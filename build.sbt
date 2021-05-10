import sbtcrossproject.CrossPlugin.autoImport.crossProject


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
    "org.scalatest" %%% "scalatest" % (if (scalaVersion.value == Versions.Scala_3_RC2) "3.2.7" else "3.2.8") % Test,
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
    ("org.scala-js" %%% "scalajs-dom" % Versions.ScalaJsDom).withDottyCompat(scalaVersion.value),
    "com.raquo" %%% "airstream" % Versions.Airstream,
    "com.raquo" %%% "laminar" % Versions.Laminar % Test,
  ),
  scalacOptions += {
    val localSourcesPath = baseDirectory.value.toURI
    val remoteSourcesPath = s"https://raw.githubusercontent.com/raquo/Waypoint/${git.gitHeadCommit.value.get}/"
    val sourcesOptionName = if (scalaVersion.value.startsWith("2.")) "-P:scalajs:mapSourceURI" else "-scalajs-mapSourceURI"

    s"${sourcesOptionName}:$localSourcesPath->$remoteSourcesPath"
  },
  scalaJSLinkerConfig ~= { _.withSourceMap(false) },
  (Test / requireJsDomEnv) := true,
  useYarn := true
)


// -- Release config

lazy val releaseSettings = Seq(
  name := "Waypoint",
  normalizedName := "waypoint",
  organization := "com.raquo",
  scalaVersion := Versions.Scala_3_RC3,
  crossScalaVersions := Versions.Scala_3_RC3 :: Nil,
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
  sonatypeProfileName := "com.raquo",
  publishMavenStyle := true,
  (Test / publishArtifact) := false,
  publishTo := sonatypePublishToBundle.value,
  releaseCrossBuild := true,
  pomIncludeRepository := { _ => false },
  useGpg := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value
)

// @TODO Does this need to be here too?
releaseCrossBuild := true

releaseProcess := {
  import ReleaseTransformations._
  Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("+publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
}

val noPublish = Seq(
  (publish / skip) := true,
  (publishLocal / skip) := true,
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
)
