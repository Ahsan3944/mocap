# UltraOP MoCap Feature Specification and Verification Plan

Date: 2026-10-24
Paper target: Paper 1.21.11, Java 21
Fabric target: Minecraft 1.21.11, Fabric, Java 21
Original source baseline: official repository branch `26.1`; target-specific release tag `v1.4-alpha-10+mc1.21.11`.

## Phase 0 — baseline and guardrails

1. Treat original command tree, serialized recording format, scene JSON versions and typed settings as compatibility contracts.
2. Keep target-specific source/build outputs separate: Minecraft 26.1 core, Fabric 1.21.11 port, Paper 1.21.11 plugin.
3. Before editing serialized formats, write a migration plan and fixtures for old recordings/scenes.
4. Keep all prior source snapshots and releases available; do not overwrite an artifact under a stable release name unless tag provenance matches the commit.
5. An automated build result never means live runtime behavior is passed.

## Feature specifications

### A. Original command parity

**Goal:** retain original names and argument order as documented by source, adding aliases only as conveniences.

**Canonical roots:** `recording`, `recordings`, `scenes`, `playback`, `settings`, `misc`, `info`, `help`.

**Paper mapping:** aliases such as `scene`, `playing`, `playback`, `customize`, `formation`, and `copy` must not replace original routes. The original `settings` path must expose actual settings or explicitly return “unsupported in Paper” at the individual setting level; root-level help text alone is not parity.

**Files:** `PaperMocapCommand.java`, original per-group command classes, `plugin.yml`, help/usage docs, command integration tests.

**Persistence:** where the original stores typed settings/config, Paper must define its own versioned persistent config or clearly expose unsupported fields.

**Errors:** missing/invalid arguments produce action-specific syntax, no hidden successful return; no unsafe partial playback after failure.

**Tests:** test every exact command from Brigadier’s source tree and confirm expected feedback, permission enforcement and autocomplete.

### B. Recording data and session lifecycle

**Goal:** for the Fabric branch, preserve original capture behavior and binary format. For Paper, use a separately named partial recorder until all unsupported action families have implementations.

**Paper currently captures:** position, rotation, timing, some pose changes, and the true profile-name header. It does not capture block edits, chat, inventory/equipment events, combat/damage, entity lifecycle/mounting, or custom actions.

**File path:** `mocap_files/recordings/<name>.mcmocap_rec` under the configured world root.

**Format compatibility:** preserve legacy version headers and parser acceptance. Changes require old-format fixtures and read-after-write tests. The newest port encoder uses a Mocap v3-compatible header; do not describe it as v1.

**Lifecycle edge cases:** player disconnect/death/dimension change, plugin disable/restart, encoder/IO errors, max duration/file size, duplicate instant-save name, partial multi-player success, explicit discard.

**Tests:** write+parse, player identity header, rotation-only, movement-only, idle ticks, pose sequence, truncated data, max duration, IO failure, restart loss/persistence policy.

### C. Scene data / authoring

**Canonical original syntax:** `/mocap scenes add_to <scene> <recording|.scene|active-pattern> [wait_on_start] [full modifier arguments]`.

**Features:** create, copy, rename, remove, add element, add pattern, remove by safe index/name, modify any implemented modifier, info, list. Nested scenes must detect cycles and enforce bounded expansion.

**Serialization:** maintain `version` and `subscenes`; maintain modifier field names `time`, `transformations`, `player_name`, `player_skin`, `player_as_entity`, `entity_filter`, `subscene_name` where supported. Reader must preserve unknown fields when safe, or document strict lossy behavior.

**Copy:** create a detached deep copy. Later editing the source cannot mutate the copy; copying must never overwrite an existing destination.

**Position/timing composition:** apply transforms in documented order; delays accumulate predictably; start-delay and wait-on-start are not conflated unless conversion is explicit. Test nested transforms at each depth and multiple actors.

**Tests:** v1-v4/legacy fixtures, malformed JSON, future version, traversal attempt, recursive scenes, missing child, source/copy independence, remove index/name mismatch, composition order, timing.

### D. Random names and skins

**Purpose:** assign values once, serialize them to the scene, and do not reroll during replay or loop.

**Command placement:** keep as an explicitly documented add-on under `scenes` or `customize` while preserving original scene modify syntax. Do not duplicate routes unless there is a compatibility reason.

**Arguments:** Scene name plus validated comma-separated candidate profile/name list. Trim whitespace, reject empty values, deduplicate as appropriate, preserve deterministic stored result. Profile identifiers must obey Mojang name constraints.

**Nested scenes:** do not silently edit children or produce inconsistent effective names. Specify whether assignment modifies a direct nested-scene reference (inherited override) or the child scene's own actors; tests must enforce the chosen behavior.

**Tests:** save/load, two playbacks use same assignment, loop does not reroll, short candidate list vs many actors, invalid/empty list, nested references, source-child independence.

### E. Formation Builder and scene placement

**Purpose:** create multiple elements with predictable row/column/spacing and optionally anchor/rotate/offset the resulting formation.

**Command placement:** `scenes formation` is preferred so scene authoring remains together; legacy `formation` alias may remain if unambiguous.

**Arguments:** scene, recording/nested scene, rows, columns, spacing and optional offset/rotation/anchor. Set a hard upper limit and validate the product `rows × columns` before writing.

**Persistence:** write each generated element with fully resolved offset/rotation; no runtime-only state unless clearly documented.

**Tests:** 1×1, rectangular grid, invalid dimensions, max count, negative/zero spacing policy, rotated grid, source scene unchanged.

### F. Equipment presets

**Purpose:** named groups containing slot, material/item data and supported metadata, optionally applied per actor, entire playback, or scene element.

**Command placement:** preserve upstream `recordings`/scene/playback command semantics; keep new presets beneath one documented `equipment` subtree/extension without shadowing original roots.

**Persistence:** versioned JSON with safe atomic write. Store item serialized data including enchantments/components supported by Bukkit; never silently degrade arbitrary unsupported components. Include migration validation and size limits.

**Apply order:** establish actor baseline, apply recording frame, then apply the documented preset override; make the order consistent across loops. Randomize once and persist when the intended behavior is “stable per replay”.

**Tests:** enchanted armor, arbitrary supported components, each slot, missing preset, corrupted JSON, restart persistence, playback frame changes, loop reset, nested scene element target.

### G. Freeze / unfreeze / end-freeze

**Semantics:** Freeze pauses session clock and actor mutation, preserving exact frame/tick and elapsed state. Unfreeze resumes at next tick, not from tick zero. End Freeze holds last successfully applied pose once normal playback reaches its end; manual Stop still removes actors. Define conflict behavior if both Freeze and End Freeze are enabled.

**State:** session `currentTick`, `frozen`, `heldAtEnd`, `loop`, `endFreeze`, actor frame indices. Validate transitions and cleanup idempotency.

**Tests:** freeze mid-frame, freeze through expected end, unfreeze from same pose, end-freeze without loop, stop while frozen, stop-all, exception during actor update, nested cleanup.

### H. Long-running playback loops

**Investigate before patching:** reproduce current reported failure with 10s, 30s, 60s recordings; inspect duration ticks, last frame, current tick, timeout and task scheduling, exceptions, session cleanup and wrap/reset logic. Record the exact failing tick and server logs. Do not infer a cause solely from seeing the final frame.

**Implementation requirement:** session duration must come from real parsed frame ticks/action duration, not frame count; looping resets actor frame indices and time origin without leaking entities/tasks/equipment overrides. A malformed frame or missing profile must not stop unrelated sessions.

**Tests:** each duration loops 30 minutes with logs/counters, manual stop, end-freeze, freeze/unfreeze across a wrap, missing file and nested scene; assert session alive count and entity count remain constant.

### I. Low-lag / memory / concurrency

**Fabric:** protect scheduled work, profile prefetch queues and network packet sizes; keep server-thread mutations on the server thread. Record queue saturation and cache metrics.

**Paper:** mannequin immovability alone is not equivalent to original low-lag. Benchmark movement updates, equipment updates, profile lookups, entity count and per-tick task time before claiming an optimization.

**Tests:** 50/100/250/500 actors; report MSPT p50/p95/p99, heap growth, GC, packets/sec and client FPS. Avoid claims of “smooth” from a build-only test.

### J. Typed settings / persistence / permissions

**Original groups:** `recording`, `playback`, `advanced`. Current upstream fields include entity tracking, record-on-death/dimension, profile assignment, chat toggles, playback speed, entity/block behavior, dimension source, name handling, hit range, movement precision bounds, MineSkin controls, NBT policy and extension requirements.

**Paper:** do not say settings are “implemented” if only a message is printed. Implement actual config for Paper-supported fields and clearly mark unsupported original settings with their reasons.

**Permissions:** root/aliases must share one documented permission policy; test player, console, operator and non-operator cases.

### K. Release verification

Every feature gets four separate statuses: Source Implemented, Build Passed, Automated Test Passed, Runtime Test Passed.

Release requires:
1. Source and branch commit identified.
2. Original-vs-target feature matrix and command tree updated.
3. Static source review and test coverage reviewed.
4. Clean build on the declared Java toolchain.
5. Unit/compatibility/regression test results captured.
6. Live runtime suite performed or explicitly left NOT RUN.
7. JAR/source zip/report hashes verified against the exact source commit.
8. Fabric and Paper artifacts remain different artifacts and install roots.
