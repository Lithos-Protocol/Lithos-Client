
name := """lithos-client"""
organization := "work.lithos"
version := "5.0.0-SNAPSHOT"
scalaVersion := "2.12.20"
libraryDependencies ++= Seq(
  //"org.ergoplatform" %% "ergo-appkit" % "5.0.4",
  //"io.github.k-singh" %% "plasma-toolkit" % "1.0.3", sl4j error due to logging dependency, please fix later
  "org.scalatest" %% "scalatest" % "3.2.14" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % Test,
  "org.ergoplatform" %% "ergo-appkit" % "6.0.0" % Test classifier "tests",
  "com.squareup.okhttp3" % "mockwebserver" % "3.12.0" % Test,
  "org.scalatestplus" %% "mockito-4-6" % "3.2.14.0" % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.21",
  guice,
  caffeine
)

Test / parallelExecution := false


// Scoped to `test`, NOT to the whole Test config, so `testOnly` can still reach these by name.
// They are excluded from a plain `sbt test` because several need a live node and a real key — but
// the sandbox is a set of manual levers you are meant to run one at a time, and filtering the whole
// configuration made it unreachable: `testOnly contracts.sandbox.…` answered "No tests to run".
Test / test / testOptions += Tests.Filter(name =>
  !name.startsWith("contracts.old.") && !name.startsWith("contracts.sandbox."))

resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Bintray" at "https://jcenter.bintray.com/"
)
lazy val scriptClasspath = Seq("*")
lazy val lib = Project(id = "lithos-lib", base = file("lithos-lib"))

lazy val root = Project(id = "lithos-client", base = file("."))
  .enablePlugins(PlayScala, LauncherJarPlugin)
  .disablePlugins(PlayLogback)
  .dependsOn(lib)
  .aggregate(lib)


