# Motion Capture Mod 26.1 — Audit and Source Update Report

## Changes in this source pass

### Stability and loop handling
- Playback manager scheduling now uses an accumulator rather than comparing a floating-point timer to an integer tick counter. Playback speed is constrained to 0.05–20.0 and execution is bounded to 20 playback steps per server tick to prevent invalid settings from causing a runaway loop.
- Unexpected runtime exceptions in a playback are logged and that playback is stopped/cleaned up rather than breaking the whole playback tick loop.
- A looping actor is restored if the previous pass ended with death/removal, including fake-player respawn and profile packet refresh.
- Existing cleanup fixes remain: resource stack entries pop in `finally`; recursive scenes are detected before cache use; partial scene starts are stopped; main entity cleanup uses the saved entity; scene parsing is transactional.

### Scene authoring and persistence
- Random unique player names are written to scene element modifiers. They remain fixed on later playback/restart; randomization only occurs when the command is run again. Individual names remain editable through the existing `player_name set` modifier.
- Random skin profile assignment is persisted and queues profile prefetch in a two-thread daemon pool. If prefetch is incomplete, existing playback code may still perform a synchronous lookup; large scenes should wait briefly before playback.
- Formation builder adds a centered X/Z grid with 1,000 additions per command and 5,000 scene elements maximum.
- Scene pattern-add now builds the additions in memory and writes once instead of repeatedly loading/saving the same scene for each match. Added `COMMON_LEFTMOST` as a scene placement anchor while keeping source scenes intact when copied. Expanded nested scenes are capped at 5,000 actors per scene tree, with a 5,000 active-actor server cap to avoid runaway nested expansion.
- Scene save uses a temporary file and atomic replacement where supported. JSON and legacy parsing reject scenes over the element cap.

### Equipment, name tags, and performance controls
- Added persistent grouped equipment presets, storing armor slots and main/offhand only; hotbar is intentionally excluded.
- A preset can be applied to one playback or recursively to all recording actors in a scene tree. Scene randomization chooses a preset independently for each actor, and the override is reapplied after recorded equipment actions.
- Added a versioned packet to hide/show name tags for living actors in a playback tree; state is synchronized to connected Mocap clients and cleared when actors stop.
- Added collision-light/static playback mode using no-physics behavior and skipping fake-player attack-strength bookkeeping while retaining death cleanup. This may change combat/damage timing and does not eliminate network or client rendering costs.
- Packet string encoding now uses UTF-8 byte length and packet reads enforce length bounds.

### Build packaging
- Removed the extra Foojay resolver settings plugin dependency.
- Added a `universalJar` task that merges the Fabric and NeoForge artifacts into one archive with both metadata files and entrypoints. Both underlying loader builds must succeed first.

## Verification performed
- English language JSON parses successfully.
- Source delimiter checks passed for the newly changed Java files.
- The provided Gradle 9.2.1 distribution launches from its extracted path. The `universalJar` task structure was tested with dummy Fabric/NeoForge archives and successfully merged both metadata files; actual project artifacts remain unbuilt.
- `universalJar --offline` fails before compilation because the Fabric Loom plugin is not cached. External DNS resolution to Gradle/Maven hosts is unavailable. The active JDK is Java 21; the supplied Java 25 distribution is Windows-only.

## Not verified
- No Java compilation completed.
- No Fabric or NeoForge JAR was built.
- No Minecraft client/server test, 30-minute loop test, or actor-count performance benchmark was run.
- The original reported long-loop failure has not been reproduced. Scheduler and actor-death loop hardening are source-level mitigations, not proof of a complete fix.
- The universal JAR packaging approach needs installation tests on both loaders.
