import sbtcrossproject.CrossPlugin.autoImport.crossProject

inThisBuild(Seq(
  name := "Waypoint",
  normalizedName := "waypoint",
  organization := "com.raquo",
  scalaVersion := "2.13.1",
  crossScalaVersions := Seq("2.12.10", "2.13.1")
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


val baseScalacSettings =
  "-encoding" :: "UTF-8" ::
    "-unchecked" ::
    "-deprecation" ::
    "-explaintypes" ::
    "-feature" ::
    "-language:_" ::
    "-Xfuture" ::
    "-Xlint" ::
    "-Yno-adapted-args" ::
    "-Ywarn-value-discard" ::
    "-Ywarn-unused" ::
    Nil

lazy val scalacSettings = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) =>
        baseScalacSettings.diff(
          "-Xfuture" ::
            "-Yno-adapted-args" ::
            "-Ywarn-infer-any" ::
            "-Ywarn-nullary-override" ::
            "-Ywarn-nullary-unit" ::
            Nil
        )
      case _ => baseScalacSettings
    }
  }
)

lazy val commonSettings = releaseSettings ++ scalacSettings ++ Seq(
  libraryDependencies ++= Seq(
    "be.doeraene" %%% "url-dsl" % "0.2.0",
    "com.lihaoyi" %%% "upickle" % "1.0.0" % Test,
    "org.scalatest" %%% "scalatest" % "3.1.1" % Test,
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
    scalaJSLinkerConfig ~= { _.withSourceMap(false) },
    requireJsDomEnv in Test := true,
    useYarn := true,
    libraryDependencies ++= Seq(
      "org.scala-js" %%% "scalajs-dom" % "1.1.0",
      "com.raquo" %%% "airstream" % "0.10.0",
      "com.raquo" %%% "laminar" % "0.10.2" % Test,
    )
  )

lazy val waypointJS = waypoint.js
lazy val waypointJVM = waypoint.jvm // #Note the JVM project exists to provide `Route` class to the backend.
