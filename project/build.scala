/*
 * Copyright (c) 2013 Novus Partners, Inc. (http://www.novus.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import sbt._
import Keys._
import sbtassembly.Plugin._
import AssemblyKeys._

object NovusjdbcBuild extends sbt.Build {

  artifact in (Compile, assembly) ~= { _.copy(`classifier` = Some("assembly")) }

  lazy val root = Project(
    id = "novus-jdbc",
    base = file("."),
    settings = baseSettings
  ).aggregate(novusJdbc, novusJdbcBonecp, novusJdbcDBCP, novusJdbcC3P0, novusJdbcTomcat, novusJdbcLogging)
  
  lazy val novusJdbc = Project(
    id = "novus-jdbc-core",
    base = file("novus-jdbc-core"),
    settings = baseSettings ++
      Seq(libraryDependencies <++= scalaVersion (v => Seq(
        "net.sourceforge.jtds" % "jtds" % "1.2.6",
        "org.slf4j" % "slf4j-api" % "1.7.2",
        "joda-time" % "joda-time" % "2.1",
        "org.joda" % "joda-convert" % "1.2" % "compile",
        "org.hsqldb" % "hsqldb" % "2.2.9" % "test"
    ) ++ Shared.specsDep(v))))

  lazy val novusJdbcBonecp = Project(
    id = "novus-jdbc-bonecp",
    base = file("novus-jdbc-bonecp"),
    settings = baseSettings ++ Seq(libraryDependencies <++= scalaVersion(v => Seq(
      "com.jolbox" % "bonecp" % "0.7.1.RELEASE"
    ) ++ Shared.specsDep(v)))
  ).dependsOn(novusJdbc)

  lazy val novusJdbcDBCP = Project(
    id = "novus-jdbc-dbcp",
    base = file("novus-jdbc-dbcp"),
    settings = baseSettings ++Seq(libraryDependencies <++= scalaVersion (v => Seq(
      "commons-dbcp" % "commons-dbcp" % "1.4"
    ))))
    .dependsOn(novusJdbc)

  lazy val novusJdbcC3P0 = Project(
    id = "novus-jdbc-c3p0",
    base = file("novus-jdbc-c3p0"),
    settings = baseSettings ++ Seq(libraryDependencies <++= scalaVersion (v => Seq(
      "c3p0" % "c3p0" % "0.9.1.2" //technically 0.9.2 is latest but need to download it
    ))))
    .dependsOn(novusJdbc)

  lazy val novusJdbcTomcat = Project(
    id = "novus-jdbc-tomcat",
    base = file("novus-jdbc-tomcat"),
    settings = baseSettings ++ Seq(libraryDependencies <++= scalaVersion (v => Seq(
      "org.apache.tomcat" % "tomcat-jdbc" % "7.0.37"
    ))))
    .dependsOn(novusJdbc)

  lazy val novusJdbcLogging = Project(
    id = "novus-jdbc-logging",
    base = file("novus-jdbc-logging"),
    settings = baseSettings ++ Seq(libraryDependencies <++= scalaVersion (v => Seq(
      "ch.qos.logback" % "logback-classic" % "1.0.7"
    ) ++ Shared.specsDep(v))))

  lazy val baseSettings = Project.defaultSettings ++ Seq(
    organization := "com.novus",
    version := "0.9.5-FINAL",
    scalaVersion := "2.10.2",
    crossScalaVersions := Seq("2.9.0", "2.9.1", "2.9.2"), //Technically this won't work unless you comment things out
    initialCommands := "import com.novus.jdbc._",
    scalacOptions := Seq("-deprecation", "-unchecked", "-feature", "-language:postfixOps"),
    resolvers ++= Seq(
      "Scala-Tools Maven2 Snapshots Repository" at "http://scala-tools.org/repo-snapshots",
      "snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
      "releases"  at "http://oss.sonatype.org/content/repositories/releases"
    ),
    credentials += Credentials(Path.userHome / ".ivy2" / ".novus_nexus"),
    publishTo <<= (version) { version: String =>
      val sfx = if(version.trim.endsWith("SNAPSHOT")) "snapshots" else "releases"
      val nexus = "https://nexus.novus.com:65443/nexus/content/repositories/"
      Some("Novus " + sfx at nexus + sfx + "/")
    }) ++ assemblySettings
}

object Shared {

  val mockito = "org.mockito" % "mockito-all" % "1.9.0"

  /** Resolve specs version for the current scala version (thanks @n8han). */
  def specsDep(sv: String, cfg: String = "test") =
    (sv.split("[.-]").toList match {
      case "2" :: "9" :: "0" :: _ => "org.specs2" % "specs2_2.9.1" % "1.7.1" :: mockito :: Nil
      case "2" :: "9" :: _ :: _ => "org.specs2" % "specs2_2.9.1" % "1.8.2" :: mockito :: Nil
      case "2" :: "10" :: _ => "org.specs2" % "specs2_2.10" % "1.14" :: mockito :: Nil
      case _ => sys.error("Specs not supported for scala version %s" format sv)
    }) map (_ % cfg)
  
}