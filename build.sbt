// MongrelDB Scala client build definition.
//
// A pure-Scala 3 client for a running `mongreldb-server` daemon, built on the
// standard-library `java.net.http.HttpClient`. No external runtime dependencies;
// the only test dependency is munit (test scope only).

ThisBuild / organization := "com.visorcraft"
ThisBuild / organizationName := "Visorcraft"
ThisBuild / homepage := Some(url("https://github.com/visorcraft/MongrelDB-Scala"))
ThisBuild / scmInfo := Some(ScmInfo(
  url("https://github.com/visorcraft/MongrelDB-Scala"),
  "scm:git:https://github.com/visorcraft/MongrelDB-Scala.git"
))
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"),
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / developers := List(
  Developer(
    id    = "visorcraft",
    name  = "Visorcraft",
    email = "support@visorcraft.com",
    url   = url("https://www.visorcraft.com")
  )
)
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

// Scala 3.
ThisBuild / scalaVersion := "3.3.3"

// Target Java 11 (the minimum that ships java.net.http.HttpClient).
ThisBuild / javacOptions ++= Seq("--release", "11")

lazy val root = (project in file("."))
  .settings(
    name := "mongreldb-scala",
    version := "0.62.0",
    libraryDependencies += "org.scalameta" %% "munit" % "0.7.29" % Test,
    // The native embedded mode (JNI) has no declared build dependency.
    // NativeDB loads libmongreldb_jni at runtime via NativeLoader, which
    // searches MONGRELDB_NATIVE_DIR, java.library.path, or the classpath
    // (for the com.visorcraft:mongreldb-jni fat JAR). Consumers add the
    // JAR to their own project when they want native mode.
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / scalacOptions ++= Seq("-release", "11"),
    Test / scalacOptions ++= Seq("-release", "11"),

    // ── Maven Central publishing ──────────────────────────────────────────
    publishMavenStyle := true,
    Test / publishArtifact := false,
    // Sources + javadoc (scaladoc) JARs, required by Maven Central.
    Compile / packageDoc / publishArtifact := true,
    // Central Publishing Portal staging API (OSSRH replacement).
    publishTo := Some(
      "central" at "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
    ),
    // Credentials read from .credentials/central.sbt (gitignored, created in CI)
    // or from env vars. See .github/workflows/release.yml.
    credentials ++= (for {
      user     <- sys.env.get("CENTRAL_USERNAME")
      password <- sys.env.get("CENTRAL_PASSWORD")
    } yield Credentials(
      "OSSRH Staging API Service",
      "ossrh-staging-api.central.sonatype.com",
      user, password
    )).toSeq,
    // POM metadata (scm, developers, licenses are set via ThisBuild above).
    pomIncludeRepository := { _ => false }
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
