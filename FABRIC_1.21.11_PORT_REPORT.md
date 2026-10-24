# Fabric 1.21.11 Feature Port — Verification Report

## Source and target
- Base: official Mocap tag v1.4-alpha-10+mc1.21.11.
- Pinned upstream commit: 192f6f426bd4c02fbe1946a86480fcf6b6405a41.
- Target: Minecraft 1.21.11, Java 21, Fabric; separate from the Minecraft 26.1 core branch.

## Backported feature groups
- Playback safety: bounded scheduler, speed validation, exception cleanup, resource-stack cleanup, recursive-scene detection, transactional scene parsing, actor/scene caps, and loop actor recovery.
- Scene authoring: persisted random names/skins, grid formations, scene copy placement anchor, and bulk scene updates.
- Playback controls: freeze/unfreeze, end-freeze, name-tag visibility sync, and collision-light playback.
- Equipment presets: persistent groups, apply to playback tree or scene element, and randomize per actor.
- Skin prefetch: two daemon workers, 256 pending queue cap, and safe handling of overflow requests.
- Network safety: UTF-8 byte lengths, bounded packet reads, and versioned name-tag visibility packets.

## Verification policy
The GitHub Actions workflow compiles Fabric on Java 21, checks ZIP integrity, verifies Fabric metadata and entrypoint, uploads the artifact, and publishes a prerelease only after successful checks. Build success does not prove live behavior is bug-free. Before production use, test 10s/30s/60s recordings for 30 minutes, confirm persistence across restart, test nested cleanup/presets/name tags, and benchmark 50/100/250/500/1000 actors with server MSPT, memory, traffic, and client FPS.

Paper is a separate plugin port; this Fabric mod cannot be installed as a Paper plugin.
