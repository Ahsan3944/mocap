# Motion Capture Mod 26.1 — Audit and Verification Report

## Features present in the published source

### Stability, persistence, and playback
- Playback scheduling uses a bounded accumulator; playback speed is restricted to 0.05–20.0, with a cap of 20 playback steps per server tick.
- Runtime exceptions in a playback are logged and that playback is stopped/cleaned up, rather than escaping the playback manager's tick loop.
- Looping playback attempts to restore a main actor that died or was removed, refreshes player-info for fake players, restores equipment overrides, and resets additional entities.
- Recording/scene resource-stack cleanup uses `finally`; recursive scene references are checked before cache lookup; failed partial scene starts are stopped; the main entity cleanup uses the saved entity; scene parsing is transactional.
- Scene save uses a temporary file and atomic replacement when supported. Scene files are capped at 5,000 direct elements; expanded nested scenes and active actor count are capped at 5,000.
- Runtime `freeze`, `unfreeze`, and `end_freeze` commands are present. End-freeze propagates down nested scene trees and intentionally holds the final actor state until manually stopped.
- Collision-light playback now sets the Minecraft 26.1 `Entity.noPhysics` field and toggles fake-player static bookkeeping for both the primary actor and any visual ghost. This reduces collision physics only; it does not remove packet or client-rendering costs.

### Scene authoring
- Random unique names and random skin profile names are assigned to direct recording elements and are saved in scene modifier data. Replaying the scene does not reroll names/skins. The individual `player_name set` modifier remains available for later editing.
- Random name/skin assignment currently rejects scenes whose direct elements include nested scenes, because one parent element may expand to multiple actors. If fewer names are provided than direct elements, the remaining elements are unchanged.
- Skin profile prefetch uses a two-thread daemon pool. If a profile is invalid, unavailable, or rate-limited, successful skin resolution is not guaranteed; test the fallback in a live server before relying on a large scene.
- Formation builder creates a centered X/Z grid with command and scene caps. Scene copy leaves the source intact. `COMMON_LEFTMOST` is available as a placement anchor. Nested scenes preserve their modifiers and receive cleanup/freeze/end-freeze/equipment/name-tag/low-lag propagation.
- Persistent equipment presets store armor and main/offhand slots, intentionally excluding the hotbar. They can be applied to a playback tree, a direct scene element, or randomized independently per actor. Overrides are reapplied after recorded equipment actions.
- Name-tag visibility commands batch and synchronize visibility overrides for living playback entities to connected clients using the matching Mocap protocol.
- Packet string encoding uses UTF-8 byte lengths, and packet input lengths have bounds.

## Build verification

- Target platform is Minecraft 26.1 with Java 25.
- GitHub Actions builds the Fabric and NeoForge projects separately, validates each JAR archive, verifies each loader descriptor and entrypoint class are present, uploads the artifacts, and creates a prerelease only on success.
- Release page: https://github.com/Ahsan3944/mocap/releases
- These checks verify compilation and packaging. They do **not** prove every feature is correct in a live Minecraft client/server.
- The combined `universalJar` Gradle task exists but is not the installable artifact validated by the current release workflow. Use the JAR matching your loader.

## Outstanding tests before calling the mod production-verified

1. Install Fabric JAR on Fabric 26.1 and NeoForge JAR on NeoForge 26.1 separately; confirm startup, mixins, and entrypoints.
2. Test stop/stop-all, freeze/unfreeze, end-freeze, name-tag hide/show, collision-light mode, and equipment overrides on both loaders.
3. Play 10-second, 30-second, and 60-second recordings on loop for 30 minutes; repeat with recordings that kill/remove the actor and with nested scenes.
4. Restart the server and confirm random assigned names/skins remain fixed; edit one actor's name; test invalid/unavailable skin profiles and rate limiting.
5. Save/load equipment presets across restart; verify armor, both hands, empty slots, enchanted/custom-component items, nested scenes, and recorded equipment actions.
6. Exercise malformed/legacy scenes, recursive scene references, partial-load cleanup, atomic-save fallback, and limits around 5,000 elements/actors.
7. Benchmark 50, 100, 250, 500 and 1,000 actors with observing clients, measuring server MSPT, CPU, memory, packet volume, and client FPS. Do not interpret the 5,000 actor safety cap as a performance guarantee.
