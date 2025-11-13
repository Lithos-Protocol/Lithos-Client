

name := """lithos-lib"""
organization := "work.lithos"
version := "1.0-SNAPSHOT"
scalaVersion := "2.12.10"

libraryDependencies ++= Seq(
  "org.ergoplatform" %% "ergo-appkit" % "5.0.4",
  //"io.github.k-singh" %% "plasma-toolkit" % "1.0.3", sl4j error due to logging dependency, please fix later
  "org.scalatest" %% "scalatest" % "3.2.14" % "test",
  "com.github.Satergo" % "JStratum" % "b3ad654112",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.70"
)
resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Bintray" at "https://jcenter.bintray.com/",
  "jitpack" at "https://jitpack.io"
)



