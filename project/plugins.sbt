logLevel := Level.Warn

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.16.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.11")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.2")
