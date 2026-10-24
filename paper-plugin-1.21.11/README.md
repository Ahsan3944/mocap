# Mocap Paper 1.21.11 — UltraOP port

A separate Paper plugin for Java 21, using Paper's public Mannequin API. It reads the existing Mocap files from the first loaded world's mocap_files/recordings and mocap_files/scenes directories.

## Build
From the repository root run:
    ./gradlew -p paper-plugin-1.21.11 --no-daemon clean test build

Install the resulting JAR from paper-plugin-1.21.11/build/libs/ in the Paper server's plugins/ directory. Do not install it in Fabric's mods/ folder.

## Commands
All commands except help require permission mocap.admin (default: operator).
- /mocap recordings; /mocap scenes
- /mocap play <recording|.scene> [loop]; /mocap playhere <recording|.scene> [loop]
- /mocap list; /mocap stop <id>; /mocap stopall
- /mocap freeze <id>; /mocap unfreeze <id>; /mocap end_freeze <id>
- /mocap nametags <id> <hide|show>; /mocap low_lag <id> <on|off>
- /mocap random_names <scene> <comma,separated,names>
- /mocap random_skins <scene> <comma,separated,profile_names>
- /mocap set_name <scene> <element-index> <player-name>
- /mocap formation <scene> <recording> <rows> <columns> <spacing>
- /mocap copy_scene <source> <target>
- /mocap equipment save <group> <name>; list [group]; delete <group> <name>
- /mocap equipment apply <id> <group> <name> — applies a persistent preset override to all actors in the playback.
- /mocap equipment apply_element <id> <actor-index> <group> <name> — applies a persistent override to one actor (1-based index in expanded playback order).
- /mocap equipment randomize <id> <group> — assigns a persistent random preset to each actor.

Scenes use the existing Mocap JSON structure and .mcmocap_scene extension; recordings use .mcmocap_rec. Random names/profile names persist in scene JSON and are not re-rolled on replay. Random assignment and individual renaming reject nested scene elements.

## Known limits
Paper Mannequins are not fake ServerPlayer entities. This port shows movement, yaw/pitch, supported poses, main-hand orientation, basic item IDs, scene placement transforms, loops/freezing, name visibility and equipment presets. Recorded block edits/mining, chat, entity add/remove/mount, hits/swings, custom Mocap extension actions, and player-only packet semantics are consumed safely but not executed. Item components/NBT, custom PNG/MineSkin skins, embedded signed skin properties, mirror/custom scene centers and player-as-entity transforms are not fully ported. Profile lookup requires Mojang services. Low-lag is an immovability toggle, not a benchmarked performance guarantee.

Build/tests validate compilation, packaging and selected parser cases, not all live gameplay. Test on a backup world; 500 actors is a safety cap, not a performance promise.
