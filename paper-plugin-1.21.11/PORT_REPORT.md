# Paper 1.21.11 port report — architecture audit

**Target:** Paper 1.21.11, Java 21, `paper-api:1.21.11-R0.1-SNAPSHOT`.
**Type:** Paper plugin JAR. It is not a Fabric/NeoForge mod.
**Audit scope:** Source/command/static review plus CI evidence. No live Minecraft server was launched during this audit.

## Current command/argument audit and CI gates

- Equipment commands now include armor-only removal (`remove`, `remove-element`) and selected-preset armor mixing (`mix`, `randomize`). Mix validates every selected preset in the same group before changing any actors; a random set is assigned per actor. Armor removal preserves held items, and mixed armor overrides are reapplied after recorded equipment frames.
- Equipment TAB completion suggests existing groups, names within the selected group, active playback IDs and one-based actor indexes. Profile-list commands have group/subgroup/ID completion. `auto` is deliberately the first completion option before saved profile names.
- The actor-index suggestions are generated from the selected active playback session. Numeric formation/time/transform arguments use named bracket hints rather than unlabeled choices such as `1, 3, 5`.
- Formation supports independent `column-gap` (side-to-side) and `row-gap` (front-to-back) values from 0–128 blocks. The legacy equal-gap add syntax remains compatible. The new `/mocap formation create <group> <source-recording> <rows> <columns> <column-gap> <row-gap>` creates a dedicated Group metadata record under `world/mocap_files/formations/` and a backing Scene with the same name; these are separate metadata and playable-scene layers, not duplicate folders. It saves the selected Blaze Rod anchor and the source recording name, with 500 actor maximum per playable Group (Paper mannequin cap). `formation list/info/play/delete` manages saved groups. The source actor stays at row 1/column 1, columns extend to the character's right, and rows extend behind along the starting recorded yaw. The old `formation add` remains for adding a grid to an existing scene and is capped at 1,000 additions per call.
- Placement tool: `/mocap formation tool` gives a custom-tagged Blaze Rod. Right-clicking a block stores its top-center as a per-player placement anchor. `/mocap paste <recording|.scene>` starts a new playback instance at that anchor; explicit `x y z` coordinates are also supported. For a saved Formation Group, `/mocap formation play <group> [loop]` uses its saved anchor/world. Whole-scene Move is `/mocap scenes modify move <scene>`; move a single top-level element/subscene with `/mocap scenes modify move <scene> <element-index[-name]>`. `/mocap scenes modify copy <source> <new-name>` preserves the source; if a placement target is selected, the copied scene is moved to that target. These operations alter scene offset metadata rather than moving an already-running playback; stop and restart an active playback to see a modified scene.
- Persistent name/skin libraries support groups, subgroups, stable entry IDs, bulk comma-separated save/add, exact-name-or-ID remove, UTF-8 text-file import and pagination (60 entries per page) so lists of thousands can be inspected. A **group** is a broad category and a **subgroup** is a named list inside it; for example `Season1/Heroes` and `Season1/Villains`. Names and skins are separate because they perform different jobs: displayed actor-name assignment versus name-based profile/skin assignment. Library files are stored under `plugins/MocapPaper/profile-libraries/`; imports go under its `imports/` directory.
- `/mocap customize names|skins <scene> <group> auto` draws candidates from the selected library group; `subgroup:<name>` restricts that pool. Lists support `/mocap names|skins list <group> [<subgroup>] [<page>|page:<page>]`. The older global `auto` mode also combines saved libraries, recorded/scene profiles and online players. Each actor is randomized independently; low candidate counts may repeat values. Assignments are persisted in scene JSON.
- The Paper settings index now exposes all 36 upstream field names under `recording`, `playback`, and `advanced`. Only `playback_speed`, `can_push_entities`, and `invulnerable_playback` currently have writable/persisted Paper behavior. Other fields are explicitly marked upstream-only and rejected on set; they are not falsely reported as implemented.
- Command roots were consolidated to one canonical command per family: use `scenes` not `scene`, `playback` not `playing`, `names` not `name`, `skins` not `skin`. `recording` and `recordings` are intentionally different: transient capture sessions versus saved recording files. Scene Move/Copy lives under `scenes modify`; root `copy` was removed. A positive optional custom numeric recording-session ID is accepted at capture start if it does not collide with an active/stopped session; this ID is transient capture bookkeeping, not a saved filename or scene ID.
- Java 21 clean build, parser/scene/equipment/settings JUnit tests, JAR ZIP integrity, plugin.yml, main class and entrypoint checks, source bundle creation, and SHA-256 checks are required for the current code commit. The newest run must be checked at [GitHub Actions](https://github.com/Ahsan3944/mocap/actions?query=branch%3Apaper-1.21.11).
- In-game command/TAB behavior, Fabric/Paper visual comparison, multi-actor playback, recording for 30 minutes, profile resolution, equipment restart persistence and long-loop runtime acceptance: **NOT RUN**.
- Full upstream feature parity: **NOT achieved**. See the feature matrix and source command reference linked below.

## Source implemented in this Paper target

- Paper-native recording lifecycle: start/stop/save/discard/list, player/selector target handling, movement/rotation/tick timing and supported pose changes.
- Recording writer emits a Mocap v3-compatible header with the actual recorded profile name; parser defensively reads supported v1–v5 inputs with bounds on file size, action count, ticks and nested payload depth.
- Scene JSON: safe paths/names, version checking, create/add/add_to/rename/remove/remove_from/modify/info/list helpers, nested-scene expansion/recursion guard, selected timing/transforms, whole-scene and element-level translation, and copy-under-modify.
- Paper Mannequin visual playback: supported movement/rotation/poses, basic item identifiers, name/profile display, looping, freeze/unfreeze/end-freeze controls, name-tag visibility, grouped equipment presets, armor removal and randomized armor mixing, and source-facing-relative formation helper.
- Random scene profile/name assignment is saved in scene JSON. Tests verify that a parent nested-scene element persists its own `player_name` and `player_skin` without mutating the referenced child scene. Effective profile/display name across the actual Mannequin playback path, no-reroll behavior and real client visuals still require runtime testing.

## Known parity gaps — do not describe these as implemented upstream features

- Paper recording does **not** capture block actions, chat, inventory/equipment changes as recorded actions, damage/combat, entity add/remove/mount, or custom extension actions.
- Paper Mannequins are not fake upstream ServerPlayer actors. Packet-level animation and original server/client entity behavior cannot be assumed.
- The original typed `settings` behavior is **partially implemented** in the Paper port. The index lists all 36 upstream fields, but only `playback_speed`, `can_push_entities`, and `invulnerable_playback` are supported and persisted. Other fields show `UPSTREAM-ONLY` and cannot be set; the full original typed validation and semantics are not ported. This is not full parity.
- The original `misc` group (`sync`, `clear_cache`, `refresh_suggestions`, `extensions`) is not implemented as upstream behavior in Paper.
- The original `playback modifiers set/list/reset/add_to` command context is not fully implemented in Paper.
- `scenes add_to` has a simplified Paper argument list and is not syntax-equivalent to upstream's full modifier tree (including optional X/Y/Z offsets and modifier/config options).
- Scene start delay/wait-on-start and selected transforms are supported, but wait-on-end, wait-for-parent-end, per-element loop scheduling, all scene-center configs and exact modifier composition have not been shown equivalent.
- Custom PNG/MineSkin skin injection and embedded signed texture properties are not fully ported. Profile names use Paper's resolvable profile API and depend on Paper/Mojang services.
- Recorded item playback reduces item identities to basic material IDs and does not preserve all arbitrary item components/NBT. Bukkit equipment presets may retain supported metadata, but enchanting/component parity has not been proven.
- Low-lag currently relies on an immovability/collision-related Paper toggle; it is not a measured equivalent to the Fabric optimization.
- Stopped unsaved sessions live in memory and are lost on plugin/server restart.

## Long-running loop bug — unresolved runtime gate

The reported “short recordings stop after a few minutes” issue has **not** been proven fixed by code compilation or by end-freeze controls. The loop path needs controlled live reproduction with:
1. 10-second, 30-second, and 60-second recordings.
2. Each loop observed for at least 30 minutes.
3. Freeze/unfreeze across a wrap; stop while frozen; end-freeze; nested scenes and delayed actors.
4. Player/profile resolution failure and actor update error injection.
5. Active session and actor counts recorded before/after each test, including cleanup assertions and server logs.

Do not mark the long-running loop bug as fixed until these checks pass and the underlying stop condition is identified.

## Verification artifact links

- [Original-versus-Our Feature Matrix](audit/Original-versus-Our-Feature-Matrix.md)
- [Feature Specification and Verification Plan](audit/ULTRAOP-MOCAP-FEATURE-SPECIFICATION.md)
- [Original Mocap Command Reference](audit/MOCAP-ORIGINAL-COMMAND-REFERENCE.md)
- [Original upstream source](https://github.com/mt1006/mc-mocap-mod/tree/26.1)
- [UltraOP Paper branch](https://github.com/Ahsan3944/mocap/tree/paper-1.21.11)
- [Latest CI build and tests](https://github.com/Ahsan3944/mocap/actions?query=branch%3Apaper-1.21.11)

## JUnit coverage currently evidenced

The current checked-in tests cover Formation Group metadata and backing-scene creation, anchor placement and independent row/column gaps, whole-scene movement preserving actor offsets, single-element move preserving other actors, encoder-to-parser round-trip (movement/rotation/pose and recorded player name), malformed/truncated/unsupported recording inputs, scene version validation, traversal guard, scene create/add/modify/rename/remove flows, refusal to overwrite a copied scene target, nested scene recursion/depth checks, formation zero-offset anchor/right-behind geometry and independent row/column gaps, recorded-profile autocomplete input, grouped profile-library bulk save/add/remove/import with stable IDs and 3,000-entry lists, equipment group/preset file listing and safe names, and Paper settings groups/persistence/unsupported-field rejection. Blaze Rod interaction/click anchor, actual in-game Formation Group start, scene-wide/element-level move commands, explicit coordinate paste and live TAB behavior still require an actual Paper 1.21.11 runtime test. These tests do not yet exercise every TabCompleter path, every original API, all modifier semantics, all item components/enchantments, or actual server/client playback.

## Required release gate

A Paper prerelease is a **tested build artifact**, not a 100%-parity final release. Before calling this project feature-complete, close the gaps above, add per-feature command/data/API tests, run static review, run clean tests, run feature regression on a backup world, and verify the JAR/source ZIP/report SHA against the exact release target commit.
