# Paper 1.21.11 port report — architecture audit

**Target:** Paper 1.21.11, Java 21, `paper-api:1.21.11-R0.1-SNAPSHOT`.
**Type:** Paper plugin JAR. It is not a Fabric/NeoForge mod.
**Audit scope:** Source/command/static review plus CI evidence. No live Minecraft server was launched during this audit.

## Status at audited code commit 995f5d4c6edb115e079122adbd644655ea8d0b24

- Java 21 clean build, the current parser/scene JUnit suite (including parent modifier persistence, nested-scene recursion/depth guards and timing/transform serialization), JAR ZIP integrity, plugin.yml, main class and entrypoint checks: **PASS** in GitHub Actions run [40091053688](https://github.com/Ahsan3944/mocap/actions/runs/40091053688).
- In-game command/TAB behavior, Fabric/Paper visual comparison, multi-actor playback, recording for 30 minutes, profile resolution, equipment restart persistence and long-loop runtime acceptance: **NOT RUN**.
- Full upstream feature parity: **NOT achieved**. See the feature matrix and source command reference linked below.

## Source implemented in this Paper target

- Paper-native recording lifecycle: start/stop/save/discard/list, player/selector target handling, movement/rotation/tick timing and supported pose changes.
- Recording writer emits a Mocap v3-compatible header with the actual recorded profile name; parser defensively reads supported v1–v5 inputs with bounds on file size, action count, ticks and nested payload depth.
- Scene JSON: safe paths/names, version checking, create/add/add_to/copy/rename/remove/remove_from/modify/info/list helpers, nested-scene expansion/recursion guard, some timing and transform support.
- Paper Mannequin visual playback: supported movement/rotation/poses, basic item identifiers, name/profile display, looping, freeze/unfreeze/end-freeze controls, name-tag visibility, grouped equipment presets and formation helper.
- Random scene profile/name assignment is saved in scene JSON. Tests verify that a parent nested-scene element persists its own `player_name` and `player_skin` without mutating the referenced child scene. Effective profile/display name across the actual Mannequin playback path, no-reroll behavior and real client visuals still require runtime testing.

## Known parity gaps — do not describe these as implemented upstream features

- Paper recording does **not** capture block actions, chat, inventory/equipment changes as recorded actions, damage/combat, entity add/remove/mount, or custom extension actions.
- Paper Mannequins are not fake upstream ServerPlayer actors. Packet-level animation and original server/client entity behavior cannot be assumed.
- The original typed `settings` registry is **not implemented** in the Paper port. `/mocap settings` currently prints a limitations/help message instead of listing and changing the original `recording`, `playback`, and `advanced` settings. This is not parity.
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
- [CI build and tests](https://github.com/Ahsan3944/mocap/actions/runs/40086999601)

## JUnit coverage currently evidenced

The checked-in tests cover encoder-to-parser round-trip (movement/rotation/pose and recorded player name), malformed/truncated/unsupported recording inputs, scene version validation, traversal guard, selected scene create/add/modify/rename/remove flows and refusal to overwrite a copied scene target. These tests do not yet cover every command, every original setting/API, all modifier semantics, all item components, or actual server/client playback.

## Required release gate

A Paper prerelease is a **tested build artifact**, not a 100%-parity final release. Before calling this project feature-complete, close the gaps above, add per-feature command/data/API tests, run static review, run clean tests, run feature regression on a backup world, and verify the JAR/source ZIP/report SHA against the exact release target commit.
