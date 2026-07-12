// MongrelDB Scala client build definition.
//
// A pure-Scala 3 client for a running `mongreldb-server` daemon, built on the
// standard-library `java.net.http.HttpClient`. No external runtime dependencies;
// the only test dependency is munit (test scope only).

ThisBuild / organization := "com.visorcraft"
ThisBuild / organizationName := "VisorCraft LLC"
ThisBuild / homepage := Some(url("https://github.com/visorcraft/MongrelDB-Scala"))
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"),
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

// Scala 3.
ThisBuild / scalaVersion := "3.3.3"

// Target Java 11 (the minimum that ships java.net.http.HttpClient).
ThisBuild / javacOptions ++= Seq("--release", "11")

lazy val root = (project in file("."))
  .settings(
    name := "mongreldb-scala",
    version := "0.1.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / scalacOptions ++= Seq("-release", "11"),
    Test / scalacOptions ++= Seq("-release", "11")
  )

// The examples depend on the client. CI compiles them with the client on the
// classpath and runs each main class.
lazy val examples = (project in file("examples"))
  .dependsOn(root)
  .settings(
    name := "mongreldb-scala-examples",
    publish / skip := true,
    Compile / scalacOptions ++= Seq("-release", "11")
  )
