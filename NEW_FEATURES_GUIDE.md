# Mocap 26.1 — New Feature Guide

> This source update is not a verified release yet. Compile on JDK 25, then test on both Fabric and NeoForge before using it in a production world.

## Saved names and skins

`/mocap scenes random_names <scene_name> <name1,name2,name3,...>`

- Assigns randomized unique names to direct recording elements and saves the chosen names in the scene file.
- Replaying the scene does not reroll names. Run the command again only if you want to randomize again.
- Duplicates are collapsed case-insensitively; names use Minecraft's 1–16 character format.
- Up to 1,000 unique names per command. Excess elements remain unchanged.
- To change one actor later, use `/mocap scenes modify <scene_name> <element_index> player_name set <new_name>`.

`/mocap scenes random_skins <scene_name> <profile1,profile2,...>`

- Assigns and saves unique profile names as skin sources.
- Up to 256 unique profiles per command; excess elements remain unchanged.
- Profile fetches are queued in a small background pool. Wait briefly before starting a large scene. If a lookup is still running, the current playback path may still fetch it synchronously.
- Invalid or unavailable profiles follow the existing fallback behavior.
- Both random assignment commands reject nested scenes, because one nested element may represent multiple actors.

## Scene copy and leftmost anchor

`/mocap scenes copy <source_scene> <destination_scene>` keeps the original and creates an independent scene file. To place/rotate a copied scene from its leftmost direct element, run `/mocap playback modifiers set transformations config scene_center common_leftmost` before `/mocap playback start .<destination_scene>`. For a nested scene element, use `/mocap scenes modify <parent_scene> <element_index> transformations config scene_center common_leftmost`. Nested scenes use their configured center.

## Formation

`/mocap scenes formation <scene_name> <playable> <rows> <columns> <spacing>`

- Adds a centered X/Z grid to an existing scene.
- Rows/columns: 1–100; spacing: 0.5–128 blocks.
- Maximum 1,000 elements per formation command; maximum 5,000 direct scene elements and 5,000 expanded actors per scene tree. Active playback across the server is capped at 10,000 actors.
- Example: `/mocap scenes formation .army soldier_walk 5 8 2.5`

## Playback control

- `/mocap playback freeze <id>`
- `/mocap playback unfreeze <id>`
- `/mocap playback end_freeze <id>` — enable before the end to keep the final actor poses until manually stopped.
- `/mocap playback nametags hide <id>`
- `/mocap playback nametags show <id>`
- `/mocap playback low_lag <id> true|false` — toggles collision-light mode; it disables collision physics and skips fake-player attack-strength bookkeeping (which may change combat/damage timing). It is not a guarantee of smooth playback with thousands of actors.
- `/mocap playback stop <id>` removes the playback and its held actors.

Name-tag synchronization requires clients to use a compatible version of Mocap.

## Equipment presets

Presets are captured from the command executor's equipped armor and main/offhand. Hotbar slots are not saved.

- `/mocap equipment save <group> <preset>`
- `/mocap equipment list <group>`
- `/mocap equipment delete <group> <preset>`
- `/mocap equipment apply <playback_id> <group> <preset>` — all actors in the playback tree.
- `/mocap equipment apply_element <playback_id> <element_index> <group> <preset>` — one direct scene element; a nested-scene element affects all actors in that nested scene.
- `/mocap equipment randomize <playback_id> <group> <preset1,preset2,...>`

Presets are stored in the world's `mocap_files/equipment_presets/<group>/` folder. Applying to a scene playback affects every recording actor in its tree; applying to a standalone recording affects that actor. Equipment overrides persist during playback and are reapplied after recorded equipment actions.

## One-archive build

With JDK 25 and a working network connection, run:

`./gradlew universalJar`

On Windows, use `gradlew.bat universalJar`. The task first builds Fabric and NeoForge outputs and then merges them into a universal archive. The combined archive is not verified until installed and tested on both loaders.
