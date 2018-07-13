name := """editor"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.6"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"

routesGenerator := InjectedRoutesGenerator

libraryDependencies += jdbc
libraryDependencies += "mysql" % "mysql-connector-java" % "8.0.11"

libraryDependencies += "com.typesafe.play" %% "play-slick" % "3.0.0"

libraryDependencies += "org.springframework.security" % "spring-security-web" % "5.0.6.RELEASE"
