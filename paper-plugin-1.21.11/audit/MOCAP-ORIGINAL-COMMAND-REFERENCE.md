# Original Mocap Command Reference (source-derived)

Source reference: https://github.com/mt1006/mc-mocap-mod/tree/26.1
Target-specific release reference: https://github.com/mt1006/mc-mocap-mod/releases/tag/v1.4-alpha-10%2Bmc1.21.11

## Root

`/mocap recording`, `recordings`, `scenes`, `playback`, `settings`, `misc`, `info`, `help`.

## Recording

- `start` — optional player selector and instant-save name; defaults to command player when possible.
- `stop [id]`
- `discard [id]`
- `save <name> [id]`
- `list [id]`

## Recordings

- `copy <src_name> <dest_name>`
- `rename <old_name> <new_name>`
- `remove <name>`
- `info <name>`
- `list`

## Scenes

- `add <name>`
- `copy <src_name> <dest_name>`
- `rename <old_name> <new_name>`
- `remove <name>`
- `add_to <scene_name> <recording-or-.scene-or-active-context>`
- The base `add_to` supports `*` prefix/suffix pattern matching when no modifiers are provided.
- Optional `wait_on_start` can be supplied before modifier arguments.
- `remove_from <scene_name> <element-index[-expected-name]>`
- `modify <scene_name> <element-index[-expected-name]> <property-and-arguments>`
- `info <scene_name> [<element-index[-expected-name]>`
- `list [scene_name]`

Modifiers are constructed through `CommandUtils.withModifiers(..., isScene=true)` and include:
- `time start_delay <seconds>`
- `time wait_on_start <seconds>`
- `time wait_on_end <seconds>`
- `time wait_for_parent_end <true|false>`
- `time loop <true|false>`
- `transformations rotation <degrees>`
- `transformations mirror <none|x|z|xz>`
- `transformations scale of_player <scale>` or `scale of_scene <scale>`
- `transformations offset <x> <y> <z>`
- transformations config for block rounding, recording center, scene center and center offset
- `player_name inherited|blank|set <name>`
- `player_skin` source choices include default, player, file, and MineSkin where supported
- `player_as_entity disabled|enabled <entity> [NBT]`
- `entity_filter disabled|enabled <filter>`
- `subscene_name <recording-or-.scene>`

Use source Brigadier tree for exact nested argument order and completion behavior.

## Playback

- `start <name> [player/modifier arguments]`
- `stop <id[-expected-name]>`
- `stop_all [including_others|excluding_others]`
- `modifiers set <modifier tree>`
- `modifiers list`
- `modifiers reset`
- `modifiers add_to <scene_name> <recording-or-.scene>`
- `list`

Playback can resolve a saved recording, a dot-prefixed scene, or active recording context. Playback config is created from current typed settings per playback.

## Settings

Root groups: `recording`, `playback`, `advanced`.
A setting literal prints its description/current/default values; adding a typed value updates and persists it.

## Misc

- `sync enable|disable`
- `clear_cache`
- `refresh_suggestions`
- `extensions`

## Data and API

- Recordings use versioned binary `.mcmocap_rec` files; parsers accept supported previous versions.
- Scenes use versioned `.mcmocap_scene` JSON, and the original code includes a legacy scene parser.
- The original project exposes API v1 types and events for recording, playback, modifiers, controller config, and extensions. This API is not automatically available in the Paper plugin.
