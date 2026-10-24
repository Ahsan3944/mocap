# Original-versus-Our Feature Matrix

Audit snapshot date: 2026-10-24.

## Sources checked

- Official source repository: https://github.com/mt1006/mc-mocap-mod
- Official source branch: `26.1`
- Official latest stable GitHub Release observed: `Mocap 1.3.10` (2025-12-11); includes Fabric 1.21.11 JAR.
- Official later version-specific release observed: `Mocap 1.4-alpha-10 (1.21.11)` (2025-12-31), non-prerelease on GitHub, with Fabric and NeoForge JAR assets.
- UltraOP repo: https://github.com/Ahsan3944/mocap
- Branches inspected: `main/core-26.1`, `fabric-1.21.11`, `paper-1.21.11`.
- Paper branch audited at commit `995f5d4c6edb115e079122adbd644655ea8d0b24`.

Release semantics note: GitHub's `/releases/latest` returned `Mocap-1.3.10` while the release list contains a newer published target-specific `v1.4-alpha-10+mc1.21.11` tag. Do not call one universally “the latest” without stating whether referring to GitHub's latest-marked release or latest target-specific release.

## Original root command tree (source-derived)

```text
/mocap
├── recording
├── recordings
├── scenes
├── playback
├── settings
│   ├── recording
│   ├── playback
│   └── advanced
├── misc
│   ├── sync enable|disable
│   ├── clear_cache
│   ├── refresh_suggestions
│   └── extensions
├── info
└── help
```

The root command requires Minecraft command permission level `LEVEL_GAMEMASTERS`; group commands reuse the Brigadier tree. Tab completion is data-backed by current recordings/scenes, active recordings and profile/skin-file caches.

### Original recording commands

```text
/mocap recording start [<player-selector>] [<instant_save_name>]
/mocap recording stop [<recording-id>]
/mocap recording discard [<recording-id>]
/mocap recording save <name> [<recording-id>]
/mocap recording list [<recording-id>]
```

Source behavior includes defaulting to the command player when available, recording one or multiple players, a target suffix for multiple instant saves, and per-recording source/config.

### Original saved-recording commands

```text
/mocap recordings copy <source> <target>
/mocap recordings rename <old-name> <new-name>
/mocap recordings remove <name>
/mocap recordings info <name>
/mocap recordings list
```

### Original scene commands

```text
/mocap scenes add <name>
/mocap scenes copy <source> <target>
/mocap scenes rename <old-name> <new-name>
/mocap scenes remove <name>
/mocap scenes add_to <scene> <recording|.scene|active-recording-pattern> [<wait_on_start>] [player/modifier arguments]
/mocap scenes remove_from <scene> <element-index[-expected-name]>
/mocap scenes modify <scene> <element-index[-expected-name]> <property and property-specific arguments>
/mocap scenes info <scene> [<element-index[-expected-name]>]
/mocap scenes list [<scene>]
```

Important source details:
- A dot-prefixed name references a scene; a leading `-` can reference active recording contexts.
- `add_to` supports a `*` name pattern in default-modifier mode.
- Modifier argument tree includes time settings, transformations (rotation/mirror/scale/offset/config), player name handling, player skin source, player-as-entity options, entity filter and nested-scene name.
- `remove_from`, `modify`, and element info accept an index plus optional expected name to avoid editing an element after its order/name changed.
- Scene JSON supports legacy parsing; scene saving preserves modifier data and updates completion caches.

### Original playback commands

```text
/mocap playback start <recording|.scene|active-context> [player and modifier arguments]
/mocap playback stop <id[-expected-name]>
/mocap playback stop_all
/mocap playback stop_all including_others
/mocap playback stop_all excluding_others
/mocap playback modifiers set <modifier arguments>
/mocap playback modifiers list
/mocap playback modifiers reset
/mocap playback modifiers add_to <scene> <recording|.scene>
/mocap playback list
```

Playback defaults are loaded from settings per playback. Active recording playback, owner-scoped stop-all, owner command context/synchronization, modifiers, entity/block/chat actions, cleanup and source options belong to the original implementation; not all are possible through Paper's public API.

### Original settings

Original source declares three setting groups:

- `recording`: `track_entities`, `prevent_tracking_played_entities`, `entity_tracking_distance`, `on_death`, `on_change_dimension`, `start_instantly`, `assign_dimension`, `assign_profile`, `chat_recording`.
- `playback`: `playback_speed`, `play_entities`, `can_push_entities`, `entities_after_playback`, `block_actions_playback`, `block_initialization`, `block_allow_scaled`, `drop_from_blocks`, `start_as_recorded`, `chat_playback`, `invulnerable_playback`, `dimension_source`, `player_name_handling`, `hit_range`.
- `advanced`: `fluent_movements`, `max_float_pos_value`, `allow_mineskin_requests`, `prevent_saving_entities`, `use_creative_game_mode`, `allow_ghosts`, `experimental_release_warning`, `pretty_scene_files`, `show_tips`, `quick_discard`, `required_extensions`, `nbt_recording_mode`, `use_authlib_services`.

Two precise rotation settings are commented-out source, not active settings. Upstream settings are typed and persisted by the setting field registry; simply showing a Paper message is not parity.

## Feature matrix

Status definitions: “Partial” means only a subset is implemented in the relevant branch; “Not implemented” means code/source indicates no matching implementation in the audited target; runtime testing is tracked independently.

| Feature | Original Mocap | Fabric 1.21.11 branch | Paper 1.21.11 branch | Test status |
|---|---|---|---|---|
| Position/rotation/timing recording | Full | Based on upstream recording engine plus backports | Partial: position/rotation/supported poses only | Fabric build passed; Paper encoder/parser unit tests passed; Paper runtime not tested |
| Sprint/jump/swim/sneak/hand swing/item use | Original recording actions | Upstream engine source retained in port | Not fully captured/replayed | Paper runtime not tested |
| Armor/hand equipment + arbitrary item components | Original actions + NBT mode | Upstream engine source retained in port | Partial: Paper presets support Bukkit item metadata; recorded action decoder currently reduces item identity | No end-to-end parity test |
| Block place/break/interact | Original recording and playback settings/actions | Upstream engine source retained in port | Not recorded/executed | Not applicable to mannequin-only subset |
| Nearby entities/riding/entity filters | Original tracking/playback config | Upstream core available in target source; API/backport build only | Not fully represented by mannequins | No runtime parity test |
| Recording lifecycle and multiple targets | Original | Original command path from upstream | Paper-native start/stop/save/discard/list, selector handling; IDs/lifecycle differ | Paper unit/integration runtime not tested |
| Saved recording copy/rename/remove/info/list | Original | Original | Implemented | Scene-related tests do not cover all recording file commands |
| Scene add/copy/rename/remove/add_to/remove_from/modify/info/list | Original; legacy and versioned scene formats | Original scene engine with backport changes | Partial Paper JSON scene model; command names partly aliased; modifier schema is not upstream-identical | Selected scene-store tests passed; runtime not tested |
| Nested scenes / recursion protection / cleanup | Original | Backport recursion and cleanup changes | Nested expansion/recursion guard implemented; full composition/timing semantics are partial | No dedicated Paper nested runtime test |
| Time modifiers | Original: start delay, waits, parent/loop timing | Upstream modifier model plus backport | start delay/wait-on-start is scheduled; wait-on-end/parent and per-element loop semantics are not fully implemented | Unit test covers serialization, not live timing |
| Position offset/rotation/mirror/scale/scene-center configs | Original modifier engine | Upstream modifier engine | Partial: offset/rotation/mirror/scene scale; player scale, exact bounding/center modes not full parity | No runtime transform suite |
| Player names/skin sources/skin lists/MineSkin/custom textures | Original | Original + profile prefetch safety backport | Partial: display name and name-based resolvable profile; custom PNG/MineSkin/embedded texture signing not fully ported | Paper profile lookup not runtime tested |
| Settings groups + persistent typed values | Full upstream registry | Original settings source included | Paper registry now lists 36 original field names across recording/playback/advanced; playback_speed, can_push_entities, invulnerable_playback are persistent and wired; the other fields are visible but explicitly unsupported | Settings JUnit covers group names, persistence and rejection of unsupported fields; runtime not tested |
| Misc sync/cache refresh/extensions | Original | Original source APIs/commands unless port alterations | Not implemented as upstream behavior | Not tested |
| Playback modifiers context | Original per-executor context/list/reset/set/add_to | Upstream command/context source | Partial help/fallback; dynamic context not implemented | Not passed for parity |
| Random Names / Random Skins + profile libraries | UltraOP add-on | Implemented/persisted for supported elements | Separate names/skins libraries with group/subgroup, stable IDs, bulk save/add/remove, text import up to 3,000 official profiles per subgroup; `customize names|skins <scene> <group> auto` selects from that group and randomizes independently per actor | JUnit covers library persistence/IDs/import and recorded-profile discovery; live Mojang skin resolution and no-reroll visuals still need runtime tests |
| Formation Builder + click/coordinate paste | UltraOP add-on | Implemented source path | Source actor anchors at row 1/column 1; right/behind layout follows recorded yaw; column-gap and row-gap are independently configurable (old equal-gap syntax remains); tagged Blaze Rod click sets top-center anchor; paste can start a complete recording or scene at the anchor or explicit x/y/z coordinates | JUnit verifies anchor and independent gap geometry; live Blaze Rod, click-paste, coordinates and actor visuals still need runtime test |
| Scene placement/copy anchor | UltraOP add-on | Implemented source path | Copy exists; placement-anchor controls are partial/not equivalent | No runtime test |
| Equipment preset groups / per actor apply / armor removal / mix | UltraOP add-on | Equipment preset manager backport | Group-scoped autocomplete, playback-scoped one-based actor indexes, armor-only remove/remove-element, mix selected preset sets randomly per actor, randomize across the full group while preserving held items | Preset listing/path tests and build verification; actual Mannequin armor display, enchanted/component fidelity, persistent overrides and in-game TAB still require runtime test |
| Name-tag hide/show | UltraOP add-on | Networked batch visibility | Mannequin custom-name visibility | No live client test |
| Freeze/unfreeze/end-freeze | UltraOP add-on | Playback state controls | Session flags/state controls | No runtime test |
| Low-lag playback | UltraOP add-on | Reduces collision/fake-player bookkeeping costs | Immovability toggle; not equivalent to the Fabric optimization | No benchmark test |
| Long-running loop recovery | Bug fix requirement | Reset/cleanup code backport exists | Finite-duration session loop exists; original bug not root-caused or 30-minute tested | Not passed for runtime |
| Permissions/command failures | Vanilla permission level + Brigadier errors | Upstream command system | Single `mocap.admin` permission plus Bukkit argument validation; named numeric hints replace arbitrary sample numbers where values need to be typed; playback ID and actor-index meanings are documented | No full command integration suite or live TAB-completion test |
| Public extension API/events | Original API v1/events/extensions | Core API included in source | Not reproduced in Paper plugin | Not passed for parity |

## Paper's hard limit

Paper is not Fabric. The Paper build uses Paper 1.21.11 API and Mannequin actors. This can reproduce only behavior exposed by Paper API or explicitly implemented with safe plugin mechanisms. A paper build passing does not prove original fake-player/packet-level event parity. Any unsupported feature must remain visible in the matrix and must not be called “100% compatible”.

## Current gate

- Architecture/source audit: in progress; this matrix is the baseline.
- Static code review: required before release promotion.
- Fabric clean build/tests: prior CI run for branch commit `73ccdf58973300c40d3cdda177ec517ac0cd3668` succeeded; no runtime report was provided.
- Paper clean build/tests: CI passed for the audited branch snapshot.
- Runtime test on a real backup Paper 1.21.11 server: not run in this audit.
- Release: prerelease artifacts are build outputs, not a 100%-parity final release.


## Source and release traceability

- Official default source branch `26.1` is the command-tree audit baseline; this matrix is not an assertion that all targets share identical runtime APIs.
- Official target release: `v1.4-alpha-10+mc1.21.11`, published 2025-12-31. The GitHub `/releases/latest` endpoint separately returns `Mocap-1.3.10`, published 2025-12-11; use the correct label depending on whether “latest” means the API-designated latest release or latest target-specific asset.
- UltraOP branch names and target versions are separate: `main/core-26.1`, `fabric-1.21.11`, `paper-1.21.11`. A JAR built for one is not interchangeable with another.
- Paper code commit audited before audit-document-only commits: `995f5d4c6edb115e079122adbd644655ea8d0b24`. The branch has subsequently had audit docs/README/workflow edits; always read the latest branch head and match release tag `target_commitish` to the JAR and source archive before distributing.
- CI evidence: Paper build/tests and descriptor validation passed at run `40086999601` for source commit `995f5d4c6edb115e079122adbd644655ea8d0b24`. A later documentation workflow run passed build/test/JAR/source-package steps but failed at release publishing because the GitHub integration token received HTTP 403. This failure does not imply the JAR failed to build; neither does build success imply runtime acceptance.
