ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "com.example"

lazy val IT = config("it") extend Test

lazy val root = (project in file("."))
  .configs(IT)
  .settings(
    inConfig(IT)(Defaults.testSettings),
    name := "weather-server",
    Compile / mainClass := Some("weather.Main"),
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-server" % "0.23.27",
      "org.http4s"     %% "http4s-ember-client" % "0.23.27",
      "org.http4s"     %% "http4s-dsl"          % "0.23.27",
      "org.http4s"     %% "http4s-circe"        % "0.23.27",
      "io.circe"       %% "circe-generic"       % "0.14.9",
      "io.circe"       %% "circe-parser"        % "0.14.9",
      "org.typelevel"  %% "cats-effect"         % "3.5.4",
      "ch.qos.logback"  % "logback-classic"     % "1.5.6",
      // Test
      "org.scalameta"  %% "munit"              % "1.0.0"  % Test,
      "org.typelevel"  %% "munit-cats-effect"  % "2.0.0"  % Test,
      // Opt-in deployment confidence checks (sbt it:test)
      "org.scalameta"  %% "munit"              % "1.0.0"  % IT,
      "org.testcontainers" % "testcontainers"  % "1.20.4" % IT
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    assembly / assemblyJarName := "weather-server.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _ @ _*) => sbtassembly.MergeStrategy.discard
      case _                             => sbtassembly.MergeStrategy.first
    }
  )
