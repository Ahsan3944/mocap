# Motion Capture Mod 26.1

This repository is the working source for the Motion Capture Mod 26.1 upgrade project.

## Build

Requires Java 25. Run `./gradlew --no-daemon :fabric:build :neoforge:build`.

GitHub Actions compiles the Fabric and NeoForge variants and uploads their JARs as a workflow artifact. Build results are not considered verified until the workflow completes successfully.

## Important

- Randomized actor names are saved into the scene modifiers. Running playback again should reuse those saved names; randomization is explicit and does not rerun on playback.
- This project is in development; see `IMPLEMENTATION_PLAN.md`, `AUDIT_FIXES_REPORT.md`, and `NEW_FEATURES_GUIDE.md` for scope and validation notes.
