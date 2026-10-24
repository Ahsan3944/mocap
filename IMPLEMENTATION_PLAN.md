# Motion Capture Mod 26.1 — Implementation and Verification Ledger

This ledger distinguishes source implementation from verified behavior. **No feature in this update is marked runtime-verified**, because this environment could not resolve the build plugins/dependencies and has only Java 21 while Minecraft 26.1 requires Java 25.

## A. Stability, persistence, and playback

| Item | Source status | Verification status / remaining work |
|---|---|---|
| Resource-stack cleanup on recording/scene load | Implemented | Compile and cyclic/missing-file tests required |
| Recursive scene detection before cache lookup; cache only complete scenes | Implemented | Compile and nested/cyclic scene tests required |
| Transactional scene parsing; malformed/legacy files do not partially overwrite data | Implemented | Run fixture-based compatibility tests |
| Atomic scene save with temporary file | Implemented | Filesystem failure test required |
| Scene element cap, expanded nested-scene actor cap, global active actor cap, and bulk pattern-add save-once behavior | Implemented | Test 5,000/5,001 element boundaries and 5,000/5,001 active actor boundaries |
| Playback freeze/unfreeze | Implemented in source | Live server test required |
| End-freeze for complete playback tree | Implemented in source | Test normal, looping, nested, and already-finished playback |
| Long-running loop stability | Scheduler rewritten to use a bounded playback accumulator; playback speed validated to 0.05–20; main actor is restored after death/removal when looping | **Not verified as fixed**; reproduce the reported 10/30/60-second loop and run 30-minute tests. Test death-ending recordings separately |
| Per-playback runtime equipment override | Implemented in source | Test armor, hands, empty slots, and recording actions that change equipment |
| Per-playback living-actor name-tag hide/show | Implemented in source via versioned client packet and renderer mixin | Fabric and NeoForge client/server integration tests required |
| Collision-light playback mode | Implemented in source using no-physics mode | Benchmark; this reduces collision overhead only and does not eliminate network/render costs |
| Packet string/byte-array length validation and UTF-8 byte-length correctness | Implemented | Packet codec round-trip and malformed packet tests required |

## B. Scene authoring

| Feature | Source status | Acceptance criteria / remaining work |
|---|---|---|
| Random unique names | Implemented and persisted in scene file | Test repeat playback, restart, duplicate/case-variant inputs, and individual edits |
| Change one assigned name later | Existing scene `player_name set` modifier retained | Test edit/save/reload workflow |
| Random unique skin profile assignment | Implemented and persisted; profile prefetch runs in a bounded two-thread daemon pool | Test cache hits, unavailable profiles, rate limits, and start latency |
| Formation builder | Implemented; centered X/Z grid, command and scene caps | Test offsets, scene center modes, nested transforms, and all grid sizes |
| Copy scene while retaining source and leftmost anchor option | Existing full scene copy retained; `COMMON_LEFTMOST` scene-center mode added | Runtime test with nested scene/modifier data and rotated placements |
| Nested scenes | Existing feature retained; partial startup cleanup and end-freeze propagation added | Test nested transforms, cleanup, loop, stop, and freeze interactions |
| Armor/equipment presets and groups | Implemented in source; stores armor + main/offhand, not hotbar; can apply to a single playback, all actors in a scene tree, or one direct scene element; randomizes per actor | Test save/load across restart, custom item components, empty slots, and scene playback |

## C. Release and build

- Target: Minecraft 26.1, Java 25, Fabric and NeoForge.
- Removed the Foojay resolver settings plugin so an installed JDK 25 does not require that extra plugin just to configure the build.
- Added root task `universalJar`, which depends on both loader JAR tasks and merges both loader descriptors/entrypoints into one archive. This packaging strategy itself still needs loader installation tests.
- The root `universalJar` packaging task was smoke-tested with dummy loader archives; it merged both metadata files and unique classes successfully. This does not validate the real loader outputs.
- Attempted `universalJar` with the supplied Gradle 9.2.1 distribution in offline mode. Build stopped before compilation because `net.fabricmc.fabric-loom:1.15-SNAPSHOT` was not present in the local Gradle cache. Online dependency resolution also fails in this environment with DNS errors (`UnknownHostException` for Gradle/Maven hosts).
- Java available in the environment is OpenJDK 21. The supplied Java 25 archive is Windows x64, not a Linux JDK.
- **No compiled JAR, Java compile, loader smoke test, Minecraft runtime test, or performance benchmark is claimed.**

## D. Required acceptance tests before release

1. Build `:common:compileJava`, `:fabric:jar`, `:neoforge:jar`, then `universalJar` on a network-enabled machine with JDK 25.
2. Install the universal JAR in Fabric 26.1 and NeoForge 26.1 separately; verify each loader ignores the other loader's metadata and initializes its own entrypoint/mixins.
3. Play an ordinary recording; test stop, stop-all, freeze/unfreeze, end-freeze, name-tag hide/show, low-lag toggle, and equipment preset application.
4. Test scene with direct recordings, nested scenes, delays, transforms, and loops. Include recordings ending in death and actors removed externally.
5. Test 10s/30s/60s recordings looping continuously for 30 minutes; record playback tick count, actor count, CPU, memory, and server MSPT.
6. Test name assignment persistence after server restart and individual name edits; test invalid profile fallback and Mojang rate limits.
7. Test malformed/legacy scenes, atomic save behavior, 5,000 element boundary, and duplicate/random equipment presets.
8. Benchmark 50, 100, 250, 500 and 1,000 actors with clients observing them; document actual render/network limits instead of promising arbitrary actor counts.
