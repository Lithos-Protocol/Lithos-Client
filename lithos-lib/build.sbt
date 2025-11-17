
name := """lithos-lib"""
organization := "work.lithos"
version := "1.0-SNAPSHOT"
scalaVersion := "2.12.10"
lazy val scriptClasspath = Seq("*")
libraryDependencies ++= Seq(
  //"org.ergoplatform" %% "ergo-appkit" % "5.0.4",
  "org.scorexfoundation" %% "sigma-state" % "6.0.2",
  "org.ergoplatform" %% "ergo-wallet" % "6.0.0",
  //"io.github.k-singh" %% "plasma-toolkit" % "1.0.3", sl4j error due to logging dependency, please fix later
  "org.scalatest" %% "scalatest" % "3.2.14" % "test",
  "com.github.Satergo" % "JStratum" % "b3ad654112",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.70",
  "com.squareup.okhttp3" % "okhttp" % "3.12.0",
  "com.google.code.findbugs" % "jsr305" % "3.0.2",
  "io.gsonfire" % "gson-fire" % "1.8.3" % "compile",
  "io.swagger.core.v3" % "swagger-annotations" % "2.0.0",
  "com.squareup.retrofit2" % "retrofit" % "2.6.2",
  "com.squareup.retrofit2" % "converter-scalars" % "2.6.2",
  "com.squareup.retrofit2" % "converter-gson" % "2.6.2",
  "javax.xml.bind" % "jaxb-api" % "2.4.0-b180830.0359",
  "org.ethereum" % "leveldbjni-all"     % "1.18.3",
  "org.iq80.leveldb" % "leveldb" % "0.12",
)
resolvers ++= Seq(
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "SonaType" at "https://oss.sonatype.org/content/groups/public",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Bintray" at "https://jcenter.bintray.com/",
  "jitpack" at "https://jitpack.io"
)



