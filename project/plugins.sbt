logLevel := Level.Warn

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.7.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.20.0")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.1.0")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")

addSbtPlugin("com.github.sbt" % "sbt-release" % "1.1.0")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.9.10")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

// @TODO Can't enable this due to (now fatal) warning in upickle macros. Uncomment to see the tests fail.
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.20")
