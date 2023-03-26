package scalajsesbuild

import sbt.AutoPlugin
import sbt._
import sbt.Keys._
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildCompile
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildInstall
import scalajsesbuild.ScalaJSEsbuildPlugin.autoImport.esbuildRunner

import scala.sys.process._

object ScalaJSEsbuildDomPlugin extends AutoPlugin {

  override def requires = ScalaJSEsbuildPlugin

  object autoImport {
    val esbuildServeScript: TaskKey[String] = taskKey(
      "esbuild script used for serving"
    ) // TODO consider doing the writing of the script upon call of this task, then use FileChanges to track changes to the script
    val esbuildServeStart =
      taskKey[Unit]("Runs esbuild serve on target directory")
    val esbuildServeStop =
      taskKey[Unit]("Stops running esbuild serve on target directory")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Compile)(perConfigSettings) ++
      inConfig(Test)(perConfigSettings)

  private lazy val perConfigSettings: Seq[Setting[_]] =
    perScalaJSStageSettings(Stage.FastOpt) ++
      perScalaJSStageSettings(Stage.FullOpt)

  private def perScalaJSStageSettings(stage: Stage): Seq[Setting[_]] = {
    val stageTask = stage.stageTask

    // TODO deal with entry points when bundling

    {
      var process: Option[Process] = None

      def terminateProcess(log: Logger) = {
        process.foreach { process =>
          log.info(s"Stopping esbuild serve process")
          process.destroy()
        }
        process = None
      }

      Seq(
        stageTask / esbuildServeScript := {
          val targetDir = (esbuildInstall / crossTarget).value

          val entryPoints = jsFileNames(stageTask.value.data)
            .map(jsFileName => s"'${(targetDir / jsFileName).absolutePath}'")
            .toSeq
          val outdir =
            (stageTask / esbuildServeStart / crossTarget).value.absolutePath
          val outdirEscaped = escapePathString(outdir)

          s"""
             |const http = require('http');
             |const esbuild = require('esbuild');
             |const jsdom = require("jsdom")
             |const { JSDOM } = jsdom;
             |const fs = require('fs');
             |const path = require('path');
             |
             |const htmlTransform = (htmlString, outDirectory) => {
             |  const workingDirectory = __dirname;
             |
             |  const meta = JSON.parse(fs.readFileSync(path.join(__dirname, "sbt-scalajs-esbuild-serve-meta.json")));
             |
             |  const dom = new JSDOM(htmlString);
             |  dom.window.document.querySelectorAll("script").forEach((el) => {
             |    let output;
             |    let outputBundle;
             |    Object.keys(meta.outputs).every((key) => {
             |      const maybeOutput = meta.outputs[key];
             |      if (el.src.endsWith(maybeOutput.entryPoint)) {
             |        output = maybeOutput;
             |        outputBundle = key;
             |        return false;
             |      }
             |      return true;
             |    })
             |    if (output) {
             |     let absolute = el.src.startsWith("/");
             |     el.src = el.src.replace(output.entryPoint, path.relative(outDirectory, path.join(workingDirectory, outputBundle)));
             |     if (output.cssBundle) {
             |       const link = dom.window.document.createElement("link");
             |       link.rel = "stylesheet";
             |       link.href = (absolute ? "/" : "") + path.relative(outDirectory, path.join(workingDirectory, output.cssBundle));
             |       el.parentNode.insertBefore(link, el.nextSibling);
             |     }
             |    }
             |  });
             |  return dom.serialize();
             |}
             |
             |const esbuildLiveReload = (htmlString) => {
             |  return htmlString
             |    .toString()
             |    .replace("</head>", `
             |      <script type="text/javascript">
             |        // Based on https://esbuild.github.io/api/#live-reload
             |        new EventSource('/esbuild').addEventListener('change', e => {
             |          const { added, removed, updated } = JSON.parse(e.data)
             |
             |          if (!added.length && !removed.length && updated.length === 1) {
             |            for (const link of document.getElementsByTagName("link")) {
             |              const url = new URL(link.href)
             |
             |              if (url.host === location.host && url.pathname === updated[0]) {
             |                const next = link.cloneNode()
             |                next.href = updated[0] + '?' + Math.random().toString(36).slice(2)
             |                next.onload = () => link.remove()
             |                link.parentNode.insertBefore(next, link.nextSibling)
             |                return
             |              }
             |            }
             |          }
             |
             |          location.reload()
             |        })
             |      </script>
             |    </head>
             |    `);
             |}
             |
             |const serve = async () => {
             |    // Start esbuild's local web server. Random port will be chosen by esbuild.
             |
             |    const plugins = [{
             |      name: 'metafile-plugin',
             |      setup(build) {
             |        let count = 0;
             |        build.onEnd(result => {
             |          if (count++ === 0) {
             |            fs.writeFileSync('sbt-scalajs-esbuild-serve-meta.json', JSON.stringify(result.metafile));
             |          } else {
             |            fs.writeFileSync('sbt-scalajs-esbuild-serve-meta.json', JSON.stringify(result.metafile));
             |          }
             |        });
             |      },
             |    }];
             |
             |    const ctx  = await esbuild.context({
             |      ${esbuildOptions(
              entryPoints,
              outdir,
              hashOutputFiles = false,
              minify = false
            )}
             |      plugins: plugins,
             |    });
             |
             |    await ctx.watch()
             |
             |    const { host, port } = await ctx.serve({
             |        servedir: '$outdirEscaped',
             |        port: 8001
             |    });
             |
             |    // Create a second (proxy) server that will forward requests to esbuild.
             |    const proxy = http.createServer((req, res) => {
             |        // forwardRequest forwards an http request through to esbuid.
             |        const forwardRequest = (path) => {
             |            const options = {
             |                hostname: host,
             |                port,
             |                path,
             |                method: req.method,
             |                headers: req.headers,
             |            };
             |
             |            const proxyReq = http.request(options, (proxyRes) => {
             |                if (proxyRes.statusCode === 404) {
             |                    // If esbuild 404s the request, assume it's a route needing to
             |                    // be handled by the JS bundle, so forward a second attempt to `/`.
             |                    return forwardRequest("/");
             |                }
             |
             |                // Otherwise esbuild handled it like a champ, so proxy the response back.
             |                res.writeHead(proxyRes.statusCode, proxyRes.headers);
             |                proxyRes.pipe(res, { end: true });
             |            });
             |
             |            req.pipe(proxyReq, { end: true });
             |        };
             |
             |        if (req.url === "/" || req.url.endsWith(".html")) {
             |          let file;
             |          if (req.url === "/") {
             |            file = "/index.html";
             |          } else {
             |            file = req.url;
             |          }
             |
             |          fs.readFile("."+file, function (err, html) {
             |            if (err) {
             |              throw err;
             |            } else {
             |              res.writeHead(200, {"Content-Type": "text/html"});
             |              res.write(htmlTransform(esbuildLiveReload(html), "$outdirEscaped"));
             |              res.end();
             |            }
             |          });
             |        } else {
             |          // When we're called pass the request right through to esbuild.
             |          forwardRequest(req.url);
             |        }
             |    });
             |
             |    // Start our proxy server at the specified `listen` port.
             |    proxy.listen(8000);
             |
             |    console.log("Started esbuild serve process [http://localhost:8000]");
             |};
             |
             |// Serves all content from $outdir on :8000.
             |// If esbuild 404s the request, the request is attempted again
             |// from `/` assuming that it's an SPA route needing to be handled by the root bundle.
             |serve();
             |""".stripMargin
        },
        stageTask / esbuildServeStart / crossTarget := (esbuildInstall / crossTarget).value / "www",
        stageTask / esbuildServeStart := {
          val logger = state.value.globalLogging.full

          (stageTask / esbuildServeStop).value

          (stageTask / esbuildCompile).value

          val targetDir = (esbuildInstall / crossTarget).value

          val script = (stageTask / esbuildServeScript).value

          logger.info(s"Starting esbuild serve process")
          val scriptFileName = "sbt-scalajs-esbuild-serve-script.cjs"
          IO.write(targetDir / scriptFileName, script)

          process =
            Some(esbuildRunner.value.process(logger)(scriptFileName, targetDir))
        },
        stageTask / esbuildServeStop := {
          terminateProcess(streams.value.log)
        },
        (onLoad in Global) := {
          (onLoad in Global).value.compose(
            _.addExitHook {
              terminateProcess(Keys.sLog.value)
            }
          )
        }
      )
    }
  }
}