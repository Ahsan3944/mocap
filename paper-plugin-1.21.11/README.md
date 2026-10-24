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

The Paper command exposes compatible roots/aliases, but **not 100% command parity**. A Paper-side settings index now lists all 36 original fields in the `recording`, `playback`, and `advanced` groups; only `playback_speed`, `can_push_entities`, and `invulnerable_playback` currently have live Paper behavior and persistence. Other settings are shown as `UPSTREAM-ONLY` and cannot be set as if they worked. The original `misc` sync/cache refresh/extension commands and upstream playback modifier context are not fully implemented. These gaps are documented in `PORT_REPORT.md` and the feature matrix. The aliases `playing`, `scene`, `customize`, `formation`, and `copy` are conveniences, not replacements for canonical upstream routes.

## Paper recording (partial)

- `/mocap recording start [<player|selector>] [<instant-save-name>]`
- `/mocap recording stop [<id>]`
- `/mocap recording save <name> [<id>]`
- `/mocap recording discard [<id>]`
- `/mocap recording list [<id>]`

This Paper recorder captures position, rotation, tick timing and supported player pose changes, plus the actual recorded profile name in a compatible Mocap v3 header. It does not record block interactions, chat, inventory/equipment changes as timed actions, damage/combat, entity add/remove/mount, or custom extension actions. Unnamed stopped sessions remain in memory and are lost at server/plugin restart.

## Saved recordings

- `/mocap recordings list`
- `/mocap recordings info <name>`
- `/mocap recordings copy <source> <target>`
- `/mocap recordings rename <old-name> <new-name>`
- `/mocap recordings remove <name>`

## Scene authoring

- `/mocap scenes add <scene-name>`
- `/mocap scenes add_to <scene> <recording|.nested-scene> [<wait-on-start-seconds>] [<player-name>] [<skin-source> <skin-value>]`
- `/mocap scenes copy <source> <target>`
- `/mocap scenes rename <old-name> <new-name>`
- `/mocap scenes remove <scene-name>`
- `/mocap scenes remove_from <scene> <element-index[-name]>`
- `/mocap scenes modify <scene> <element-index[-name]> <property> <values...>`
- `/mocap scenes info <scene> [<element-index[-name]>]`
- `/mocap scenes list [<scene-name>]`
- `/mocap scenes play <recording|.scene> [loop]` (Paper-specific convenience)

The upstream syntax supports a full modifier tree and has further optional `add_to` arguments. This Paper shortcut does not yet reproduce the whole upstream argument surface. Supported scene modifier subset includes player name, name-based profile, selected time fields and selected transformations (offset, rotation, mirror, scene scale). Wait-on-end/wait-for-parent-end scheduling and all transform-center modes are not verified as parity.

## Paper-only playback conveniences

- `/mocap playback start <recording|.scene> [loop]`
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
- `/mocap formation add <scene-name> <recording-name> <rows:1-1000> <columns:1-1000> <spacing-blocks:0.5-128>`
- `/mocap settings list [<recording|playback|advanced>]`
- `/mocap settings <group> <setting>` to inspect default/current value and Paper support status
- `/mocap settings <group> <setting> <value>` to persist one of the supported Paper settings

Name/skin libraries are stored under `plugins/MocapPaper/profile-libraries/names/<group>/<subgroup>.yml` and `.../skins/<group>/<subgroup>.yml`. Each subgroup supports up to 3,000 unique official Mojang profile names. IDs are stable and subgroup-qualified when listing a whole group; removing an entry does not renumber others. `save` replaces one subgroup, `add` appends new unique values, and `remove` accepts an entry ID or exact profile name. For large lists, place a UTF-8 text file under `plugins/MocapPaper/profile-libraries/imports/`; the file may be comma-separated or one profile per line, with a 512 KiB size limit.

After choosing a library group in `/mocap customize names|skins <scene> <group>`, `auto` is the first TAB suggestion. It randomly assigns from all saved subgroups in that group; a labeled `subgroup:<name>` scope can restrict the pool. Direct comma-separated official names remain available. Random selection is performed independently for each scene element, so short pools can repeat across large formations, and the resulting assignments are stored in scene JSON rather than re-rolled during playback. Official name and skin list entries are validated as 1–16-character Mojang profile names.

Equipment autocomplete is group-scoped: after the group is selected, only presets in that group are suggested. `apply-element` and `remove-element` suggest one-based actor indexes from the selected active playback. `remove`/ `remove-element` clear only the four armor slots, leaving hands untouched. `mix` takes at least two selected preset names from one group, randomly assigns one whole armor set per actor, and preserves the current held-item state; `randomize` chooses from every armor set in a group. Full `apply` still applies a complete equipment preset.

Formation meanings: `rows` run behind the source character and `columns` grow to the character's right, relative to the saved recording's starting yaw. The original source actor stays at zero offset at row 1/column 1 instead of being centered. Rows × columns cannot exceed 1,000 actor additions per call and a scene cannot exceed 5,000 elements.

Name-tag commands retain the ID-first syntax `/mocap playback nametags <playback-id> <hide|show>`; clearer action-first shortcut: `/mocap playback nametags <hide|show> <playback-id>`. A playback ID is the first number shown by `/mocap playback list`.

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
