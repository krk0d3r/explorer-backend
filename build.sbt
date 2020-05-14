import Dependencies._

Global / cancelable := true // Allow cancellation of forked task without killing SBT

lazy val root = (project in file("."))
  .settings(
    organization := "org.alephium",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.2",
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "utf-8",
      "-explaintypes",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xlint:adapted-args",
      "-Xlint:constant",
      "-Xlint:delayedinit-select",
      "-Xlint:doc-detached",
      "-Xlint:inaccessible",
      "-Xlint:infer-any",
      "-Xlint:missing-interpolator",
      "-Xlint:nullary-override",
      "-Xlint:nullary-unit",
      "-Xlint:option-implicit",
      "-Xlint:package-object-classes",
      "-Xlint:poly-implicit-overload",
      "-Xlint:private-shadow",
      "-Xlint:stars-align",
      "-Xlint:type-parameter-shadow",
      "-Xlint:nonlocal-return",
      "-Ywarn-dead-code",
      "-Ywarn-extra-implicit",
      "-Ywarn-numeric-widen",
      "-Ywarn-unused:implicits",
      "-Ywarn-unused:imports",
      "-Ywarn-unused:locals",
      "-Ywarn-unused:params",
      "-Ywarn-unused:patvars",
      "-Ywarn-unused:privates",
      "-Ywarn-value-discard"
    ),
    wartremoverErrors in (Compile, compile) := Warts.allBut(wartsCompileExcludes: _*),
    wartremoverErrors in (Compile, compile) := Warts.allBut(wartsCompileExcludes: _*),
    fork := true,
    libraryDependencies ++= Seq(
      alephiumUtil % "test" classifier "tests",
      alephiumRpc,
      tapirCore,
      tapirCirce,
      tapirAkka,
      circeCore,
      circeGeneric,
      scalaLogging,
      logback,
      akkaTest,
      akkaHttptest,
      akkaStreamTest,
      scalatest,
      scalatestplus,
      scalacheck
    )
  )

val wartsCompileExcludes = Seq(
  Wart.Any,
  Wart.Nothing
)
