logLevel := Level.Warn

// @TODO remove when scalajs-dom is published for Scala 3.0.0-RC2
addSbtPlugin("ch.epfl.lamp" % "sbt-dotty" % "0.5.3")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.5.0")

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.20.0")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.1")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.13")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.5")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

// @TODO Can't enable this due to (now fatal) warning in upickle macros. Uncomment to see the tests fail.
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.17")
