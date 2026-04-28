ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "com.example"

lazy val root = (project in file("."))
  .settings(
    name := "weather-server",
    libraryDependencies ++= Seq(
      "org.http4s"     %% "http4s-ember-server" % "0.23.27",
      "org.http4s"     %% "http4s-ember-client" % "0.23.27",
      "org.http4s"     %% "http4s-dsl"          % "0.23.27",
      "org.http4s"     %% "http4s-circe"        % "0.23.27",
      "io.circe"       %% "circe-generic"       % "0.14.9",
      "io.circe"       %% "circe-parser"        % "0.14.9",
      "org.typelevel"  %% "cats-effect"         % "3.5.4",
      "ch.qos.logback"  % "logback-classic"     % "1.5.6",
      "dev.profunktor" %% "redis4cats-effects"  % "1.7.2",
      "dev.profunktor" %% "redis4cats-log4cats" % "1.7.2",
      "org.typelevel"  %% "log4cats-slf4j"      % "2.7.0",
      // Test
      "org.scalameta"  %% "munit"              % "1.0.0"  % Test,
      "org.typelevel"  %% "munit-cats-effect"  % "2.0.0"  % Test,
      "org.testcontainers" % "testcontainers"  % "1.21.3" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / javaOptions += "-Ddocker.api.version=1.40"
  )
