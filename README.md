# About
**Motion Capture** is a Minecraft mod that allows you to record and replay player movements. It is created mainly for people who want to create complex scenes in Minecraft without using real players.

It records:
- movements
- sprinting, jumping, swimming, sneaking
- swinging hands
- using items
- items in hands and armor
- states like being on fire, being invisible, or glowing
- placing, breaking and interacting with blocks
- nearby entities and riding them

After you save recording, you can create a scene, to which you can add multiple recordings or other scenes, with parameters like player name, position offset, or start delay.

CurseForge page: https://www.curseforge.com/minecraft/mc-mods/motion-capture-mod-mocap

Modrinth page: https://modrinth.com/mod/motion-capture

# How to use

- First, you need to start recording using ```/mocap recording start```. It will record all your movements or movements of a player given as an argument. 
- Then, you can stop recording using ```/mocap recording stop``` and save it with ```/mocap recording save <name>```.
- Use ```/mocap playback start <name>``` to play a recording or scene.
- You can also create scenes to which you can add multiple recordings or other scenes, with parameters like player name, position offset, or start delay.
- To create scene use ```/mocap scenes add <scene_name>```. Then, add your recording using ```/mocap scenes add_to <scene_name> <recording_name>``` or the modifier-based command options for delays, offsets, player names, and skins (you can skip arguments in square brackets if you want).
- To play a scene use ```/mocap playback start .<scene_name>``` - you need to put a dot before the scene name.
- By putting a dot before the name, when using ```/mocap scenes add_to (...)```, you can also add scenes to other scenes.

### Commands
```
/mocap recording [...] - Recording player movements
/mocap recordings [...] - Managing recording files
/mocap scenes [...] - Creating and modifying scenes
/mocap playback [...] - Playing scenes and recordings
/mocap equipment [...] - Saves and applies equipment presets
/mocap settings [...] - Shows and modifies settings
/mocap info - Displays information about mod
/mocap help - Displays help message
```

# Example

Simple example of one recording used 4 times in a scene with time and position offset

![](https://raw.githubusercontent.com/mt1006/mc-mocap-mod/_common/screenshots/example1.png)

One recording of climbing stairs used multiple times in a scene with start delay

![](https://raw.githubusercontent.com/mt1006/mc-mocap-mod/_common/screenshots/example2.png)


# Extended Motion Capture tools (this source update)

These commands are additions to the existing recording, scene, and playback workflow. The updated source package has not yet passed a Java/Gradle build or live Minecraft test, so use it as a development build until those checks are completed.

## Persistent random names and skins

`/mocap scenes random_names <scene_name> <name1,name2,name3,...>` randomly assigns unique player names to direct recording elements and saves them into the scene file. Replaying the scene keeps the saved names; it does not reroll them. If fewer names are supplied than elements, extra elements are unchanged.

To change one actor later, use `/mocap scenes modify <scene_name> <element_index> player_name set <new_name>` and save the scene as usual. The same saved-scene principle applies to `/mocap scenes random_skins <scene_name> <profile1,profile2,...>`. New skin profiles are prefetched in a small background worker pool to reduce server-thread stalls; wait briefly before starting a large scene, because a lookup that is still in flight may still block on the existing playback path.

Random name/skin assignment intentionally rejects scenes that directly contain nested scenes, because a nested element may represent several actors and uniqueness cannot be guaranteed at the parent level.

## Scene copy and placement anchor

`/mocap scenes copy <source_scene> <destination_scene>` creates a separate copy and leaves the source scene intact. For consistent placement when rotating/moving a copied scene, set the playback modifier before starting it: `/mocap playback modifiers set transformations config scene_center common_leftmost`, then `/mocap playback start .<destination_scene>`. This uses the leftmost direct scene element’s transformed center; nested scenes use their configured center. For a nested scene element, set `/mocap scenes modify <parent_scene> <element_index> transformations config scene_center common_leftmost`.

## Formation builder

`/mocap scenes formation <scene_name> <playable> <rows> <columns> <spacing>` adds a centered X/Z grid of a recording or scene to an existing scene. `rows` and `columns` are 1–100, spacing is 0.5–128 blocks, with a 1,000-element per-command cap, 5,000 expanded actors per scene tree, and 5,000 active actors across all playback trees.

Example: `/mocap scenes formation .army soldier_walk 5 8 2.5`

## Playback control

- `/mocap playback freeze <id>` pauses the playback tree.
- `/mocap playback unfreeze <id>` resumes it.
- `/mocap playback end_freeze <id>` keeps actors at their final state when playback ends; enable it before the end.
- `/mocap playback nametags hide <id>` and `/mocap playback nametags show <id>` hide/show name tags for living actors in that playback tree. All clients that use the matching Mocap network protocol need the updated mod.
- `/mocap playback low_lag <id> true|false` toggles collision-light mode for playback actors. This disables collision physics and skips fake-player attack-strength bookkeeping while preserving death cleanup. It may change combat/damage timing and does not eliminate client rendering or network costs, so it is not a guarantee that thousands of actors will run smoothly.

Use `/mocap playback stop <id>` to remove a held playback.

## Equipment presets

Presets are saved from the command executor's current armor and main/offhand equipment; the hotbar is not included.

- `/mocap equipment save <group> <preset>`
- `/mocap equipment list <group>`
- `/mocap equipment delete <group> <preset>`
- `/mocap equipment apply <playback_id> <group> <preset>` — applies to all actors in that playback tree.
- `/mocap equipment apply_element <playback_id> <element_index> <group> <preset>` — applies to one direct scene element (1-based index); if that element is a nested scene, all actors inside that nested scene receive it.
- `/mocap equipment randomize <playback_id> <group> <preset1,preset2,...>`

Groups and preset names must use the file-name characters accepted by Mocap. Applying a preset to a scene playback assigns it to every recording actor in the tree and reapplies it after recorded equipment actions, while applying it to a standalone recording playback affects that one actor. Randomize chooses independently for each actor. Presets are stored under the world's `mocap_files/equipment_presets/<group>/` directory.

## Build and test status

The source targets Minecraft 26.1 and Java 25. Use `./gradlew --no-daemon :fabric:build :neoforge:build` to compile both supported loaders. A generated JAR must pass archive integrity checks and still needs in-game regression testing before being treated as production-ready.
