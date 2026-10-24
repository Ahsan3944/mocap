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

The Paper command exposes a number of compatible roots/aliases, but **not 100% command parity**. Original `settings` groups (typed persisted `recording`, `playback`, `advanced`) are not ported as a registry; `misc` sync/cache refresh/extension commands are not fully implemented; and upstream playback modifier context is not implemented. These gaps are documented explicitly in `PORT_REPORT.md` and the feature matrix. The aliases `playing`, `scene`, `customize`, `formation`, and `copy` are not replacements for canonical upstream routes.

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
- `/mocap customize names <scene> <comma-separated-names>`
- `/mocap customize skins <scene> <comma-separated-profile-names>`
- `/mocap customize name <scene> <element-index> <player-name>`
- `/mocap customize equipment ...`
- `/mocap formation add <scene> <recording> <rows> <columns> <spacing>`

These additions are not proof that the corresponding Fabric feature behaves identically. Random-name/skin persistence and nested-scene behavior need dedicated regression tests.

## Original-versus-Our audit artifacts

- [Original-versus-Our Feature Matrix](audit/Original-versus-Our-Feature-Matrix.md)
- [Feature Specification and Verification Plan](audit/ULTRAOP-MOCAP-FEATURE-SPECIFICATION.md)
- [Original Mocap Command Reference](audit/MOCAP-ORIGINAL-COMMAND-REFERENCE.md)
- [Technical/Parity Report](PORT_REPORT.md)
- Official source baseline: https://github.com/mt1006/mc-mocap-mod/tree/26.1
- Official target-specific release: https://github.com/mt1006/mc-mocap-mod/releases/tag/v1.4-alpha-10%2Bmc1.21.11

## Verification status

The audited Paper commit's GitHub Actions run passed Java 21 build, selected parser/scene JUnit tests, JAR integrity, plugin descriptor and entrypoint checks. This is **not** a runtime verification pass. A real Paper 1.21.11 server still must be tested for startup, command suggestions, recording/scene playback, nested delays/cleanup, random values staying stable on replay, skins, enchanted equipment, freeze/unfreeze/end-freeze and long-running loops. The reported “short recording stops after minutes” issue is **not considered fixed** without reproducing it and passing the 30-minute loop test.

Paper API cannot reproduce every original fake-player/action behavior. Any unsupported action/settings/API remains explicitly partial or unsupported; this port is not advertised as 100% equivalent.
