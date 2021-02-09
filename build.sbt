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
    "be.doeraene" %%% "url-dsl" % "0.3.2",
    "com.lihaoyi" %%% "upickle" % "1.0.0" % Test,
    "org.scalatest" %%% "scalatest" % "3.2.3" % Test,
  ),
  scalacOptions ~= { options: Seq[String] =>
    options.filterNot(Set(
      "-Ywarn-value-discard",
      "-Wvalue-discard"
    ))
  },
  scalacOptions in Test ~= { options: Seq[String] =>
    options.filterNot { o =>
      o.startsWith("-Ywarn-unused") || o.startsWith("-Wunused")
    }
  }
)

lazy val jsSettings = Seq(
  scalaJSLinkerConfig ~= {
    _.withSourceMap(false)
  },
  requireJsDomEnv in Test := true,
  useYarn := true,
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "1.1.0",
    "com.raquo" %%% "airstream" % "0.12.0-M2",
    "com.raquo" %%% "laminar" % "0.12.0-M2" % Test,
  )
)


// -- Release config

lazy val releaseSettings = Seq(
  name := "Waypoint",
  normalizedName := "waypoint",
  organization := "com.raquo",
  scalaVersion := "2.13.4",
  crossScalaVersions := Seq("2.12.12", "2.13.4"),
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
  publishArtifact in Test := false,
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
  skip in publish := true,
  skip in publishLocal := true,
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
)
