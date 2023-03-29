package scalajsesbuild

import java.nio.file.Path
import org.scalajs.jsenv.Input.Script
import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.ModuleKind
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fastLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.fullLinkJS
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.jsEnvInput
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerConfig
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSLinkerOutputDirectory
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.scalaJSStage
import org.scalajs.sbtplugin.Stage
import sbt._
import sbt.AutoPlugin
import sbt.Keys._
import sbt.nio.Keys.fileInputExcludeFilter
import sbt.nio.Keys.fileInputs
import sbt.nio.file.FileTreeView

object ScalaJSEsbuildPlugin extends AutoPlugin {

  override def requires: Plugins = ScalaJSPlugin

  object autoImport {
    val esbuildRunner: SettingKey[EsbuildRunner] =
      settingKey("Runs Esbuild commands")
    val esbuildPackageManager: SettingKey[PackageManager] =
      settingKey("Package manager to use for esbuild tasks")
    val esbuildResourcesDirectory: SettingKey[sbt.File] =
      settingKey("esbuild resource directory")
    val esbuildCopyResources: TaskKey[FileChanges] =
      taskKey("Copies over esbuild resources to target directory")
    val esbuildInstall: TaskKey[FileChanges] =
      taskKey(
        "Runs package manager's `install` in target directory on copied over esbuild resources"
      )
    val esbuildCompile: TaskKey[FileChanges] =
      taskKey(
        "Compiles module and copies output to target directory"
      )
    val esbuildBundleScript: TaskKey[String] = taskKey(
      "esbuild script used for bundling"
    ) // TODO consider doing the writing of the script upon call of this task, then use FileChanges to track changes to the script
    val esbuildBundle: TaskKey[FileChanges] = taskKey(
      "Bundles module with esbuild"
    )

    // workaround for https://github.com/sbt/sbt/issues/7164
    val esbuildFastLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fastLinkJS task to provide fileOutputs")
    val esbuildFullLinkJSWrapper =
      taskKey[Seq[Path]]("Wraps fullLinkJS task to provide fileOutputs")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
    esbuildRunner := EsbuildRunner.Default,
    esbuildResourcesDirectory := baseDirectory.value / "esbuild",
    esbuildPackageManager := PackageManager.Npm
  ) ++
    inConfig(Compile)(perConfigSettings) ++
    inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] = Seq(
    unmanagedSourceDirectories += esbuildResourcesDirectory.value,
    esbuildInstall / crossTarget := {
      crossTarget.value /
        "esbuild" /
        (if (configuration.value == Compile) "main" else "test")
    },
    esbuildCopyResources / fileInputs += (esbuildResourcesDirectory.value.toGlob / **),
    esbuildCopyResources / fileInputExcludeFilter := (esbuildCopyResources / fileInputExcludeFilter).value || (esbuildResourcesDirectory.value.toGlob / "node_modules" / **),
    esbuildCopyResources := {
      val targetDir = (esbuildInstall / crossTarget).value

      val fileChanges = esbuildCopyResources.inputFileChanges

      processFileChanges(
        fileChanges,
        esbuildResourcesDirectory.value,
        targetDir
      )

      fileChanges
    },
    esbuildInstall := {
      val changeStatus = esbuildCopyResources.value

      val s = streams.value

      val targetDir = (esbuildInstall / crossTarget).value

      val lockFile = esbuildPackageManager.value.lockFile

      FileFunction.cached(
        streams.value.cacheDirectory /
          "esbuild" /
          (if (configuration.value == Compile) "main" else "test"),
        inStyle = FilesInfo.hash
      ) { _ =>
        s.log.debug(s"Installing packages")

        esbuildPackageManager.value.install(s.log)(targetDir)

        IO.copyFile(
          targetDir / lockFile,
          esbuildResourcesDirectory.value / lockFile
        )

        Set.empty
      }(
        Set(
          esbuildResourcesDirectory.value / "package.json",
          esbuildResourcesDirectory.value / lockFile
        )
      )

      changeStatus
    },
    jsEnvInput := Def.taskDyn {
      val stageTask = scalaJSStage.value.stageTask
      Def.task {
        (stageTask / esbuildBundle).value

        jsFileNames(stageTask.value.data)
          .map((stageTask / esbuildBundle / crossTarget).value / _)
          .map(_.toPath)
          .map(Script)
          .toSeq
      }
    }.value,
    esbuildFastLinkJSWrapper := {
      fastLinkJS.value
      FileTreeView.default
        .list((fastLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    },
    esbuildFullLinkJSWrapper := {
      fullLinkJS.value
      FileTreeView.default
        .list((fullLinkJS / scalaJSLinkerOutputDirectory).value.toGlob / **)
        .map { case (path, _) =>
          path
        }
    }
  ) ++
    perScalaJSStageSettings(Stage.FastOpt) ++
    perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val (stageTask, stageTaskWrapper) =
      (stage.stageTask, stage.stageTaskWrapper)

    Seq(
      stageTask / esbuildCompile := {
        val installFileChanges = esbuildInstall.value

        stageTaskWrapper.value

        val targetDir = (esbuildInstall / crossTarget).value

        val stageTaskFileChanges = stageTaskWrapper.outputFileChanges

        processFileChanges(
          stageTaskFileChanges,
          (stageTask / scalaJSLinkerOutputDirectory).value,
          targetDir
        )

        installFileChanges ++ stageTaskFileChanges
      },
      stageTask / esbuildBundle / crossTarget := (esbuildInstall / crossTarget).value / "out",
      stageTask / esbuildBundleScript := {
        val targetDir = (esbuildInstall / crossTarget).value
        val stageTaskReport = stageTask.value.data
        val outdir =
          (stageTask / esbuildBundle / crossTarget).value
        generateEsbuildBundleScript(
          targetDir,
          outdir,
          stageTaskReport,
          hashOutputFiles = false,
          Seq.empty
        )
      },
      stageTask / esbuildBundle := {
        val log = streams.value.log

        val fileChanges = (stageTask / esbuildCompile).value
        val bundlingScript = (stageTask / esbuildBundleScript).value

        if (fileChanges.hasChanges) {
          val targetDir = (esbuildInstall / crossTarget).value

          val scriptFileName = "sbt-scalajs-esbuild-bundle-script.cjs"
          IO.write(targetDir / scriptFileName, bundlingScript)

          esbuildRunner.value.run(log)(scriptFileName, targetDir)
        }

        fileChanges
      }
    )
  }
}
