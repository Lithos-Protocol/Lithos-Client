
name := """lithos-client"""
organization := "work.lithos"
version := "2.0.2-SNAPSHOT"
scalaVersion := "2.12.10"
libraryDependencies ++= Seq(
  //"org.ergoplatform" %% "ergo-appkit" % "5.0.4",
  //"io.github.k-singh" %% "plasma-toolkit" % "1.0.3", sl4j error due to logging dependency, please fix later
  "org.scalatest" %% "scalatest" % "3.2.14" % "test",
  "ch.qos.logback" % "logback-classic" % "1.5.21",
  guice,
  caffeine
)
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


