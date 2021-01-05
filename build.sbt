import sbtcrossproject.CrossPlugin.autoImport.crossProject

inThisBuild(Seq(
  name := "Waypoint",
  normalizedName := "waypoint",
  organization := "com.raquo",
  scalaVersion := "2.13.4",
  crossScalaVersions := Seq("2.12.12", "2.13.4")
))

// @TODO[WTF] Why can't this be inside releaseSettings?
releaseCrossBuild := true

// @TODO[SBT] How to extract these shared settings into a separate release.sbt file?
lazy val releaseSettings = Seq(
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
  publishTo := sonatypePublishTo.value,
  releaseCrossBuild := true,
  pomIncludeRepository := { _ => false },
  useGpg := false,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value
)

val filterScalacOptions = { options: Seq[String] =>
  options.filterNot(Set(
    "-Ywarn-value-discard",
    "-Wvalue-discard"
  ))
}

lazy val commonSettings = releaseSettings ++ Seq(
  scalacOptions ~= filterScalacOptions,
  libraryDependencies ++= Seq(
    "be.doeraene" %%% "url-dsl" % "0.3.2",
    "com.lihaoyi" %%% "upickle" % "1.2.2" % Test,
    "org.scalatest" %%% "scalatest" % "3.2.3" % Test,
  )
)

lazy val root = project.in(file("."))
  .aggregate(waypointJS, waypointJVM)
  .settings(commonSettings)
  .settings(
    skip in publish := true
  )

lazy val waypoint = crossProject(JSPlatform, JVMPlatform).in(file("."))
  .settings(commonSettings)
  .jsConfigure(_.enablePlugins(ScalaJSBundlerPlugin))
  .jsSettings(
    scalaJSLinkerConfig ~= {
      _.withSourceMap(false)
    },
    requireJsDomEnv in Test := true,
    useYarn := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "1.1.0",
      "com.raquo" %%% "airstream" % "0.11.1",
      "com.raquo" %%% "laminar" % "0.11.0" % Test,
    )
  )

lazy val waypointJS = waypoint.js
lazy val waypointJVM = waypoint.jvm // #Note the JVM project exists to provide `Route` class to the backend.
