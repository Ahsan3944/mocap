# Mocap Paper 1.21.11 — UltraOP port

Paper plugin for Java 21. The upstream Mocap command hierarchy is retained where supported: `recording`, `recordings`, `scenes`, `playing`, `settings`, `info`, and `help`. Paper-only playback conveniences live under `playback`, `customize`, and `formation`.

## Build and install

From the repository root run:

```sh
./gradlew -p paper-plugin-1.21.11 --no-daemon clean test build
```

Install `paper-plugin-1.21.11/build/libs/Mocap-PAPER-1.21.11-1.0.0-uop1.jar` in the Paper server's `plugins/` directory. Do not put this plugin JAR in Fabric's `mods/` folder.

## Recording

- `/mocap recording start [<player|selector>] [<instant-save-name>]`
- `/mocap recording stop [<id>]`
- `/mocap recording save <file-name> [<id>]`
- `/mocap recording discard [<id>]`
- `/mocap recording list [<id>]`

When run by a player, `/mocap recording start` defaults to recording that player. Optional targets accept online player names, comma-separated names, or selectors supported by Paper such as `@a`, `@p`, and `@s`. TAB completion suggests selectors and online player names. The saved Mocap recording header includes the actual recorded player's profile name; playback uses it as the actor display name unless a scene or playback command overrides it.

The Paper recorder captures position, rotation, tick timing, and supported player pose changes. It does **not** currently capture block edits/mining, chat, inventory/equipment changes, damage/combat, entity add/remove/mount, or custom Mocap extension actions. Stopped recordings that are not saved are held in memory only and will be lost on server/plugin restart. The upstream Mod's complete recording engine is not fully ported to Paper.

## Saved recordings

- `/mocap recordings list`
- `/mocap recordings info <name>`
- `/mocap recordings copy <source> <target>`
- `/mocap recordings rename <old-name> <new-name>`
- `/mocap recordings remove <name>`

Files are stored in the first loaded world's `mocap_files/recordings/` directory.

## Scenes — restored upstream-style workflow

- `/mocap scenes add <scene-name>` — create an empty scene.
- `/mocap scenes add_to <scene> <recording|.nested-scene> [<wait-on-start-seconds>] [<player-name>] [<skin-source> <skin-value>]` — add a recording or another scene. Prefix a nested scene with a dot, e.g. `.intro`.
- `/mocap scenes copy <source> <target>`
- `/mocap scenes rename <old-name> <new-name>`
- `/mocap scenes remove <scene-name>`
- `/mocap scenes remove_from <scene> <element-index[-name]>`
- `/mocap scenes modify <scene> <element-index[-name]> <property> <values...>`
- `/mocap scenes info <scene> [<element-index[-name]>]`
- `/mocap scenes list [<scene-name>]`
- `/mocap scenes play <recording|.scene> [loop]`

Scene modifiers currently supported include:
- `player_name inherited`, `player_name blank`, `player_name set <name>`
- `player_skin default`, `player_skin from_player <profile>`
- `time start_delay <seconds>`, `time wait_on_start <seconds>`, `time wait_on_end <seconds>`, `time wait_for_parent_end <true|false>`, `time loop <true|false>`
- `transformations offset <x> <y> <z>`, `transformations rotation <degrees>`, `transformations mirror <none|x|z|xz>`, `transformations scale <of_player|of_scene> <scale>`

For example:
```text
/mocap scenes add battle
/mocap scenes add_to battle alex_run 1.5 Alex skin_from_player Steve
/mocap scenes add_to battle .intro 0
/mocap scenes list battle
/mocap scenes play .battle
```

TAB completion includes known scene names, scene element IDs, recording names, player/profile names, skin sources, time modifiers and transformation modifiers. Profile suggestions include online players, profiles already referenced by scenes, and the common sample names `Steve` and `Alex`.

## Playing and Paper-only controls

Upstream-style playback entry points:
- `/mocap playing start <recording|.scene> [<player-name>] [skin_from_player <profile>]`
- `/mocap playing stop <id>`
- `/mocap playing stop_all`
- `/mocap playing list`

Additional Paper controls:
- `/mocap playback start <recording|.scene> [loop]`
- `/mocap playback list`, `stop <id>`, `stopall`
- `/mocap playback freeze <id>`, `unfreeze <id>`, `end-freeze <id>`
- `/mocap playback low-lag <id> <on|off>`
- `/mocap playback nametags <id> <hide|show>`
- `/mocap customize names <scene> <comma-separated-names>`
- `/mocap customize skins <scene> <comma-separated-profile-names>`
- `/mocap customize name <scene> <element-index> <player-name>`
- `/mocap formation add <scene> <recording> <rows> <columns> <spacing>`
- `/mocap copy scene <source> <target>`
- `/mocap info`, `/mocap help`, `/mocap settings`

The full upstream per-executor playback modifier context and upstream settings registry are not implemented in the Paper port yet; those commands show a limitation message rather than pretending the feature is active.

## Known compatibility limits

Paper Mannequins are not fake `ServerPlayer` entities. The plugin visualizes supported movement/rotation/pose, basic item IDs, scene transformations, supported start delays, name visibility, loops and playback controls. Block edits/mining, chat, entity add/remove/mount, hits/swings, arbitrary item components/NBT and custom extension actions aren't executed. Custom PNG/MineSkin skin injection, embedded signed skin properties, every scene-center mode and player-as-entity transforms are not fully ported. `player_skin from_player` uses Paper's resolvable profile API and depends on Paper/Mojang services. Low-lag is an immovability toggle, not a measured performance guarantee.

## Verification

CI runs Java 21 compilation, selected parser/scene JUnit tests, JAR integrity/descriptor/entrypoint checks, source packaging and SHA-256 manifests. This does not replace live tests on a backup Paper 1.21.11 world. Runtime checks for plugin startup, recordings and scenes, profile lookup, delayed/nested playback, 10/30/60-second recordings looping for 30 minutes, cleanup and multi-actor performance remain required. The 500 mannequin limit is a safety cap, not a performance guarantee.
