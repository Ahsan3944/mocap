# Mocap Paper 1.21.11 — UltraOP port

Target: Paper 1.21.11, Java 21. This is a Paper plugin, not a Fabric/NeoForge mod. The Paper implementation uses Paper Mannequin actors; it does not yet provide full original Mocap action parity.

## Build and install

From the repository root:

```sh
./gradlew -p paper-plugin-1.21.11 --no-daemon clean test build
```

Install the generated JAR from `paper-plugin-1.21.11/build/libs/` into Paper's `plugins/` directory only. Do not install this plugin JAR in Fabric's `mods/` folder.

## Command roots — upstream contract vs Paper implementation

The original source root is `recording`, `recordings`, `scenes`, `playback`, `settings`, `misc`, `info`, and `help`. Source command reference and feature matrix are in `audit/`.

The Paper command has one canonical entry point for each command family. Use `scenes` (not `scene`), `playback` (not `playing`), `names` (not `name`) and `skins` (not `skin`). `recording` and `recordings` are intentionally separate: the former manages live/stopped capture sessions; the latter manages saved `.mcmocap_rec` files. Scene copy/move is inside `/mocap scenes modify`; there is no standalone root `/mocap copy`. This cleanup does not imply 100% upstream parity. Only `playback_speed`, `can_push_entities`, and `invulnerable_playback` currently have live Paper settings behavior; other settings are marked `UPSTREAM-ONLY`. The original `misc` sync/cache refresh/extension commands and upstream playback modifier context are not fully implemented.

## Paper recording (partial)

- `/mocap recording start [<player|selector>] [<instant-save-name>] [<custom-session-id>]`
- `/mocap recording stop [<id>]`
- `/mocap recording save <name> [<id>]`
- `/mocap recording discard [<id>]`
- `/mocap recording list [<id>]`

This Paper recorder captures position, rotation, tick timing and supported player pose changes, plus the actual recorded profile name in a compatible Mocap v3 header. It does not record block interactions, chat, inventory/equipment changes as timed actions, damage/combat, entity add/remove/mount, or custom extension actions. Unnamed stopped sessions remain in memory and are lost at server/plugin restart. An optional positive numeric `custom-session-id` can replace the generated capture-session ID, provided it is not already active/stopped; it is a temporary session ID, not the saved recording filename. For the current player with a custom ID, use `/mocap recording start - - 52`. Saved recordings are named with the `save` argument (or the instant-save name).

## Saved recordings

- `/mocap recordings list`
- `/mocap recordings info <name>`
- `/mocap recordings copy <source> <target>`
- `/mocap recordings rename <old-name> <new-name>`
- `/mocap recordings remove <name>`

## Scene authoring

- `/mocap scenes add <scene-name>`
- `/mocap scenes add_to <scene> <recording|.nested-scene> [<wait-on-start-seconds>] [<player-name>] [<skin-source> <skin-value>]`
- `/mocap scenes modify move <scene> [<element-index[-name]>]` (move whole scene/team, or a single top-level actor/subscene)
- `/mocap scenes modify copy <source-scene> <new-scene-name>` (copy without overwriting; if you selected an anchor, the new copy is placed there)
- `/mocap scenes rename <old-name> <new-name>`
- `/mocap scenes remove <scene-name>`
- `/mocap scenes remove_from <scene> <element-index[-name]>`
- `/mocap scenes modify <scene> <element-index[-name]> <property> <values...>` (per-element properties)
- `/mocap scenes info <scene> [<element-index[-name]>]`
- `/mocap scenes list [<scene-name>]`
- `/mocap scenes play <recording|.scene> [loop]` (Paper-specific convenience)

The upstream syntax supports a full modifier tree and has further optional `add_to` arguments. This Paper shortcut does not yet reproduce the whole upstream argument surface. Scene-wide or single-element Move/Copy is a Paper convenience inside the same `modify` command group. To move, first use `/mocap formation tool` and right-click the destination block. Supported scene modifier subset includes player name, name-based profile, selected time fields and selected transformations (offset, rotation, mirror, scene scale). Wait-on-end/wait-for-parent-end scheduling and all transform-center modes are not verified as parity.

## Paper-only playback conveniences

- `/mocap playback start <recording|.scene> [<player_name>] [skin_from_player <profile>] [loop]` (one canonical playback root)
- `/mocap playback list`, `stop <id>`, `stopall`
- `/mocap playback freeze <id>`, `unfreeze <id>`, `end-freeze <id>`
- `/mocap playback low-lag <id> <on|off>`
- `/mocap playback nametags <id> <hide|show>`
- `/mocap names|skins groups`, `subgroups <group>`, `list <group> [<subgroup>]`
- `/mocap names|skins save <group> <subgroup> <comma,separated,official,names>` (replace that subgroup)
- `/mocap names|skins add <group> <subgroup> <comma,separated,official,names>` (append new unique names)
- `/mocap names|skins import <group> <subgroup> <file.txt>` (replace from UTF-8 file)
- `/mocap names|skins import-add <group> <subgroup> <file.txt>` (append from UTF-8 file)
- `/mocap names|skins remove <group> <subgroup> <entry-id-or-exact-name>`
- `/mocap names|skins delete <group> <subgroup>`
- `/mocap customize names <scene> <group> auto`
- `/mocap customize skins <scene> <group> auto`
- `/mocap customize names <scene> <group> <subgroup:subgroup-name> auto`
- `/mocap customize skins <scene> <group> <subgroup:subgroup-name> auto`
- `/mocap customize name <scene> <element-index[-name]> <player-name>`
- `/mocap customize equipment save <group-name> <preset-name>`
- `/mocap customize equipment list [<group-name>]`
- `/mocap customize equipment apply <playback-id> <group-name> <preset-name>`
- `/mocap customize equipment apply-element <playback-id> <actor-index> <group-name> <preset-name>`
- `/mocap customize equipment remove <playback-id>` (remove armor from every actor)
- `/mocap customize equipment remove-element <playback-id> <actor-index>`
- `/mocap customize equipment mix <playback-id> <group-name> <preset-1> <preset-2> [<preset-3> ...]`
- `/mocap customize equipment randomize <playback-id> <group-name>` (choose randomly from every set in the group)
- `/mocap formation create <group-name> <source-recording> <rows:1-1000> <columns:1-1000> <column-gap:0-128> <row-gap:0-128>` (creates a distinct saved Formation Group and its backing scene at the selected anchor)
- `/mocap formation list`, `/mocap formation info <group>`, `/mocap formation play <group> [loop]`, `/mocap formation delete <group>`
- `/mocap formation add <scene-name> <recording-name> <rows:1-1000> <columns:1-1000> <column-gap:0-128> <row-gap:0-128>` (legacy grid helper, adds to an existing scene)
- Legacy equal-gap syntax remains supported: `/mocap formation add <scene> <recording> <rows> <columns> <spacing>` (an optional final `row-gap` makes the two directions independent)
- `/mocap formation tool` (gives the tagged Blaze Rod placement tool)
- `/mocap paste <recording-name|.scene-name> [loop]` (after right-clicking a target block with the placement tool)
- `/mocap paste <recording-name|.scene-name> <x> <y> <z> [loop]` (explicit coordinates in the current world)

- `/mocap settings list [<recording|playback|advanced>]`
- `/mocap settings <group> <setting>` to inspect default/current value and Paper support status
- `/mocap settings <group> <setting> <value>` to persist one of the supported Paper settings

A **group** is the broad category (e.g. `Season1`); a **subgroup** is a named list inside it (e.g. `Heroes` or `Villains`). This avoids mixing separate rosters into one giant list and lets `auto` randomize either an entire category or one subgroup. Names and skins are intentionally separate libraries: `names` sets the on-screen player name, while `skins` chooses a profile to resolve for the mannequin skin. Libraries are stored under `plugins/MocapPaper/profile-libraries/names/<group>/<subgroup>.yml` and `.../skins/<group>/<subgroup>.yml`. Each subgroup supports up to 3,000 unique official Mojang profile names. IDs are stable and subgroup-qualified when listing a whole group; removing an entry does not renumber others. `save` replaces one subgroup, `add` appends new unique values, and `remove` accepts an entry ID or exact profile name. For large lists, place a UTF-8 text file under `plugins/MocapPaper/profile-libraries/imports/`; the file may be comma-separated or one profile per line, with a 512 KiB size limit.

Use `/mocap names list <group> [<subgroup>] [<page>|page:<page>]` or the equivalent `skins` command to inspect lists 60 entries at a time. After choosing a library group in `/mocap customize names|skins <scene> <group>`, `auto` is the first TAB suggestion. It randomly assigns from all saved subgroups in that group; a labeled `subgroup:<name>` scope can restrict the pool. Direct comma-separated official names remain available. Random selection is performed independently for each scene element, so short pools can repeat across large formations, and the resulting assignments are stored in scene JSON rather than re-rolled during playback. Official name and skin list entries are validated as 1–16-character Mojang profile names.

Equipment autocomplete is group-scoped: after the group is selected, only presets in that group are suggested. `apply-element` and `remove-element` suggest one-based actor indexes from the selected active playback. `remove`/ `remove-element` clear only the four armor slots, leaving hands untouched. `mix` takes at least two selected preset names from one group, randomly assigns one whole armor set per actor, and preserves the current held-item state; `randomize` chooses from every armor set in a group. Full `apply` still applies a complete equipment preset.

**Named Formation Group workflow:** run `/mocap formation tool`, right-click the target block with the specifically tagged Blaze Rod, and then run `/mocap formation create BattleGroup warrior 3 5 1.5 4`. This creates both a separate metadata record under `world/mocap_files/formations/` and a backing `.mcmocap_scene` of the same name; these are two different layers, not duplicate folders. The selected top-center is saved as the group's anchor. The source must be a saved recording of the player/animation to clone. A group is capped at 500 actors so it fits the current Paper playback limit. The group can later be started at its saved position with `/mocap formation play BattleGroup`; the generated scene can be customized with the saved name/skin libraries.

Formation meanings: `rows` run behind the source character and `columns` grow to the character's right, relative to the saved recording's starting yaw. The original source actor stays at row 1/column 1 instead of being centered. `column-gap` controls side-to-side spacing independently from `row-gap`, which controls front-to-back spacing; both accept 0–128 blocks. Legacy `formation add` remains available for adding a grid to an existing scene.

Placement/paste: run `/mocap formation tool`, then right-click a block with the specifically tagged Blaze Rod. That stores the center of the clicked block's top face as the anchor for the first actor. Run `/mocap paste warrior` or `/mocap paste .battle`; the existing recording or full scene starts at that anchor, and all remaining actors keep their relative scene/formation offsets. To skip the rod, use `/mocap paste warrior 100 65 -20`. Coordinate paste uses the executing player's current world. The anchor is per-player and lasts until the player changes it or disconnects. This starts a playback instance; it does not move a currently running playback or permanently freeze actors as static entities.

Name-tag commands retain the ID-first syntax `/mocap playback nametags <playback-id> <hide|show>`; clearer action-first shortcut: `/mocap playback nametags <hide|show> <playback-id>`. A playback ID is the first number shown by `/mocap playback list`.

Whole-scene Move: use the tagged rod to select a new target, then `/mocap scenes modify move BattleGroup`. Move one actor/subscene with `/mocap scenes modify move BattleGroup 003-warrior`. Copy a scene into a new scene with `/mocap scenes modify copy BattleGroup BattleGroupCopy`; if you selected a target, the copy is moved there while the source remains unchanged. Normal recordings/scenes can also be started at a target with `/mocap paste <recording|.scene>`; that starts a new playback rather than teleporting a playback which is already running.

These additions are not proof that the corresponding Fabric feature behaves identically. Paper runtime and client tests are still required.

## Original-versus-Our audit artifacts

- [Original-versus-Our Feature Matrix](audit/Original-versus-Our-Feature-Matrix.md)
- [Feature Specification and Verification Plan](audit/ULTRAOP-MOCAP-FEATURE-SPECIFICATION.md)
- [Original Mocap Command Reference](audit/MOCAP-ORIGINAL-COMMAND-REFERENCE.md)
- [Technical/Parity Report](PORT_REPORT.md)
- Official source baseline: https://github.com/mt1006/mc-mocap-mod/tree/26.1
- Official target-specific release: https://github.com/mt1006/mc-mocap-mod/releases/tag/v1.4-alpha-10%2Bmc1.21.11

## Verification status

GitHub Actions runs Java 21 clean build/tests, JAR integrity, plugin descriptor/entrypoint checks, source bundle integrity and SHA-256 verification. Use the latest run for the exact source commit before distributing a JAR. A green CI run is **not** a runtime verification pass. A real Paper 1.21.11 server still must be tested for startup, command suggestions, recording/scene playback, nested delays/cleanup, random values staying stable on replay, skins, enchanted equipment, freeze/unfreeze/end-freeze and long-running loops. The reported “short recording stops after minutes” issue is **not considered fixed** without reproducing it and passing the 30-minute loop test.

Paper API cannot reproduce every original fake-player/action behavior. Any unsupported action/settings/API remains explicitly partial or unsupported; this port is not advertised as 100% equivalent.
