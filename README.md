# Mocap — Motion Capture Mod 26.1

This repository was created to hold the upgraded source for the Motion Capture Mod 26.1 project.

## Current status

The full source tree and a verified build artifact have **not** yet been published here. Do not install a JAR from this repository until a successful GitHub Actions build and artifact validation are available.

## Build requirements

- Java Development Kit 25
- The complete project Gradle wrapper and the source modules (`common`, `fabric`, `neoforge`)
- Network access to the Gradle Plugin Portal and Minecraft mod-loader Maven repositories

Once the complete source tree is present, run `./gradlew --no-daemon :fabric:build :neoforge:build`. A successful task alone is not enough; verify each output JAR and test it in the appropriate Minecraft 26.1 loader.

## Name-randomization requirement

Randomized actor names must be persisted to the scene definition. Replaying a scene must not re-randomize names. A later explicit edit may change one actor's name.

## Release policy

No uncompiled or untested JAR should be treated as a release. The source upload, compilation, archive integrity, and runtime regression tests must all be checked before publishing a release.