
name := """lithos-client"""
organization := "work.lithos"
version := "5.1.0-SNAPSHOT"
scalaVersion := "2.12.20"
libraryDependencies ++= Seq(
  //"org.ergoplatform" %% "ergo-appkit" % "5.0.4",
  //"io.github.k-singh" %% "plasma-toolkit" % "1.0.3", sl4j error due to logging dependency, please fix later
  "org.scalatest" %% "scalatest" % "3.2.14" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.6.19" % Test,
  "org.ergoplatform" %% "ergo-appkit" % "6.0.1" % Test classifier "tests",
  "com.squareup.okhttp3" % "mockwebserver" % "3.12.0" % Test,
  "org.scalatestplus" %% "mockito-4-6" % "3.2.14.0" % Test,
  "ch.qos.logback" % "logback-classic" % "1.5.21",
  guice,
  caffeine
)
// Reads lithos.conf from bin by default
Universal / javaOptions ++= Seq(
  "-Dconfig.file=lithos.conf"
)

Universal / packageName := "lithos-client"
Universal / packageBin := {
  val originalZip = (Universal / packageBin).value
  val newZip = originalZip.getParentFile / s"lithos-client-${version.value}.zip"
  IO.move(originalZip, newZip)
  newZip
}


Test / parallelExecution := false



Test / test / testOptions += Tests.Filter(name =>
  !name.startsWith("contracts.old.") &&
    !name.startsWith("contracts.sandbox.") &&
    !name.endsWith("ProductionBudgetSpec"))

addCommandAlias("productionBudgetTest",
  "; set Test / fork := true; Test / testOnly *ProductionBudgetSpec")

addCommandAlias("productionBudgetSmokeTest",
  "; set Test / fork := true; " +
    "set Test / javaOptions += \"-Dlithos.productionBudget.heapsMiB=1024\"; " +
    "set Test / javaOptions += \"-Dlithos.productionBudget.scenarios=all\"; " +
    "Test / testOnly *ProductionBudgetSpec")

addCommandAlias("operationalQualificationTest",
  "; set Test / fork := true; " +
    "set Test / javaOptions += \"-Dlithos.productionBudget.scenarios=fanout-large\"; " +
    "Test / testOnly *ProductionBudgetSpec")

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


