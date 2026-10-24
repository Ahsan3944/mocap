# Motion Capture Mod 26.1 — Implementation and Verification Ledger

This ledger distinguishes source implementation from verified behavior. **Java 25 compilation and JAR packaging are verified by GitHub Actions; live Minecraft runtime behavior and performance are not yet verified.** The release workflow builds Fabric and NeoForge separately, checks archive integrity and loader metadata, and publishes only after successful checks.

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
- The current source includes the `universalJar` Gradle task, but this combined archive is experimental and is not the release artifact validated by the workflow.
- GitHub Actions compiles `:fabric:build` and `:neoforge:build`, validates each produced JAR with ZIP integrity checks, checks loader metadata and entrypoint classes, uploads a workflow artifact, and publishes a prerelease only if all these steps pass.
- The released files are loader-specific: use the Fabric JAR on Fabric, or the NeoForge JAR on NeoForge.
- See the current release list: https://github.com/Ahsan3944/mocap/releases
- A successful build is not a live client/server test. The long-loop bug, nested scene interactions, visual name-tag behavior, profile service fallbacks, and the load/performance envelope still require acceptance tests.

## D. Required acceptance tests before production use

1. Install the Fabric JAR in Fabric 26.1 and the NeoForge JAR in NeoForge 26.1 separately; verify startup, metadata, entrypoints, and mixins.
2. On both loaders, test ordinary playback, stop, stop-all, freeze/unfreeze, end-freeze, name-tag hide/show, low-lag toggle, and equipment preset application.
3. Test nested scenes, delays, transforms, loops, and cleanup after failed partial startup. Include recordings ending in death and actors externally removed.
4. Test 10s/30s/60s recordings looping continuously for 30 minutes; record playback tick count, actor count, CPU, memory, and server MSPT.
5. Confirm random names remain fixed across replay and restart, then individually edit one name. Test random skins with cache hits, unavailable profiles, API rate limits, and start latency.
6. Test equipment preset save/load across restart, empty slots, enchanted or custom-component items, both hand slots, nested scenes, and recorded equipment actions.
7. Test malformed and legacy scenes, recursive scene references, atomic save behavior, and boundary cases at 5,000/5,001 elements and expanded actors.
8. Benchmark 50, 100, 250, 500 and 1,000 actors with observing clients. Measure server MSPT, memory, CPU, network traffic, and client FPS. The configured 5,000-actor ceiling is a safety limit, not a guarantee that 5,000 visible actors will be smooth.
