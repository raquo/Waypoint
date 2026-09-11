// Compiles the vendored scala-js-env-selenium sources (git submodule at
// vendor/scala-js-env-selenium, pinned to the commit from
// https://github.com/scala-js/scala-js-env-selenium/pull/131, which adds
// Selenium 4 support) into the meta-build classpath, so that build.sbt can
// construct a SeleniumJSEnv directly.
//
// We vendor via a submodule + unmanagedSourceDirectories, rather than a
// published artifact, because that PR is approved and CI-green but not yet
// merged/released.
Compile / unmanagedSourceDirectories +=
  baseDirectory.value.getParentFile / "vendor" / "scala-js-env-selenium" / "seleniumJSEnv" / "src" / "main" / "scala"

// Dependencies for the vendored scala-js-env-selenium sources in vendor/scala-js-env-selenium
// (see project/vendor.sbt), pinned to match https://github.com/scala-js/scala-js-env-selenium/pull/131
libraryDependencies ++= Seq(
  "org.seleniumhq.selenium" % "selenium-java" % "4.43.0",
  "org.scala-js" %% "scalajs-js-envs" % "1.6.0",
  "com.google.jimfs" % "jimfs" % "1.3.1",
)
