logLevel := Level.Warn

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.9.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.21.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")

addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.11")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// #TODO: Do not upgrade until https://github.com/typelevel/sbt-tpolecat/issues/102 is fixed
// @TODO Can't enable this due to (now fatal) warning in upickle macros. Uncomment to see the tests fail.
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
