/*
 * Copyright (2020) The Delta Lake Project Authors.
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

import ReleaseTransformations._

parallelExecution in ThisBuild := false
scalastyleConfig in ThisBuild := baseDirectory.value / "scalastyle-config.xml"

lazy val compileScalastyle = taskKey[Unit]("compileScalastyle")
lazy val testScalastyle = taskKey[Unit]("testScalastyle")

crossScalaVersions := Seq("2.12.8", "2.11.12")

val sparkVersion = "2.4.3"
val hadoopVersion = "2.7.2"
val hiveVersion = "2.3.7"
val deltaVersion = "0.5.0"

lazy val commonSettings = Seq(
  organization := "io.delta",
  scalaVersion := "2.12.8",
  fork := true,
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
  scalacOptions += "-target:jvm-1.8",
  // Configurations to speed up tests and reduce memory footprint
  javaOptions in Test ++= Seq(
    "-Dspark.ui.enabled=false",
    "-Dspark.ui.showConsoleProgress=false",
    "-Dspark.databricks.delta.snapshotPartitions=2",
    "-Dspark.sql.shuffle.partitions=5",
    "-Ddelta.log.cacheSize=3",
    "-Dspark.sql.sources.parallelPartitionDiscovery.parallelism=5",
    "-Xmx1024m"
  ),
  compileScalastyle := scalastyle.in(Compile).toTask("").value,
  (compile in Compile) := ((compile in Compile) dependsOn compileScalastyle).value,
  testScalastyle := scalastyle.in(Test).toTask("").value,
  (test in Test) := ((test in Test) dependsOn testScalastyle).value
)

lazy val hive = (project in file("hive")) dependsOn(standalone) settings (
  name := "hive-delta",
  commonSettings,

  // Minimal dependencies to compile the codes. This project doesn't run any tests so we don't need
  // any runtime dependencies.
  libraryDependencies ++= Seq(
    "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
    "org.apache.parquet" % "parquet-hadoop" % "1.10.1" % "provided",
    "org.apache.hive" % "hive-exec" % hiveVersion % "provided" classifier "core" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
      ExclusionRule(organization = "com.google.protobuf")
    ),
    "org.apache.hive" % "hive-metastore" % hiveVersion % "provided"  excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("org.apache.hive", "hive-exec")
    ),
    "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("ch.qos.logback", "logback-classic"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
      ExclusionRule("org.apache.hive", "hive-exec"),
      ExclusionRule(organization = "com.google.protobuf")
    ),
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "io.delta" %% "delta-core" % deltaVersion % "test",
    "org.apache.spark" %% "spark-sql" % sparkVersion % "test",
    "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
    "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
    "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests"
  ),

  /** Hive assembly jar. Build with `assembly` command */
  logLevel in assembly := Level.Info,
  test in assembly := {},
  assemblyJarName in assembly := s"${name.value}-assembly_${scalaBinaryVersion.value}-${version.value}.jar",
  // default merge strategy
  assemblyShadeRules in assembly :=
    (if (scalaBinaryVersion.value == "2.12") Seq(
      // shade to solve this issue: https://issues.apache.org/jira/browse/SPARK-22128
      ShadeRule.rename("com.thoughtworks.paranamer.**" -> "shadedelta.@0").inAll
    ) else Nil)
)

lazy val hiveMR = (project in file("hive-mr")) dependsOn(hive % "test->test") settings (
  name := "hive-mr",
  commonSettings,
  libraryDependencies ++= Seq(
    "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
    "org.apache.hive" % "hive-exec" % hiveVersion % "provided" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
    ),
    "org.apache.hadoop" % "hadoop-common" % hadoopVersion % "test" classifier "tests",
    "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersion % "test",
    "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersion % "test" classifier "tests",
    "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersion % "test" classifier "tests",
    "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("ch.qos.logback", "logback-classic"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm")
    ),
    // TODO Figure out how this fixes some bad dependency
    "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "io.delta" %% "delta-core" % deltaVersion % "test" excludeAll ExclusionRule("org.apache.hadoop")
  )
)

lazy val hiveTez = (project in file("hive-tez")) dependsOn(hive % "test->test") settings (
  name := "hive-tez",
  commonSettings,
  libraryDependencies ++= Seq(
    "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided" excludeAll (
      ExclusionRule(organization = "com.google.protobuf")
      ),
    "org.apache.parquet" % "parquet-hadoop" % "1.10.1" excludeAll(
      ExclusionRule("org.apache.hadoop", "hadoop-client")
      ),
    "com.google.protobuf" % "protobuf-java" % "2.5.0",
    "org.apache.hive" % "hive-exec" % hiveVersion % "provided" classifier "core" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
      ExclusionRule(organization = "com.google.protobuf")
    ),
    "org.jodd" % "jodd-core" % "3.5.2",
    "org.apache.hive" % "hive-metastore" % hiveVersion % "provided" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("org.apache.hive", "hive-exec")
    ),
    "org.apache.hadoop" % "hadoop-common" % hadoopVersion % "test" classifier "tests",
    "org.apache.hadoop" % "hadoop-mapreduce-client-hs" % hadoopVersion % "test",
    "org.apache.hadoop" % "hadoop-mapreduce-client-jobclient" % hadoopVersion % "test" classifier "tests",
    "org.apache.hadoop" % "hadoop-yarn-server-tests" % hadoopVersion % "test" classifier "tests",
    "org.apache.hive" % "hive-cli" % hiveVersion % "test" excludeAll(
      ExclusionRule(organization = "org.apache.spark"),
      ExclusionRule(organization = "org.apache.parquet"),
      ExclusionRule("ch.qos.logback", "logback-classic"),
      ExclusionRule("org.pentaho", "pentaho-aggdesigner-algorithm"),
      ExclusionRule("org.apache.hive", "hive-exec"),
      ExclusionRule(organization = "com.google.protobuf")
    ),
    "org.apache.hadoop" % "hadoop-yarn-common" % hadoopVersion % "test",
    "org.apache.hadoop" % "hadoop-yarn-api" % hadoopVersion % "test",
    "org.apache.tez" % "tez-mapreduce" % "0.8.4" % "test",
    "org.apache.tez" % "tez-dag" % "0.8.4" % "test",
    "org.apache.tez" % "tez-tests" % "0.8.4" % "test" classifier "tests",
    // TODO Figure out how this fixes some bad dependency
    "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "io.delta" %% "delta-core" % deltaVersion % "test" excludeAll ExclusionRule("org.apache.hadoop")
  )
)

lazy val standalone = (project in file("standalone"))
  .settings(
    name := "standalone",
    commonSettings,
    unmanagedResourceDirectories in Test += file("golden-tables/src/test/resources"),
    libraryDependencies ++= Seq(
      "org.apache.hadoop" % "hadoop-client" % hadoopVersion % "provided",
      "org.apache.parquet" % "parquet-hadoop" % "1.10.1" % "provided",
      "com.github.mjakubowski84" %% "parquet4s-core" % "1.2.1" excludeAll (
        ExclusionRule("org.slf4j", "slf4j-api"),
        ExclusionRule("org.apache.parquet", "parquet-hadoop")
      ),
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.6.7.1",
      "org.json4s" %% "json4s-jackson" % "3.5.3" excludeAll (
        ExclusionRule("com.fasterxml.jackson.core"),
        ExclusionRule("com.fasterxml.jackson.module")
      ),
      "org.scalatest" %% "scalatest" % "3.0.5" % "test"
    ))

  /**
   * Unidoc settings
   * Generate javadoc with `unidoc` command, outputs to `standalone/target/javaunidoc`
   */
  .enablePlugins(GenJavadocPlugin, JavaUnidocPlugin)
  .settings(
    javacOptions in (JavaUnidoc, unidoc) := Seq(
      "-public",
      "-windowtitle", "Delta Standalone Reader " + version.value.replaceAll("-SNAPSHOT", "") + " JavaDoc",
      "-noqualifier", "java.lang",
      "-tag", "return:X",
      // `doclint` is disabled on Circle CI. Need to enable it manually to test our javadoc.
      "-Xdoclint:all"
    ),
    unidocAllSources in(JavaUnidoc, unidoc) := {
      (unidocAllSources in(JavaUnidoc, unidoc)).value
        // ignore any internal Scala code
        .map(_.filterNot(_.getName.contains("$")))
        .map(_.filterNot(_.getCanonicalPath.contains("/internal/")))
        // ignore project `hive` which depends on this project
        .map(_.filterNot(_.getCanonicalPath.contains("/hive/")))
    },
    // Ensure unidoc is run with tests. Must be cleaned before test for unidoc to be generated.
    (test in Test) := ((test in Test) dependsOn unidoc.in(Compile)).value
  )

  /**
   * Release settings
   */
  .settings(
    bintrayOrganization := Some("delta-io"),
    bintrayRepository := "standalone",
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts,
      setNextVersion,
      commitNextVersion
    )
  )

lazy val goldenTables = (project in file("golden-tables")) settings (
  name := "golden-tables",
  commonSettings,
  libraryDependencies ++= Seq(
    // Test Dependencies
    "org.scalatest" %% "scalatest" % "3.0.5" % "test",
    "org.apache.spark" %% "spark-sql" % sparkVersion % "test",
    "io.delta" %% "delta-core" % deltaVersion % "test",
    "commons-io" % "commons-io" % "2.8.0" % "test",
    "org.apache.spark" %% "spark-catalyst" % sparkVersion % "test" classifier "tests",
    "org.apache.spark" %% "spark-core" % sparkVersion % "test" classifier "tests",
    "org.apache.spark" %% "spark-sql" % sparkVersion % "test" classifier "tests"
  )
)
