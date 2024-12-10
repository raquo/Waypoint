import com.raquo.buildkit.SourceDownloader
import sbtcrossproject.CrossPlugin.autoImport.crossProject
import VersionHelper.{versionFmt, fallbackVersion}

// Get Maven releases faster
resolvers ++= Resolver.sonatypeOssRepos("public")

lazy val preload = taskKey[Unit]("runs Laminar-specific pre-load tasks")

preload := {
  val projectDir = (ThisBuild / baseDirectory).value
  // TODO Move code generators here as well?

  SourceDownloader.downloadVersionedFile(
    name = "scalafmt-shared-conf",
    version = "v0.1.0",
    urlPattern = version => s"https://raw.githubusercontent.com/raquo/scalafmt-config/refs/tags/$version/.scalafmt.shared.conf",
    versionFile = projectDir / ".downloads" / ".scalafmt.shared.conf.version",
    outputFile = projectDir / ".downloads" / ".scalafmt.shared.conf",
    processOutput = "#\n# DO NOT EDIT. See SourceDownloader in build.sbt\n" + _
  )
}

Global / onLoad := {
  (Global / onLoad).value andThen { state => preload.key.label :: state }
}

// Makes sure to increment the version for local development
ThisBuild / version := dynverGitDescribeOutput.value
  .mkVersion(out => versionFmt(out, dynverSonatypeSnapshots.value), fallbackVersion(dynverCurrentDate.value))

ThisBuild / dynver := {
  val d = new java.util.Date
  sbtdynver.DynVer
    .getGitDescribeOutput(d)
    .mkVersion(out => versionFmt(out, dynverSonatypeSnapshots.value), fallbackVersion(d))
}

// -- Projects

lazy val root = project.in(file("."))
  .aggregate(waypointJS, waypointJVM)
  .settings(commonSettings)
  .settings(noPublish)

lazy val waypoint = crossProject(JSPlatform, JVMPlatform).in(file("."))
  .settings(commonSettings)
  .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
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

lazy val jsSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % Versions.ScalaJsDom,
    "com.raquo" %%% "laminar" % Versions.Laminar,
    "com.raquo" %%% "airstream" % Versions.Airstream
  ),
  scalacOptions ++= sys.env.get("CI").map { _ =>
    val localSourcesPath = (LocalRootProject / baseDirectory).value.toURI
    val remoteSourcesPath = s"https://raw.githubusercontent.com/raquo/Waypoint/${git.gitHeadCommit.value.get}/"
    val sourcesOptionName = if (scalaVersion.value.startsWith("2.")) "-P:scalajs:mapSourceURI" else "-scalajs-mapSourceURI"

    s"${sourcesOptionName}:$localSourcesPath->$remoteSourcesPath"
  },
  (Test / requireJsDomEnv) := true,
  (installJsdom / version) := Versions.JsDom,
  (webpack / version) := Versions.Webpack,
  (startWebpackDevServer / version) := Versions.WebpackDevServer,
  useYarn := true
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
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
)

val noPublish = Seq(
  (publish / skip) := true,
  (publishLocal / skip) := true
)

// https://github.com/JetBrains/sbt-ide-settings
SettingKey[Seq[File]]("ide-excluded-directories").withRank(KeyRanks.Invisible) := Seq(
  ".downloads", ".idea", ".metals", ".bloop", ".bsp"
).map(file)
