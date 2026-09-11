import org.openqa.selenium.chrome.{ChromeDriver, ChromeOptions}
import org.scalajs.jsenv.selenium.SeleniumJSEnv
import sbtcrossproject.CrossPlugin.autoImport.crossProject

ThisBuild / buildKitDownloads := Seq(
  _.fromGithubTag(
    repo = "raquo/scalafmt-config",
    filePath = ".scalafmt.shared.conf",
    tag = "v0.1.0"
  ).withDoNotEditComment(_.`#`)
)

// Auto-increment version for local development
ThisBuild / version := buildKitDynVer.version.value

ThisBuild / dynver := buildKitDynVer.dynver.value

// -- Projects

lazy val root = project.in(file("."))
  .aggregate(waypointJS, waypointJVM)
  .settings(commonSettings)
  .settings(noPublish)

lazy val waypoint = crossProject(JSPlatform, JVMPlatform).in(file("."))
  .settings(commonSettings)
  .jsSettings(jsSettings)

lazy val waypointJS = waypoint.js
lazy val waypointJVM = waypoint.jvm // #Note the JVM project exists to provide `Route` class to the backend.

// -- Settings

lazy val commonSettings = releaseSettings ++ Seq(
  libraryDependencies ++= Seq(
    "be.doeraene" %%% "url-dsl" % Versions.UrlDsl,
    "com.lihaoyi" %%% "upickle" % Versions.Upickle % Test,
    "org.scalatest" %%% "scalatest" % Versions.ScalaTest % Test,
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-language:higherKinds",
    "-language:implicitConversions"
  ),
  (Test / scalacOptions) ~= { options: Seq[String] =>
    options.filterNot { o =>
      o.startsWith("-Ywarn-unused") || o.startsWith("-Wunused")
    }
  },
  (Compile / doc / scalacOptions) ++= Seq(
    "-no-link-warnings" // Suppress scaladoc "Could not find any member to link for" warnings
  )
)

// Test bundles must be served over http://, not file://: Chrome refuses
// History API calls (pushState / replaceState) on file:// origins, which
// SeleniumJSEnv's default (temp-file) materialization would use.
lazy val seleniumMaterializeDir = {
  val d = file("js/target/selenium-materialize").getAbsoluteFile
  sbt.io.IO.createDirectory(d)
  d
}

lazy val seleniumMaterializePort = 8873

lazy val seleniumHttpServer = {
  import com.sun.net.httpserver.SimpleFileServer
  val server = SimpleFileServer.createFileServer(
    new java.net.InetSocketAddress("localhost", seleniumMaterializePort),
    seleniumMaterializeDir.toPath,
    SimpleFileServer.OutputLevel.NONE
  )
  server.start()
  server
}

// Silence specific Selenium Chrome DevTools Protocol warnings we don't care about
lazy val silencedSeleniumLoggers: Seq[java.util.logging.Logger] = {
  val cdpVersionWarningFilter: java.util.logging.Filter = { record =>
    val msg = record.getMessage
    msg == null || !(
      msg.startsWith("Unable to find CDP implementation matching") ||
        msg.startsWith("Unable to find version of CDP to use for")
    )
  }
  val loggers = Seq(
    java.util.logging.Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder"),
    java.util.logging.Logger.getLogger("org.openqa.selenium.chromium.ChromiumDriver")
  )
  loggers.foreach(_.setFilter(cdpVersionWarningFilter))
  loggers
}

lazy val jsSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % Versions.ScalaJsDom,
    "com.raquo" %%% "laminar" % Versions.Laminar,
    // "com.raquo" %%% "airstream" % Versions.Airstream
  ),
  scalacOptions += pointScalaJsSourceMapsToGithub("raquo/Waypoint").value,
  jsEnv := {
    val _ = silencedSeleniumLoggers
    seleniumHttpServer // force start, memoized for the sbt session
    new SeleniumJSEnv(
      driverFactory = {
        () =>
          new ChromeDriver(
            new ChromeOptions().addArguments(
              "--headless=new",
              "--no-sandbox",
              "--disable-dev-shm-usage"
            )
          )
      },
      config = {
        SeleniumJSEnv.Config().withMaterializeInServer(
          seleniumMaterializeDir.getPath,
          s"http://localhost:$seleniumMaterializePort/"
        )
      }
    )
  },
)

// -- Release config

lazy val releaseSettings = Seq(
  name := "Waypoint",
  normalizedName := "waypoint",
  organization := "com.raquo",
  scalaVersion := Versions.Scala_3,
  crossScalaVersions := Seq(Versions.Scala_3, Versions.Scala_2_13),
  homepage := Some(url("https://github.com/raquo/waypoint")),
  licenses += ("MIT", url("https://github.com/raquo/waypoint/blob/master/LICENSE.md")),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/raquo/waypoint"),
      "scm:git@github.com/raquo/waypoint.git"
    )
  ),
  developers := List(
    Developer(
      id = "raquo",
      name = "Nikita Gazarov",
      email = "nikita@raquo.com",
      url = url("http://raquo.com")
    )
  ),
  (Test / publishArtifact) := false,
  pomIncludeRepository := { _ => false },
)

val noPublish = Seq(
  (publish / skip) := true,
  (publishLocal / skip) := true
)

// https://github.com/JetBrains/sbt-ide-settings
SettingKey[Seq[File]]("ide-excluded-directories").withRank(KeyRanks.Invisible) := Seq(
  ".buildkit", ".idea", ".metals", ".bloop", ".bsp", "target", "node_modules"
).map(file)

// Note: This project is a regression guard for the tuplez artifact conflict in
// https://github.com/raquo/Waypoint/issues/29 — Airstream and url-dsl each pull
// a different tuplez facet (`tuplez-full` vs `tuplez-full-light`) that both
// define `app.tulz.tuplez.Composition`. When their tuplez lineages diverge into
// incompatible `Composition` classes, a multi-module Scala.js link fails.
//
// A single-module runtime test can't detect this (its compile and link
// classpaths agree, so the linker's chosen `Composition` is always self
// consistent). Instead we re-link Waypoint's whole test suite with url-dsl's own
// `tuplez-full-light` excluded, which forces url-dsl's *precompiled* tuplez calls
// (`Composition.compose` / `Composition.decompose`, exercised by every route) to
// resolve against the tuplez that Airstream/Laminar bring in. If the two lineages
// agree it links & passes; if they don't, linking fails with the exact issue #29
// error. Run via `sbt tuplezConflictCheck/test`.
lazy val tuplezConflictCheck = project
  .in(file("target/tuplez-conflict-check"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(jsSettings)
  .settings(noPublish)
  .settings(
    // Reuse Waypoint's JS sources verbatim so the guard always covers exactly
    // the url-dsl / Airstream API surface that Waypoint actually uses.
    Compile / unmanagedSourceDirectories := (waypointJS / Compile / unmanagedSourceDirectories).value,
    Test / unmanagedSourceDirectories := (waypointJS / Test / unmanagedSourceDirectories).value,
    // The actual probe: drop url-dsl's bundled tuplez-full-light.
    libraryDependencies := libraryDependencies.value.map { m =>
      if (m.organization == "be.doeraene" && m.name.startsWith("url-dsl")) {
        m.exclude("app.tulz", "tuplez-full-light_sjs1_3")
      } else {
        m
      }
    }
  )
