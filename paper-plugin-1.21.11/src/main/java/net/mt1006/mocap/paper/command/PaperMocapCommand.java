package net.mt1006.mocap.paper.command;

import net.mt1006.mocap.paper.MocapPaperPlugin;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.playback.PaperPlaybackManager;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import net.mt1006.mocap.paper.recording.PaperRecordingManager;
import net.mt1006.mocap.paper.settings.PaperSettingsStore;
import net.mt1006.mocap.paper.profile.ProfileLibraryStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hierarchical Paper command surface aligned to upstream Mocap groups.
 * Paper-specific limitations are reported explicitly rather than silently
 * pretending unsupported game actions are available.
 */
public final class PaperMocapCommand implements CommandExecutor, TabCompleter {
    private final MocapPaperPlugin plugin;
    private final MocapSceneStore scenes;
    private final EquipmentPresetStore equipment;
    private final PaperPlaybackManager playbacks;
    private final PaperRecordingManager recordings;
    private final PaperSettingsStore settings;
    private final ProfileLibraryStore profileLibraries;

    public PaperMocapCommand(MocapPaperPlugin plugin, MocapSceneStore scenes,
                             EquipmentPresetStore equipment, PaperPlaybackManager playbacks,
                             PaperRecordingManager recordings, PaperSettingsStore settings,
                             ProfileLibraryStore profileLibraries) {
        this.plugin = plugin;
        this.scenes = scenes;
        this.equipment = equipment;
        this.playbacks = playbacks;
        this.recordings = recordings;
        this.settings = settings;
        this.profileLibraries = profileLibraries;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || eq(args[0], "help")) {
            help(sender);
            return true;
        }
        if (!sender.hasPermission("mocap.admin") && !eq(args[0], "info")) {
            sender.sendMessage("You do not have permission to manage Mocap. Required: mocap.admin");
            return true;
        }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "info" -> info(sender);
                case "recording" -> recordingCommand(sender, args);
                case "recordings" -> recordingsCommand(sender, args);
                case "scenes" -> sceneCommand(sender, args);
                case "playback" -> playbackCommand(sender, args);
                case "settings" -> settingsCommand(sender, args);
                case "customize" -> customizeCommand(sender, args);
                case "names" -> profileLibraryCommand(sender, args, "names");
                case "skins" -> profileLibraryCommand(sender, args, "skins");
                case "formation" -> formationCommand(sender, args);
                case "paste" -> pasteCommand(sender, args);
                default -> {
                    sender.sendMessage("Unknown Mocap command group: " + args[0]);
                    help(sender);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            sender.sendMessage("Mocap: " + e.getMessage());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Mocap command failed: " + e.getMessage());
            sender.sendMessage("Mocap command failed safely; check the server log.");
        }
        return true;
    }

    private void recordingCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP RECORDING");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording start [<player|selector>] [<instant-save-name>] [<custom-session-id>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording stop [<id>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording save <name> [<id>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording discard [<id>] | list [<id>]");
            sender.sendMessage(ChatColor.GRAY + "custom-session-id is an optional positive number for this capture session; it must not already be active/stopped.");
            sender.sendMessage(ChatColor.GRAY + "Saved recording files are named with <name>; a session ID is only the temporary capture/session ID.");
            sender.sendMessage(ChatColor.GRAY + "Paper captures player position and rotation only; block/chat/inventory actions are not recorded.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length > 5) { usage(sender, "/mocap recording start [<player|selector>] [<instant-save-name>] [<custom-session-id>]"); return; }
                String selector = args.length >= 3 && !args[2].isBlank() && !args[2].equals("-") ? args[2].trim() : null;
                List<Player> targets = resolveRecordingTargets(sender, selector);
                String instantName = args.length >= 4 && !args[3].isBlank() && !args[3].equals("-") ? args[3].trim() : null;
                String requestedId = args.length >= 5 && !args[4].isBlank() ? args[4].trim() : null;
                if (requestedId != null && targets.size() != 1) throw new IOException("A custom session ID is allowed for one selected player only. Use a single player name instead of @a.");
                int started = 0;
                List<String> errors = new ArrayList<>();
                for (Player target : targets) {
                    String targetName = instantName;
                    if (targetName != null && targets.size() > 1) targetName = targetName + "_" + target.getName();
                    try {
                        String id = recordings.start(target, targetName, requestedId);
                        sender.sendMessage(ChatColor.GREEN + "Recording started for " + target.getName() + ChatColor.GRAY + " (ID " + id + ").");
                        started++;
                    } catch (IOException e) {
                        errors.add(target.getName() + ": " + e.getMessage());
                    }
                }
                for (String error : errors) sender.sendMessage(ChatColor.RED + error);
                if (started == 0) throw new IOException(errors.isEmpty() ? "No player was selected. Try /mocap recording start @a." : "No recordings were started.");
                if (started > 1) sender.sendMessage(ChatColor.GREEN + "Started " + started + " player recording(s).");
            }
            case "stop" -> sender.sendMessage(recordings.stop(args.length >= 3 ? args[2] : null, asPlayer(sender)));
            case "save" -> {
                if (args.length < 3) { usage(sender, "/mocap recording save <name> [<id>]"); return; }
                sender.sendMessage(recordings.save(args[2], args.length >= 4 ? args[3] : null, asPlayer(sender)));
            }
            case "discard" -> sender.sendMessage(recordings.discard(args.length >= 3 ? args[2] : null, asPlayer(sender)));
            case "list" -> {
                List<String> lines = recordings.statusLines();
                if (args.length >= 3 && !args[2].isBlank()) lines = lines.stream().filter(line -> line.startsWith(args[2] + " ")).toList();
                sender.sendMessage(lines.isEmpty() ? "No active or stopped Paper recordings found." : ChatColor.GOLD + "Paper recordings:");
                lines.forEach(line -> sender.sendMessage(ChatColor.GRAY + line));
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown recording action: " + args[1]);
                sender.sendMessage("Usage: /mocap recording <start|stop|save|discard|list>");
            }
        }
    }

    private List<Player> resolveRecordingTargets(CommandSender sender, String selector) throws IOException {
        if (selector == null || selector.isBlank()) {
            if (sender instanceof Player player) return List.of(player);
            throw new IOException("Console must specify a player or selector, for example /mocap recording start @a.");
        }
        List<Player> targets = new ArrayList<>();
        if (selector.startsWith("@")) {
            try {
                for (Entity entity : Bukkit.selectEntities(sender, selector)) {
                    if (entity instanceof Player player) targets.add(player);
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException("Invalid player selector '" + selector + "': " + e.getMessage(), e);
            }
        } else {
            for (String rawName : selector.split(",")) {
                String name = rawName.trim();
                if (name.isEmpty()) continue;
                Player player = Bukkit.getPlayerExact(name);
                if (player == null) throw new IOException("Player is not online: " + name);
                targets.add(player);
            }
        }
        List<Player> unique = targets.stream().collect(
                java.util.stream.Collectors.toMap(Player::getUniqueId, p -> p, (first, ignored) -> first, LinkedHashMap::new))
                .values().stream().toList();
        if (unique.isEmpty()) throw new IOException("No online players matched '" + selector + "'. Try @a, @p, @s, or an online player name.");
        return unique;
    }

    private static Player asPlayer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    private void recordingsCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP SAVED RECORDINGS");
            sender.sendMessage(ChatColor.WHITE + "/mocap recordings list");
            sender.sendMessage(ChatColor.WHITE + "/mocap recordings info <name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap recordings copy <source> <target>");
            sender.sendMessage(ChatColor.WHITE + "/mocap recordings rename <old_name> <new_name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap recordings remove <name>");
            sender.sendMessage(ChatColor.GRAY + "Files are stored in world/mocap_files/recordings/.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listRecordings(sender);
            case "info" -> {
                if (args.length < 3) { usage(sender, "/mocap recordings info <name>"); return; }
                var path = scenes.recordingPath(args[2]);
                var parsed = new net.mt1006.mocap.paper.playback.MocapRecordingParser().parse(path);
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP RECORDING INFO");
                sender.sendMessage(ChatColor.GRAY + "File: " + ChatColor.WHITE + path.getFileName());
                sender.sendMessage(ChatColor.GRAY + "Recorded player: " + ChatColor.AQUA + (parsed.recordedProfileName() == null ? "(not stored)" : parsed.recordedProfileName()));
                sender.sendMessage(ChatColor.GRAY + "Format: " + ChatColor.AQUA + parsed.formatVersion());
                sender.sendMessage(ChatColor.GRAY + "Size: " + ChatColor.AQUA + Files.size(path) + " bytes");
                sender.sendMessage(ChatColor.GRAY + "Duration: " + ChatColor.AQUA + parsed.durationTicks() + " ticks");
            }
            case "copy" -> {
                if (args.length < 4) { usage(sender, "/mocap recordings copy <source> <target>"); return; }
                scenes.copyRecording(args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Copied recording " + args[2] + " to " + args[3] + ".");
            }
            case "rename" -> {
                if (args.length < 4) { usage(sender, "/mocap recordings rename <old_name> <new_name>"); return; }
                scenes.renameRecording(args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Renamed recording " + args[2] + " to " + args[3] + ".");
            }
            case "remove", "delete" -> {
                if (args.length < 3) { usage(sender, "/mocap recordings remove <name>"); return; }
                scenes.removeRecording(args[2]);
                sender.sendMessage(ChatColor.GREEN + "Removed recording " + args[2] + ".");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown recordings action: " + args[1]);
                sender.sendMessage("Usage: /mocap recordings <list|info|copy|rename|remove>");
            }
        }
    }

    private void sceneCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            scenesHelp(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add", "create" -> {
                if (args.length < 3) { usage(sender, "/mocap scenes add <scene_name>"); return; }
                scenes.createScene(args[2]);
                sender.sendMessage(ChatColor.GREEN + "Created scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
            }
            case "add_to", "addto", "add-to" -> addToScene(sender, args);
            case "rename" -> {
                if (args.length < 4) { usage(sender, "/mocap scenes rename <old_name> <new_name>"); return; }
                scenes.renameScene(args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Renamed scene " + args[2] + " to ." + MocapSceneStore.normalizeSceneName(args[3]) + ".");
            }
            case "remove", "delete" -> {
                if (args.length < 3) { usage(sender, "/mocap scenes remove <scene_name>"); return; }
                scenes.removeScene(args[2]);
                sender.sendMessage(ChatColor.GREEN + "Removed scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
            }
            case "remove_from", "remove-from" -> {
                if (args.length < 4) { usage(sender, "/mocap scenes remove_from <scene_name> <element_index[-name]>"); return; }
                scenes.removeSceneElement(args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Removed element " + args[3] + " from scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
            }
            case "modify" -> {
                if (args.length >= 3 && eq(args[2], "move")) {
                    if (args.length != 4 && args.length != 5) {
                        usage(sender, "/mocap scenes modify move <scene-name> [<element-index[-expected-name]>]");
                        return;
                    }
                    if (!(sender instanceof Player player)) throw new IOException("Scene move requires an in-game target selection.");
                    Location target = plugin.placementAnchor(player);
                    if (target == null) throw new IOException("No target selected. Run /mocap formation tool, then right-click the destination block before moving a scene or actor.");
                    if (args.length == 5) {
                        scenes.moveSceneElementTo(args[3], args[4], target);
                        sender.sendMessage(ChatColor.GREEN + "Moved element " + ChatColor.AQUA + args[4]
                                + ChatColor.GREEN + " in Scene ." + ChatColor.AQUA + MocapSceneStore.normalizeSceneName(args[3])
                                + ChatColor.GREEN + " to the selected destination.");
                    } else {
                        scenes.moveSceneTo(args[3], target);
                        sender.sendMessage(ChatColor.GREEN + "Moved the entire Scene ." + ChatColor.AQUA + MocapSceneStore.normalizeSceneName(args[3])
                                + ChatColor.GREEN + " so its first actor is at the selected destination.");
                    }
                    return;
                }
                if (args.length >= 3 && eq(args[2], "copy")) {
                    if (args.length != 5) { usage(sender, "/mocap scenes modify copy <source-scene> <new-scene-name>"); return; }
                    scenes.copyScene(args[3], args[4]);
                    boolean placed = false;
                    if (sender instanceof Player player) {
                        Location target = plugin.placementAnchor(player);
                        if (target != null) {
                            scenes.moveSceneTo(args[4], target);
                            placed = true;
                        }
                    }
                    sender.sendMessage(ChatColor.GREEN + "Copied Scene ." + ChatColor.AQUA + MocapSceneStore.normalizeSceneName(args[3])
                            + ChatColor.GREEN + " to ." + ChatColor.AQUA + MocapSceneStore.normalizeSceneName(args[4]) + ChatColor.GREEN + "."
                            + (placed ? " The copy was placed at the currently selected target." : " Original scene coordinates were preserved."));
                    return;
                }
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /mocap scenes modify <scene> <element> <property> <values...>");
                    sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify move <scene-name> [<element-index[-expected-name]>]");
                    sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify copy <source-scene> <new-scene-name>");
                    sender.sendMessage(ChatColor.GRAY + "Per-element properties: subscene_name, player_name, player_skin, time, transformations.");
                    return;
                }
                String[] values = Arrays.copyOfRange(args, 5, args.length);
                scenes.modifySceneElement(args[2], args[3], args[4], values);
                sender.sendMessage(ChatColor.GREEN + "Updated element " + args[3] + " in scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
            }
            case "info" -> sceneInfo(sender, args);
            case "list" -> {
                if (args.length < 3) listScenes(sender);
                else listSceneElements(sender, args[2]);
            }
            case "customize" -> customizeCommand(sender, shift(args, 1, "customize"));
            case "formation" -> formationCommand(sender, shift(args, 1, "formation"));
            case "play" -> {
                if (args.length < 3) { usage(sender, "/mocap scenes play <scene> [loop]"); return; }
                startPlayback(sender, args[2], args.length > 3 && eq(args[3], "loop"), false);
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown scenes action: " + args[1]);
                scenesHelp(sender);
            }
        }
    }

    private void addToScene(CommandSender sender, String[] args) throws IOException {
        if (args.length < 4) {
            usage(sender, "/mocap scenes add_to <scene> <recording|.nested_scene> [<wait_on_start_seconds>] [<player_name>] [<skin_from_player|skin_from_file|skin_from_mineskin> <skin_value>]");
            return;
        }
        double waitOnStart = 0.0;
        if (args.length >= 5) {
            try { waitOnStart = Double.parseDouble(args[4]); }
            catch (NumberFormatException e) { throw new IOException("wait_on_start must be a number of seconds (for example 0 or 2.5).", e); }
        }
        String playerName = args.length >= 6 ? args[5] : null;
        String skinSource = args.length >= 7 ? args[6] : "default";
        String skinPath = args.length >= 8 ? args[7] : null;
        scenes.addSceneElement(args[2], args[3], waitOnStart, playerName, skinSource, skinPath);
        sender.sendMessage(ChatColor.GREEN + "Added " + args[3] + " to scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
        if (waitOnStart > 0) sender.sendMessage(ChatColor.GRAY + "Start delay: " + waitOnStart + " seconds.");
        if (playerName != null && !playerName.equals("-")) sender.sendMessage(ChatColor.GRAY + "Display player name: " + playerName);
    }

    private void sceneInfo(CommandSender sender, String[] args) throws IOException {
        if (args.length < 3) { usage(sender, "/mocap scenes info <scene_name> [<element_index[-name]>]"); return; }
        var scene = scenes.readScene(args[2]);
        var entries = scene.getAsJsonArray("subscenes");
        String name = "." + MocapSceneStore.normalizeSceneName(args[2]);
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP SCENE INFO");
        sender.sendMessage(ChatColor.GRAY + "Name: " + ChatColor.WHITE + name);
        sender.sendMessage(ChatColor.GRAY + "Format: " + ChatColor.AQUA + scene.get("version").getAsInt());
        sender.sendMessage(ChatColor.GRAY + "Elements: " + ChatColor.AQUA + entries.size());
        sender.sendMessage(ChatColor.GRAY + "File size: " + ChatColor.AQUA + Files.size(scenes.scenePath(args[2])) + " bytes");
        if (args.length >= 4) {
            int index = parseElementIndex(args[3]);
            if (index < 1 || index > entries.size()) throw new IOException("Scene element index is out of range.");
            var el = entries.get(index - 1).getAsJsonObject();
            sender.sendMessage(ChatColor.YELLOW + "Element " + index + ChatColor.GRAY + " — " + ChatColor.WHITE + el.get("name").getAsString());
            sender.sendMessage(ChatColor.GRAY + "Player name: " + ChatColor.WHITE + (el.has("player_name") ? el.get("player_name").getAsString() : "(inherited/default)"));
            sender.sendMessage(ChatColor.GRAY + "Player skin: " + ChatColor.WHITE + (el.has("player_skin") ? el.getAsJsonObject("player_skin").toString() : "default"));
            sender.sendMessage(ChatColor.GRAY + "Time: " + ChatColor.WHITE + (el.has("time") ? el.getAsJsonObject("time").toString() : "default"));
            sender.sendMessage(ChatColor.GRAY + "Transformations: " + ChatColor.WHITE + (el.has("transformations") ? el.getAsJsonObject("transformations").toString() : "default"));
        }
    }

    private void listSceneElements(CommandSender sender, String sceneName) throws IOException {
        var scene = scenes.readScene(sceneName);
        var entries = scene.getAsJsonArray("subscenes");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "SCENE ELEMENTS " + ChatColor.GRAY + "." + MocapSceneStore.normalizeSceneName(sceneName));
        if (entries.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "This scene is empty. Add one with /mocap scenes add_to " + MocapSceneStore.normalizeSceneName(sceneName) + " <recording>.");
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            var el = entries.get(i).getAsJsonObject();
            String playerName = el.has("player_name") ? el.get("player_name").getAsString() : "inherited/default";
            sender.sendMessage(ChatColor.GRAY + String.format(Locale.ROOT, "%03d", i + 1) + ChatColor.WHITE + " " + el.get("name").getAsString()
                    + ChatColor.DARK_GRAY + " (" + playerName + ")");
        }
        sender.sendMessage(ChatColor.GRAY + "Remove: /mocap scenes remove_from " + MocapSceneStore.normalizeSceneName(sceneName) + " <element_index[-name]>");
    }

    private static int parseElementIndex(String raw) throws IOException {
        int dash = raw.indexOf('-');
        String digits = dash >= 0 ? raw.substring(0, dash) : raw;
        try { return Integer.parseInt(digits); }
        catch (NumberFormatException e) { throw new IOException("Scene element must use a numeric 1-based index.", e); }
    }

    private static void scenesHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "           MOCAP SCENES");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes add <scene_name>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes add_to <scene> <recording|.nested_scene> [<wait_on_start_seconds>] [<player_name>] [<skin_source> <skin_value>]");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes rename <source> <target>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <remove <scene>|remove_from <scene> <element>>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify move <scene> [<element-index[-expected-name]>]");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify copy <source> <new-scene-name>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify <scene> <element> <property> <values...>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <list [scene]|info <scene> [element]>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes play <recording|.scene> [loop]");
        sender.sendMessage(ChatColor.GRAY + "modify owns scene-wide move/copy and per-element properties (player_name/player_skin/time/transformations). A move requires a target selected with the placement rod.");
        sender.sendMessage(ChatColor.GRAY + "customize is an UltraOP shortcut for bulk names/skins/equipment and is not a replacement for modify.");
        sender.sendMessage(ChatColor.GRAY + "For numeric values, TAB shows a bracketed argument hint; replace it with a real number before submitting.");
        sender.sendMessage(ChatColor.GRAY + "Use /mocap scenes <action> then press TAB to explore arguments.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void playbackCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            playbackHelp(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String[] a = shift(args, 1, action);
        switch (action) {
            case "start" -> {
                if (a.length < 2) {
                    usage(sender, "/mocap playback start <recording|.scene> [<player_name>] [skin_from_player <profile>] [loop]");
                    return;
                }
                String playable = a[1];
                String displayName = null;
                String profileName = null;
                boolean loop = false;
                for (int i = 2; i < a.length; i++) {
                    String option = a[i];
                    String lower = option.toLowerCase(Locale.ROOT);
                    if (lower.equals("loop")) {
                        loop = true;
                    } else if (lower.equals("default")) {
                        // Explicit default profile is accepted for compatibility.
                    } else if (lower.equals("skin_from_player") || lower.equals("from_player")) {
                        if (i + 1 >= a.length || a[i + 1].equalsIgnoreCase("loop")) {
                            throw new IOException("Usage: /mocap playback start <recording|.scene> [<player_name>] skin_from_player <profile> [loop].");
                        }
                        profileName = a[++i];
                    } else if (lower.equals("skin_from_file") || lower.equals("skin_from_mineskin")
                            || lower.equals("from_file") || lower.equals("from_mineskin")) {
                        throw new IOException("This Paper port currently resolves player-name skins only. File/MineSkin skins are not yet implemented.");
                    } else if (displayName == null && !option.equals("-")) {
                        displayName = option;
                    } else {
                        throw new IOException("Unrecognized playback option '" + option + "'. Use a display name, skin_from_player <profile>, or loop.");
                    }
                }
                startPlayback(sender, playable, loop, false, displayName, profileName);
            }
            case "playhere" -> {
                if (a.length < 2) { usage(sender, "/mocap playback playhere <recording|.scene> [loop]"); return; }
                startPlayback(sender, a[1], a.length > 2 && eq(a[2], "loop"), true);
            }
            case "list" -> {
                List<String> active = playbacks.list();
                sender.sendMessage(active.isEmpty() ? "No active Mocap playbacks." : "Active Mocap playbacks (" + active.size() + "):");
                active.forEach(sender::sendMessage);
                sender.sendMessage("Active mannequin actors: " + playbacks.activeActors() + "/" + PaperPlaybackManager.MAX_ACTIVE_MANNEQUINS);
            }
            case "stop" -> {
                if (a.length < 2) { usage(sender, "/mocap playback stop <id>"); return; }
                sender.sendMessage(playbacks.stop(a[1]) ? "Stopped Mocap playback " + a[1] + "." : "Playback ID not found.");
            }
            case "stopall", "stop_all" -> sender.sendMessage("Stopped " + playbacks.stopAll() + " Mocap playback(s).");
            case "freeze", "unfreeze" -> {
                if (a.length < 2) { usage(sender, "/mocap playback " + action + " <id>"); return; }
                sender.sendMessage(playbacks.freeze(a[1], eq(action, "freeze")) ? (eq(action, "freeze") ? "Playback frozen." : "Playback resumed.") : "Playback ID not found.");
            }
            case "end_freeze", "end-freeze" -> {
                if (a.length < 2) { usage(sender, "/mocap playback end-freeze <id>"); return; }
                sender.sendMessage(playbacks.endFreeze(a[1]) ? "Playback will hold its final pose at the end." : "Playback ID not found.");
            }
            case "low_lag", "low-lag" -> {
                if (a.length < 3) { usage(sender, "/mocap playback low-lag <id> <on|off>"); return; }
                sender.sendMessage(playbacks.lowLag(a[1], parseToggle(a[2])) ? "Low-lag mode updated." : "Playback ID not found.");
            }
            case "nametags" -> {
                if (a.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /mocap playback nametags <playback-id> <hide|show>");
                    sender.sendMessage(ChatColor.GRAY + "Alternative: /mocap playback nametags <hide|show> <playback-id>");
                    return;
                }
                String playbackId;
                String visibilityAction;
                if (a[1].equalsIgnoreCase("hide") || a[1].equalsIgnoreCase("show")) {
                    visibilityAction = a[1].toLowerCase(Locale.ROOT);
                    playbackId = a[2];
                } else {
                    playbackId = a[1];
                    visibilityAction = a[2].toLowerCase(Locale.ROOT);
                }
                boolean hidden = switch (visibilityAction) {
                    case "hide" -> true; case "show" -> false;
                    default -> throw new IllegalArgumentException("Use hide or show.");
                };
                sender.sendMessage(playbacks.nameTags(playbackId, hidden)
                        ? ChatColor.GREEN + "Name tags " + visibilityAction + " for playback " + playbackId + "."
                        : ChatColor.RED + "Playback ID " + playbackId + " was not found. Run /mocap playback list.");
            }
            case "modifiers" -> playbackModifiersHelp(sender, a);
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown playback action: " + action);
                playbackHelp(sender);
            }
        }
    }

    private void playbackModifiersHelp(CommandSender sender, String[] args) {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "PLAYBACK MODIFIERS");
            sender.sendMessage(ChatColor.WHITE + "/mocap playback modifiers <list|reset|set|add_to>");
            sender.sendMessage(ChatColor.GRAY + "Paper does not yet implement upstream's per-executor modifier context. Store per-actor modifiers in /mocap scenes modify instead.");
            return;
        }
        sender.sendMessage(ChatColor.RED + "Dynamic playback modifier context is not implemented in the Paper port yet. Use /mocap scenes modify <scene> <element> <property> <values...>.");
    }

    private static void playbackHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "           MOCAP PLAYBACK");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback start <recording|.scene> [<player_name>] [skin_from_player <profile>] [loop]");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <list|stop <id>|stopall|freeze <id>|unfreeze <id>|end-freeze <id>>");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <low-lag <id> <on|off>|nametags <id> <hide|show>|playhere <recording|.scene> [loop]>");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback modifiers <list|reset|set|add_to>");
        sender.sendMessage(ChatColor.GRAY + "This is the single playback command group; the former /mocap playing root has been consolidated here.");
        sender.sendMessage(ChatColor.GRAY + "The optional player_name changes display name; skin_from_player <profile> changes the name-based skin; loop keeps the playback repeating.");
        sender.sendMessage(ChatColor.YELLOW + "ID GUIDE");
        sender.sendMessage(ChatColor.GRAY + "The number offered after stop/freeze/nametags is the active playback ID—not a player name or actor index.");
        sender.sendMessage(ChatColor.GRAY + "Use /mocap playback list; each line starts with the playback ID and then shows the scene, actor count, and tick.");
        sender.sendMessage(ChatColor.GRAY + "For equipment apply-element, actor-index is a separate one-based number within that playback.");
        sender.sendMessage(ChatColor.GRAY + "Paper playback uses Mannequin actors; unsupported original player/packet actions remain unavailable.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void settingsCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            settingsHelp(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            if (args.length == 2) {
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP SETTINGS GROUPS");
                sender.sendMessage(ChatColor.WHITE + "recording, playback, advanced");
                sender.sendMessage(ChatColor.GRAY + "Use /mocap settings <group> to list that group's original field names.");
                return;
            }
            if (args.length == 3) {
                listSettingsGroup(sender, args[2]);
                return;
            }
            usage(sender, "/mocap settings list [<recording|playback|advanced>]");
            return;
        }
        if (action.equals("info")) {
            if (args.length == 2) { settingsHelp(sender); return; }
            if (args.length == 3) { listSettingsGroup(sender, args[2]); return; }
            if (args.length == 4) { showSettingInfo(sender, args[2], args[3]); return; }
            usage(sender, "/mocap settings info <group> [<setting>]");
            return;
        }
        if (!settings.groups().contains(action)) {
            sender.sendMessage(ChatColor.RED + "Unknown settings group: " + args[1]);
            settingsHelp(sender);
            return;
        }
        if (args.length == 2) {
            listSettingsGroup(sender, action);
            return;
        }
        if (args.length == 3) {
            showSettingInfo(sender, action, args[2]);
            return;
        }
        if (args.length == 4) {
            PaperSettingsStore.Definition definition = settings.definition(action, args[2]);
            String oldValue = settings.currentValue(definition);
            String newValue = settings.set(action, args[2], args[3]);
            sender.sendMessage(ChatColor.GREEN + "Updated settings " + action + "." + args[2]
                    + ChatColor.GRAY + ": " + oldValue + ChatColor.WHITE + " -> " + ChatColor.AQUA + newValue);
            sender.sendMessage(ChatColor.GRAY + "Saved to plugins/MocapPaper/settings.yml. New playbacks use the new value.");
            return;
        }
        usage(sender, "/mocap settings <recording|playback|advanced> <setting> [<new_value>]");
    }

    private void listSettingsGroup(CommandSender sender, String group) throws IOException {
        if (!settings.groups().contains(group.toLowerCase(Locale.ROOT))) {
            sender.sendMessage(ChatColor.RED + "Unknown settings group: " + group);
            sender.sendMessage("Available groups: " + String.join(", ", settings.groups()));
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP SETTINGS • " + group.toUpperCase(Locale.ROOT));
        for (PaperSettingsStore.Definition def : settings.fields(group)) {
            String state = def.supported() ? ChatColor.GREEN + "SUPPORTED" : ChatColor.RED + "UPSTREAM-ONLY";
            sender.sendMessage(ChatColor.WHITE + def.name() + ChatColor.GRAY + " = " + settings.currentValue(def)
                    + ChatColor.DARK_GRAY + " [" + state + ChatColor.DARK_GRAY + "]");
        }
        sender.sendMessage(ChatColor.GRAY + "Read a setting: /mocap settings " + group + " <setting>");
        sender.sendMessage(ChatColor.GRAY + "Set a supported setting: /mocap settings " + group + " <setting> <value>");
    }

    private void showSettingInfo(CommandSender sender, String group, String name) {
        PaperSettingsStore.Definition def = settings.definition(group, name);
        if (def == null) {
            sender.sendMessage(ChatColor.RED + "Unknown setting: " + group + "." + name);
            sender.sendMessage("Use /mocap settings " + group + " to list valid names.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "SETTING • " + def.group() + "." + def.name());
        sender.sendMessage(ChatColor.GRAY + "Type: " + ChatColor.WHITE + def.type());
        sender.sendMessage(ChatColor.GRAY + "Default: " + ChatColor.WHITE + def.defaultValue());
        sender.sendMessage(ChatColor.GRAY + "Current: " + (def.supported() ? ChatColor.GREEN : ChatColor.RED) + settings.currentValue(def));
        sender.sendMessage(ChatColor.GRAY + "Description: " + ChatColor.WHITE + def.description());
        if (def.supported()) {
            if (def.type() == PaperSettingsStore.Type.BOOLEAN) {
                sender.sendMessage(ChatColor.GRAY + "Usage: " + ChatColor.WHITE + "/mocap settings " + group + " " + name + " <true|false>");
            } else if (def.type() == PaperSettingsStore.Type.DOUBLE) {
                sender.sendMessage(ChatColor.GRAY + "Usage: " + ChatColor.WHITE + "/mocap settings " + group + " " + name + " <number>");
            }
        } else {
            sender.sendMessage(ChatColor.RED + "This original Mocap setting is not implemented by the Paper port and cannot be changed here.");
        }
    }

    private static void settingsHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "          MOCAP SETTINGS");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings list [<group>]");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings <recording|playback|advanced>");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings <group> <setting>");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings <group> <setting> <value>");
        sender.sendMessage(ChatColor.GRAY + "Original groups/field names are listed. Only fields marked SUPPORTED affect this Paper plugin.");
        sender.sendMessage(ChatColor.GRAY + "Supported now: playback_speed (0.1–4.0), can_push_entities (true/false), invulnerable_playback (true/false).");
        sender.sendMessage(ChatColor.RED + "Other upstream settings are shown but cannot be changed until their behavior is ported.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void customizeCommand(CommandSender sender, String[] args) throws IOException {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (eq(action, "customize")) {
            if (args.length == 1 || eq(args[1], "help")) {
                sender.sendMessage("Customize: names <scene> <group> auto | skins <scene> <group> auto | equipment ...");
                sender.sendMessage("Change one actor's display name: /mocap names modify <scene> <element-index[-name]> <player-name>.");
                sender.sendMessage("Library: /mocap names|skins <groups|save|add|list|remove|import|import-add> ...");
                sender.sendMessage("Equipment: /mocap customize equipment <apply|remove|remove-element|mix|randomize> ...");
                return;
            }
            action = args[1].toLowerCase(Locale.ROOT);
            args = shift(args, 1, action);
        }
        switch (action) {
            case "random_names", "names" -> assignProfiles(sender, args, false);
            case "random_skins", "skins" -> assignProfiles(sender, args, true);
            case "equipment" -> equipmentCommand(sender, args);
            default -> sender.sendMessage("Unknown customize action: " + action);
        }
    }

    private void setSceneElementPlayerName(CommandSender sender, String sceneName, String elementIndex, String playerName) throws IOException {
        // Reuse the scene modifier parser so the TAB-completed 001-recording_name form is valid
        // and the expected element name prevents editing a different element after reorder.
        scenes.modifySceneElement(sceneName, elementIndex, "player_name", new String[]{"set", playerName});
        sender.sendMessage(ChatColor.GREEN + "Saved player name " + playerName + " for scene ."
                + MocapSceneStore.normalizeSceneName(sceneName) + " element " + elementIndex + ".");
    }

    private void profileLibraryCommand(CommandSender sender, String[] args, String kind) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            profileLibraryHelp(sender, kind);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "groups" -> {
                List<String> groups = profileLibraries.listGroups(kind);
                sender.sendMessage(groups.isEmpty()
                        ? ChatColor.YELLOW + "No " + kind + " groups exist. Use /mocap " + kind + " save <group> <subgroup> <comma,separated,names>."
                        : ChatColor.GOLD + kind.toUpperCase(Locale.ROOT) + " GROUPS: " + String.join(", ", groups));
            }
            case "subgroups" -> {
                if (args.length < 3) { usage(sender, "/mocap " + kind + " subgroups <group>"); return; }
                List<String> subgroups = profileLibraries.listSubgroups(kind, args[2]);
                sender.sendMessage(subgroups.isEmpty()
                        ? ChatColor.YELLOW + "No subgroups in " + args[2] + ". Save a list with /mocap " + kind + " save " + args[2] + " <subgroup> <comma,separated,names>."
                        : ChatColor.GOLD + "Subgroups in " + args[2] + ": " + String.join(", ", subgroups));
            }
            case "modify" -> {
                if (!kind.equalsIgnoreCase("names")) {
                    throw new IOException("Single-actor displayed-name changes belong under /mocap names modify; the skins library only manages skin profiles.");
                }
                if (args.length < 5) {
                    usage(sender, "/mocap names modify <scene-name> <element-index[-name]> <new-player-name>");
                    sender.sendMessage(ChatColor.GRAY + "Use /mocap names list <group> page:<page> or /mocap names list <group> <subgroup> <page> to inspect saved profile lists.");
                    return;
                }
                setSceneElementPlayerName(sender, args[2], args[3], args[4]);
            }
            case "list" -> {
                if (args.length < 3) {
                    List<String> groups = profileLibraries.listGroups(kind);
                    sender.sendMessage(groups.isEmpty() ? "No " + kind + " groups saved." : ChatColor.GOLD + kind.toUpperCase(Locale.ROOT) + " GROUPS: " + String.join(", ", groups));
                    return;
                }
                if (args.length > 5) { usage(sender, "/mocap " + kind + " list <group> [<subgroup>] [<page>|page:<page>]"); return; }
                String groupName = args[2];
                String subgroup = null;
                int page = 1;
                if (args.length >= 4) {
                    if (args[3].toLowerCase(Locale.ROOT).startsWith("page:")) {
                        page = parseProfilePage(args[3]);
                    } else {
                        subgroup = args[3];
                    }
                }
                if (args.length >= 5) page = parseProfilePage(args[4]);
                List<ProfileLibraryStore.Entry> entries = subgroup == null
                        ? profileLibraries.list(kind, groupName) : profileLibraries.list(kind, groupName, subgroup);
                if (entries.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "No " + kind + " entries found for " + groupName
                            + (subgroup == null ? "" : "/" + subgroup) + ".");
                    return;
                }
                int pageSize = 60;
                int pages = (entries.size() + pageSize - 1) / pageSize;
                if (page > pages) throw new IOException("Page " + page + " is outside this list; choose page 1–" + pages + ".");
                int start = (page - 1) * pageSize;
                int end = Math.min(entries.size(), start + pageSize);
                sender.sendMessage(ChatColor.GOLD + kind.toUpperCase(Locale.ROOT) + " • " + groupName
                        + (subgroup == null ? " (all subgroups)" : "/" + subgroup)
                        + " • page " + page + "/" + pages + " • entries " + (start + 1) + "–" + end + " of " + entries.size());
                for (int i = start; i < end; i++) {
                    ProfileLibraryStore.Entry entry = entries.get(i);
                    sender.sendMessage(ChatColor.GRAY + "[" + entry.id() + "] " + ChatColor.WHITE + entry.value());
                }
                if (pages > 1) sender.sendMessage(ChatColor.GRAY + "Next page: /mocap " + kind + " list " + groupName
                        + (subgroup == null ? " page:" : " " + subgroup + " ") + (page + 1) + " (or use page:" + (page + 1) + " when listing all subgroups).");
            }
            case "save", "add" -> {
                if (args.length < 5) {
                    usage(sender, "/mocap " + kind + " " + action + " <group> <subgroup> <comma,separated,official,names>");
                    sender.sendMessage(ChatColor.GRAY + "Paste a large comma-separated list as one message; up to 3,000 unique names per subgroup.");
                    return;
                }
                String raw = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                List<ProfileLibraryStore.Entry> changed = action.equals("save")
                        ? profileLibraries.save(kind, args[2], args[3], List.of(raw))
                        : profileLibraries.add(kind, args[2], args[3], List.of(raw));
                reportProfileListChange(sender, kind, args[2], args[3], changed, action.equals("save"));
            }
            case "import", "import-add" -> {
                if (args.length < 5) {
                    usage(sender, "/mocap " + kind + " " + action + " <group> <subgroup> <file-name.txt>");
                    sender.sendMessage(ChatColor.GRAY + "Put the UTF-8 file under plugins/MocapPaper/profile-libraries/imports/. One profile per line or comma-separated.");
                    return;
                }
                List<ProfileLibraryStore.Entry> changed = profileLibraries.importFile(kind, args[2], args[3], args[4], action.equals("import-add"));
                reportProfileListChange(sender, kind, args[2], args[3], changed, !action.equals("import-add"));
            }
            case "remove" -> {
                if (args.length < 5) { usage(sender, "/mocap " + kind + " remove <group> <subgroup> <entry-id-or-exact-name>"); return; }
                boolean removed = profileLibraries.remove(kind, args[2], args[3], args[4]);
                sender.sendMessage(removed
                        ? ChatColor.GREEN + "Removed " + kind + " entry " + args[2] + "/" + args[3] + "/" + args[4] + "."
                        : ChatColor.RED + "No entry ID or exact profile name " + args[4] + " found in " + args[2] + "/" + args[3] + ".");
            }
            case "delete" -> {
                if (args.length < 4) { usage(sender, "/mocap " + kind + " delete <group> <subgroup>"); return; }
                sender.sendMessage(profileLibraries.delete(kind, args[2], args[3])
                        ? ChatColor.GREEN + "Deleted " + kind + " subgroup " + args[2] + "/" + args[3] + "."
                        : ChatColor.RED + "Subgroup not found: " + args[2] + "/" + args[3] + ".");
            }
            case "info" -> {
                if (args.length < 5) { usage(sender, "/mocap " + kind + " info <group> <subgroup> <entry-id>"); return; }
                for (ProfileLibraryStore.Entry entry : profileLibraries.list(kind, args[2], args[3])) {
                    if (entry.id().equals(args[4])) {
                        sender.sendMessage(ChatColor.GOLD + kind.toUpperCase(Locale.ROOT) + " ENTRY");
                        sender.sendMessage(ChatColor.GRAY + "Group: " + ChatColor.WHITE + args[2]);
                        sender.sendMessage(ChatColor.GRAY + "Subgroup: " + ChatColor.WHITE + args[3]);
                        sender.sendMessage(ChatColor.GRAY + "ID: " + ChatColor.AQUA + entry.id());
                        sender.sendMessage(ChatColor.GRAY + "Official profile name: " + ChatColor.WHITE + entry.value());
                        return;
                    }
                }
                sender.sendMessage(ChatColor.RED + "Entry ID not found in that subgroup.");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown " + kind + " action: " + action);
                profileLibraryHelp(sender, kind);
            }
        }
    }

    private static int parseProfilePage(String raw) throws IOException {
        String text = raw == null ? "" : raw.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("page:")) text = text.substring(5);
        try {
            int page = Integer.parseInt(text);
            if (page < 1) throw new IOException("Page numbers start at 1.");
            return page;
        } catch (NumberFormatException e) {
            throw new IOException("Page must be a positive whole number, or use page:<number>.", e);
        }
    }

    private static void reportProfileListChange(CommandSender sender, String kind, String group, String subgroup,
                                                List<ProfileLibraryStore.Entry> changed, boolean replaced) {
        sender.sendMessage(ChatColor.GREEN + (replaced ? "Saved/replaced " : "Added ")
                + changed.size() + " " + kind + " entry/entries in " + group + "/" + subgroup + ".");
        if (!changed.isEmpty()) {
            ProfileLibraryStore.Entry first = changed.get(0);
            ProfileLibraryStore.Entry last = changed.get(changed.size() - 1);
            sender.sendMessage(ChatColor.GRAY + "Entry IDs: " + first.id()
                    + (changed.size() > 1 ? "–" + last.id() : "") + ". Use /mocap " + kind + " list " + group + " " + subgroup + " to review.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "No new entries were needed; duplicate values were skipped.");
        }
    }

    private static void profileLibraryHelp(CommandSender sender, String kind) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "       " + kind.toUpperCase(Locale.ROOT) + " LIBRARY");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " groups");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " subgroups <group>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " list <group> [<subgroup>] [<page>]");
        sender.sendMessage(ChatColor.GRAY + "When listing across all subgroups, use page:<page> in place of <subgroup> (for example: /mocap " + kind + " list Story1 page:2).");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " save <group> <subgroup> <comma,separated,names>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " add <group> <subgroup> <comma,separated,names>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " import <group> <subgroup> <file-name.txt>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " import-add <group> <subgroup> <file-name.txt>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " remove <group> <subgroup> <entry-id-or-exact-name>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " delete <group> <subgroup>");
        sender.sendMessage(ChatColor.WHITE + "/mocap " + kind + " info <group> <subgroup> <entry-id>");
        if (kind.equalsIgnoreCase("names")) sender.sendMessage(ChatColor.WHITE + "/mocap names modify <scene> <element-index[-name]> <new-player-name>");
        sender.sendMessage(ChatColor.GRAY + "Group = a broad collection/category (for example, season1). Subgroup = a named list inside it (for example, heroes or villains).");
        sender.sendMessage(ChatColor.GRAY + "Names and skins are separate libraries because display-name changes and skin-profile selection are different operations. Up to 3,000 unique names per subgroup.");
        sender.sendMessage(ChatColor.GRAY + "For thousands of names, import UTF-8 .txt files from plugins/MocapPaper/profile-libraries/imports/ (512 KiB max).");
        sender.sendMessage(ChatColor.GRAY + "IDs remain stable when other entries are removed. Names are checked as official 1–16-character profile names.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private List<String> profileLibrarySuggestions(String[] args, String kind) {
        if (args.length == 2) {
            List<String> actions = new ArrayList<>(List.of("help", "groups", "subgroups", "list", "save", "add", "import", "import-add", "remove", "delete", "info"));
            if (kind.equalsIgnoreCase("names")) actions.add("modify");
            return filter(actions, args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("modify")) {
            if (args.length == 3) return sceneNameSuggestions(args[2]);
            if (args.length == 4) return filter(scenes.sceneElementSuggestions(args[2]), args[3]);
            if (args.length == 5) return profileNameSuggestions(args[4]);
            return List.of();
        }
        if (args.length == 3) {
            if (action.equals("groups")) return List.of();
            if (List.of("subgroups", "list", "save", "add", "import", "import-add", "remove", "delete", "info").contains(action)) {
                try {
                    List<String> groups = new ArrayList<>(profileLibraries.listGroups(kind));
                    if (List.of("save", "add").contains(action)) groups.add("<new-group-name>");
                    return filter(groups, args[2]);
                } catch (IOException ignored) { return List.of("<group-name>"); }
            }
        }
        if (args.length == 4) {
            if (action.equals("list")) {
                try {
                    List<String> options = new ArrayList<>(profileLibraries.listSubgroups(kind, args[2]));
                    options.add("page:1");
                    return filter(options, args[3]);
                } catch (IOException ignored) { return List.of("<subgroup-name>", "page:1"); }
            }
            if (List.of("save", "add", "import", "import-add", "remove", "delete", "info").contains(action)) {
                try {
                    List<String> subgroups = new ArrayList<>(profileLibraries.listSubgroups(kind, args[2]));
                    if (List.of("save", "add").contains(action)) subgroups.add("<new-subgroup-name>");
                    return filter(subgroups, args[3]);
                } catch (IOException ignored) { return List.of("<subgroup-name>"); }
            }
        }
        if (args.length == 5 && action.equals("list")) {
            try {
                List<ProfileLibraryStore.Entry> entries = args[3].toLowerCase(Locale.ROOT).startsWith("page:")
                        ? profileLibraries.list(kind, args[2]) : profileLibraries.list(kind, args[2], args[3]);
                int pages = Math.max(1, (entries.size() + 59) / 60);
                List<String> availablePages = new ArrayList<>();
                for (int page = 1; page <= pages; page++) availablePages.add(Integer.toString(page));
                return filter(availablePages, args[4]);
            } catch (IOException ignored) { return List.of("1"); }
        }
        if (args.length == 5 && List.of("save", "add").contains(action)) return filterOrHint(List.of(), args[4], "<comma,separated,official-profile-names>");
        if (args.length == 5 && List.of("import", "import-add").contains(action)) return filterOrHint(List.of(), args[4], "<file-name.txt:put-in-profile-libraries/imports>");
        if (args.length == 5 && List.of("remove", "info").contains(action)) {
            try {
                return filter(profileLibraries.list(kind, args[2], args[3]).stream().map(ProfileLibraryStore.Entry::id).toList(), args[4]);
            } catch (IOException ignored) { return List.of(); }
        }
        if (args.length >= 5 && List.of("save", "add").contains(action)) return filterOrHint(List.of(), args[args.length - 1], "<comma,separated,official-profile-names>");
        if (args.length >= 5 && List.of("import", "import-add").contains(action)) return filterOrHint(List.of(), args[args.length - 1], "<file-name.txt:put-in-profile-libraries/imports>");
        return List.of();
    }

    private void assignProfiles(CommandSender sender, String[] args, boolean skins) throws IOException {
        String kind = skins ? "skins" : "names";
        if (args.length < 3) {
            usage(sender, "/mocap customize " + kind + " <scene> <group> <auto|comma,separated,official,names>");
            return;
        }

        List<String> tail = Arrays.stream(Arrays.copyOfRange(args, 2, args.length)).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String first = tail.isEmpty() ? "" : tail.get(0);
        String last = tail.isEmpty() ? "" : tail.get(tail.size() - 1);
        boolean auto = List.of("auto", "random", "recorded").contains(last.toLowerCase(Locale.ROOT));
        List<String> groups = profileLibraries.listGroups(kind);
        boolean namedGroup = !first.contains(",") && groups.stream().anyMatch(g -> g.equalsIgnoreCase(first));
        List<String> values;
        String scopeForMessage = null;

        if (auto && namedGroup && tail.size() >= 2) {
            String group = first;
            String scope = tail.size() >= 3 ? tail.get(1) : null;
            if (scope != null && scope.equalsIgnoreCase("auto")) scope = null;
            if (scope != null && scope.startsWith("subgroup:")) {
                scopeForMessage = scope.substring("subgroup:".length());
                values = profileLibraries.values(kind, group, scopeForMessage);
            } else if (scope != null && scope.contains("/")) {
                String[] parts = scope.split("/", 2);
                if (!parts[0].equalsIgnoreCase(group)) throw new IOException("Subgroup scope must stay inside the selected group: " + group + ".");
                scopeForMessage = parts[1];
                values = profileLibraries.values(kind, parts[0], parts[1]);
            } else if (scope != null && profileLibraries.listSubgroups(kind, group).contains(scope)) {
                scopeForMessage = scope;
                values = profileLibraries.values(kind, group, scope);
            } else {
                values = profileLibraries.values(kind, group);
            }
            if (values.isEmpty()) throw new IOException("No saved " + kind + " profiles in " + group
                    + (scopeForMessage == null ? "" : "/" + scopeForMessage) + ". Add profiles with /mocap " + kind + " add first.");
            sender.sendMessage(ChatColor.GRAY + "Auto pool: " + group + (scopeForMessage == null ? "" : "/" + scopeForMessage)
                    + " (" + values.size() + " saved profiles). Assignment is randomized independently per actor.");
        } else if (auto && tail.size() == 1) {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            for (String group : groups) candidates.addAll(profileLibraries.values(kind, group));
            candidates.addAll(scenes.knownProfiles());
            Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(candidates::add);
            if (candidates.isEmpty()) {
                candidates.add("Steve");
                candidates.add("Alex");
            }
            values = List.copyOf(candidates);
            sender.sendMessage(ChatColor.GRAY + "Auto pool combines saved library profiles, recorded/scene profile names and online players.");
        } else if (namedGroup && tail.size() >= 2) {
            String group = first;
            String raw = String.join(" ", tail.subList(1, tail.size()));
            values = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> available = profileLibraries.values(kind, group);
            for (String value : values) {
                if (available.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
                    throw new IOException("'" + value + "' is not saved in " + kind + " group '" + group
                            + "'. Use /mocap " + kind + " list " + group + " or select auto.");
                }
            }
            sender.sendMessage(ChatColor.GRAY + "Using " + values.size() + " selected profile(s) from " + group + ".");
        } else {
            String raw = String.join(" ", tail);
            values = Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        if (values.isEmpty()) {
            usage(sender, "/mocap customize " + kind + " <scene> <group> <auto|comma,separated,official,names>");
            return;
        }
        scenes.assignRandomProfiles(args[1], values, skins);
        sender.sendMessage(ChatColor.GREEN + "Saved randomized " + (skins ? "skin profiles" : "names")
                + " into scene ." + MocapSceneStore.normalizeSceneName(args[1]) + ". These assignments persist across playback and loops.");
    }

    private void formationCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            formationHelp(sender);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "tool" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Only a player can receive and use the placement tool.");
                    return;
                }
                plugin.givePlacementTool(player);
            }
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Formation Group creation requires the in-game placement tool.");
                    return;
                }
                if (args.length != 8) {
                    usage(sender, "/mocap formation create <group-name> <source-recording> <rows> <columns> <column-gap> <row-gap>");
                    sender.sendMessage(ChatColor.GRAY + "Select a target with /mocap formation tool first. The source-recording is the saved movement of the player you want to clone.");
                    return;
                }
                Location anchor = plugin.placementAnchor(player);
                if (anchor == null) {
                    throw new IOException("No placement anchor is selected. Run /mocap formation tool and right-click the target block first.");
                }
                int rows = parseBoundedInteger(args[4], "rows", 1, MocapSceneStore.MAX_FORMATION_GROUP_ACTORS);
                int columns = parseBoundedInteger(args[5], "columns", 1, MocapSceneStore.MAX_FORMATION_GROUP_ACTORS);
                long actorCount = (long) rows * columns;
                if (actorCount > MocapSceneStore.MAX_FORMATION_GROUP_ACTORS) {
                    throw new IOException("Rows × columns is " + actorCount + "; maximum is "
                            + MocapSceneStore.MAX_FORMATION_GROUP_ACTORS + " playable actors per group. Reduce rows or columns.");
                }
                double columnGap = parseBoundedDecimal(args[6], "column-gap", 0.0, 128.0);
                double rowGap = parseBoundedDecimal(args[7], "row-gap", 0.0, 128.0);
                scenes.createFormationGroup(args[2], args[3], rows, columns, columnGap, rowGap, anchor);
                sender.sendMessage(ChatColor.GREEN + "Created Formation Group " + ChatColor.AQUA + args[2]
                        + ChatColor.GREEN + " and its Scene ." + ChatColor.AQUA + args[2] + ChatColor.GREEN + ".");
                sender.sendMessage(ChatColor.GRAY + "Source recording/player: " + ChatColor.WHITE + args[3]
                        + ChatColor.GRAY + " | Actors: " + ChatColor.AQUA + actorCount
                        + ChatColor.GRAY + " | Rows behind: " + ChatColor.AQUA + rows
                        + ChatColor.GRAY + " | Columns right: " + ChatColor.AQUA + columns
                        + ChatColor.GRAY + " | Side gap: " + ChatColor.AQUA + columnGap
                        + ChatColor.GRAY + " | Front/back gap: " + ChatColor.AQUA + rowGap);
                sender.sendMessage(ChatColor.GRAY + "The first actor is anchored at the selected location. Customize names/skins on scene ."
                        + args[2] + " with /mocap customize names|skins.");
            }
            case "play" -> {
                if (args.length < 3) { usage(sender, "/mocap formation play <group-name> [loop]"); return; }
                String scene = scenes.formationGroupScene(args[2]);
                var info = scenes.formationGroupInfo(args[2]);
                String worldName = info.has("anchor_world") ? info.get("anchor_world").getAsString() : "";
                World targetWorld = worldName == null || worldName.isBlank() ? null : Bukkit.getWorld(worldName);
                if (targetWorld == null) {
                    if (!worldName.isBlank()) throw new IOException("The Formation Group anchor world '" + worldName + "' is not loaded. Load that world before playing this group.");
                    targetWorld = sender instanceof Player p ? p.getWorld() : defaultWorld();
                }
                boolean loop = args.length >= 4 && eq(args[3], "loop");
                String playbackId = playbacks.start("." + scene, targetWorld, loop, null);
                sender.sendMessage(ChatColor.GREEN + "Started Formation Group " + ChatColor.AQUA + args[2]
                        + ChatColor.GREEN + " at its saved anchor. Playback ID: " + ChatColor.YELLOW + playbackId
                        + (loop ? ChatColor.GREEN + " (looping)" : ""));
            }
            case "list" -> {
                List<String> groups = scenes.listFormationGroups();
                sender.sendMessage(groups.isEmpty() ? ChatColor.YELLOW + "No Formation Groups saved. Use /mocap formation create ..."
                        : ChatColor.GOLD + "FORMATION GROUPS (" + groups.size() + "): " + String.join(", ", groups));
            }
            case "info" -> {
                if (args.length < 3) { usage(sender, "/mocap formation info <group-name>"); return; }
                var group = scenes.formationGroupInfo(args[2]);
                sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "FORMATION GROUP • " + args[2]);
                sender.sendMessage(ChatColor.GRAY + "Scene: " + ChatColor.WHITE + "." + group.get("scene_name").getAsString());
                sender.sendMessage(ChatColor.GRAY + "Source recording/player: " + ChatColor.WHITE + group.get("source_recording").getAsString());
                sender.sendMessage(ChatColor.GRAY + "Rows × columns: " + ChatColor.AQUA + group.get("rows").getAsInt() + " × " + group.get("columns").getAsInt());
                sender.sendMessage(ChatColor.GRAY + "Column gap: " + ChatColor.AQUA + group.get("column_gap").getAsDouble()
                        + ChatColor.GRAY + " | Row gap: " + ChatColor.AQUA + group.get("row_gap").getAsDouble());
                sender.sendMessage(ChatColor.GRAY + "Anchor world: " + ChatColor.WHITE + group.get("anchor_world").getAsString()
                        + ChatColor.GRAY + " | Anchor: " + ChatColor.AQUA + group.get("anchor"));
            }
            case "delete" -> {
                if (args.length < 3) { usage(sender, "/mocap formation delete <group-name>"); return; }
                scenes.deleteFormationGroup(args[2]);
                sender.sendMessage(ChatColor.GREEN + "Deleted Formation Group " + args[2] + " and its backing Scene.");
            }
            case "add" -> {
                // Backwards-compatible legacy helper: adds the grid to an existing scene.
                if (args.length != 7 && args.length != 8) {
                    usage(sender, "/mocap formation add <scene-name> <recording-name> <rows> <columns> <spacing>");
                    sender.sendMessage(ChatColor.GRAY + "Legacy equal spacing. For new named groups use /mocap formation create <group> <recording> <rows> <columns> <column-gap> <row-gap>.");
                    return;
                }
                String sceneName = args[2];
                String recordingName = args[3];
                int rows = parseBoundedInteger(args[4], "rows", 1, MocapSceneStore.MAX_FORMATION_ADDITIONS);
                int columns = parseBoundedInteger(args[5], "columns", 1, MocapSceneStore.MAX_FORMATION_ADDITIONS);
                long actorCount = (long) rows * columns;
                if (actorCount > MocapSceneStore.MAX_FORMATION_ADDITIONS) {
                    throw new IOException("Rows × columns is " + actorCount + "; maximum is " + MocapSceneStore.MAX_FORMATION_ADDITIONS + " actors.");
                }
                double columnGap = parseBoundedDecimal(args[6], "column-gap", 0.0, 128.0);
                double rowGap = args.length == 8 ? parseBoundedDecimal(args[7], "row-gap", 0.0, 128.0) : columnGap;
                scenes.addFormation(sceneName, recordingName, rows, columns, columnGap, rowGap);
                sender.sendMessage(ChatColor.GREEN + "Formation grid added to existing scene ." + MocapSceneStore.normalizeSceneName(sceneName) + ".");
                sender.sendMessage(ChatColor.GRAY + "Rows: " + rows + " | Columns: " + columns + " | Side gap: " + columnGap + " | Front/back gap: " + rowGap + " | Actors added: " + actorCount);
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown formation action: " + action);
                formationHelp(sender);
            }
        }
    }

    private static void formationHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "       FORMATION GROUP BUILDER");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation tool");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation create <group-name> <source-recording> <rows> <columns> <column-gap> <row-gap>");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation <list|info <group>|play <group> [loop]|delete <group>>");
        sender.sendMessage(ChatColor.YELLOW + "CREATE WORKFLOW");
        sender.sendMessage(ChatColor.GRAY + "1. Run /mocap formation tool and right-click the block where the first actor should stand.");
        sender.sendMessage(ChatColor.GRAY + "2. Choose a saved recording of the player you want to use as the formation source.");
        sender.sendMessage(ChatColor.GRAY + "3. Create a named group. A separate Formation Group metadata record and a backing Scene with the same name are created automatically.");
        sender.sendMessage(ChatColor.GRAY + "4. The first actor stays at the selected anchor; columns grow to the source actor's right and rows grow behind the source's starting yaw.");
        sender.sendMessage(ChatColor.GRAY + "5. Play later with /mocap formation play <group-name>. Name/skin assignments can be saved on its backing scene using /mocap customize names|skins.");
        sender.sendMessage(ChatColor.YELLOW + "GAPS AND LIMITS");
        sender.sendMessage(ChatColor.GRAY + "column-gap: side-to-side distance. row-gap: front-to-back distance. Both accept 0–128 blocks.");
        sender.sendMessage(ChatColor.GRAY + "Rows × columns are limited to 500 playable actors per saved group (Paper playback cap); legacy additions are limited to 1,000 per call.");
        sender.sendMessage(ChatColor.GRAY + "Legacy: /mocap formation add <scene> <recording> <rows> <columns> <spacing> [row-gap].");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void pasteCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP PLACEMENT");
            sender.sendMessage(ChatColor.WHITE + "/mocap formation tool");
            sender.sendMessage(ChatColor.WHITE + "/mocap paste <recording-name|.scene-name> [loop]");
        sender.sendMessage(ChatColor.WHITE + "/mocap paste <recording-name|.scene-name> <x> <y> <z> [loop]");
            sender.sendMessage(ChatColor.WHITE + "/mocap paste <recording-name|.scene-name> <x> <y> <z> [loop]");
            sender.sendMessage(ChatColor.GRAY + "Click mode: hold the named Blaze Rod, right-click a block, then run paste.");
            sender.sendMessage(ChatColor.GRAY + "Coordinate mode: provide x y z in your current world. The first actor is anchored at that exact position.");
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("loop")) {
            pasteAtAnchor(sender, args[1], true);
            return;
        }
        if (args.length == 2) {
            pasteAtAnchor(sender, args[1], false);
            return;
        }
        if (args.length == 3) {
            throw new IOException("Use /mocap paste <recording|.scene> [loop] or /mocap paste <recording|.scene> <x> <y> <z> [loop].");
        }
        if (args.length != 5 && args.length != 6) {
            usage(sender, "/mocap paste <recording-name|.scene-name> <x> <y> <z> [loop]");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Coordinate paste requires an in-game player so the target world is known.");
            return;
        }
        double x = parseBoundedDecimal(args[2], "x", -30_000_000, 30_000_000);
        double y = parseBoundedDecimal(args[3], "y", player.getWorld().getMinHeight(), player.getWorld().getMaxHeight());
        double z = parseBoundedDecimal(args[4], "z", -30_000_000, 30_000_000);
        boolean loop = args.length == 6 && args[5].equalsIgnoreCase("loop");
        if (args.length == 6 && !loop) throw new IOException("The optional sixth argument must be 'loop'.");
        pasteAtLocation(sender, args[1], loop, new Location(player.getWorld(), x, y, z));
    }

    private void pasteAtAnchor(CommandSender sender, String playableName, boolean loop) throws IOException {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Paste requires a player placement anchor. Run it in-game after selecting the target block.");
            return;
        }
        Location anchor = plugin.placementAnchor(player);
        if (anchor == null) {
            sender.sendMessage(ChatColor.RED + "No placement anchor is set. Run /mocap formation tool, then right-click the target block with the named Blaze Rod.");
            return;
        }
        if (anchor.getWorld() == null) throw new IOException("The saved placement anchor's world is no longer available; select the target block again.");
        pasteAtLocation(sender, playableName, loop, anchor);
    }

    private void pasteAtLocation(CommandSender sender, String playableName, boolean loop, Location anchor) throws IOException {
        if (anchor.getWorld() == null) throw new IOException("The placement anchor's world is no longer available.");
        String id = playbacks.start(playableName, anchor.getWorld(), loop, anchor);
        sender.sendMessage(ChatColor.GREEN + "Pasted " + ChatColor.AQUA + playableName + ChatColor.GREEN
                + " at " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f %.2f %.2f", anchor.getX(), anchor.getY(), anchor.getZ())
                + ChatColor.GREEN + ". Playback ID: " + ChatColor.YELLOW + id
                + (loop ? ChatColor.GREEN + " (looping)" : "") + ChatColor.GRAY + ". The first actor is anchored here; the rest keep their saved offsets.");
    }

    private static int parseBoundedInteger(String raw, String argument, int min, int max) throws IOException {
        final int value;
        try { value = Integer.parseInt(raw); }
        catch (NumberFormatException e) {
            throw new IOException("Replace <" + argument + "> with a whole number from " + min + " to " + max + "; received '" + raw + "'.", e);
        }
        if (value < min || value > max) throw new IOException(argument + " must be between " + min + " and " + max + ".");
        return value;
    }

    private static double parseBoundedDecimal(String raw, String argument, double min, double max) throws IOException {
        final double value;
        try { value = Double.parseDouble(raw); }
        catch (NumberFormatException e) {
            throw new IOException("Replace <" + argument + "> with a number from " + min + " to " + max + "; received '" + raw + "'.", e);
        }
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IOException(argument + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private void equipmentCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "       EQUIPMENT PRESETS");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment save <group-name> <preset-name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment list [<group-name>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment delete <group-name> <preset-name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment apply <playback-id> <group-name> <preset-name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment apply-element <playback-id> <actor-index> <group-name> <preset-name>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment remove <playback-id>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment remove-element <playback-id> <actor-index>");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment mix <playback-id> <group-name> <preset-1> <preset-2> [<preset-3> ...]");
            sender.sendMessage(ChatColor.WHITE + "/mocap customize equipment randomize <playback-id> <group-name>");
            sender.sendMessage(ChatColor.YELLOW + "ARGUMENT GUIDE");
            sender.sendMessage(ChatColor.GRAY + "group-name: preset folder; TAB lists existing groups. Save can create a new group.");
            sender.sendMessage(ChatColor.GRAY + "preset-name: name saved under that group; TAB lists names already in that group.");
            sender.sendMessage(ChatColor.GRAY + "playback-id: first number shown by /mocap playback list.");
            sender.sendMessage(ChatColor.GRAY + "actor-index: one-based actor position (1, 2, 3...) in that playback; TAB lists valid indexes.");
            sender.sendMessage(ChatColor.GRAY + "save records your current main/off hand plus helmet, chestplate, leggings, and boots.");
            sender.sendMessage(ChatColor.GRAY + "remove clears only helmet/chestplate/leggings/boots; hands stay unchanged.");
            sender.sendMessage(ChatColor.GRAY + "mix assigns one randomly selected saved preset from your chosen list to each actor.");
            sender.sendMessage(ChatColor.GRAY + "auto is a separate top completion item for saved name/skin library groups.");
            sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "Only a player can save their current equipment."); return; }
                if (args.length < 4) { usage(sender, "/mocap customize equipment save <group-name> <preset-name>"); return; }
                equipment.saveFrom(player, args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Saved equipment preset " + args[2] + "/" + args[3] + ".");
            }
            case "list" -> {
                if (args.length < 3) {
                    List<String> groups = equipment.listGroups();
                    sender.sendMessage(groups.isEmpty() ? "No equipment groups exist. Save one with /mocap customize equipment save <group-name> <preset-name>."
                            : ChatColor.GOLD + "Equipment groups: " + String.join(", ", groups));
                } else {
                    List<String> presets = equipment.list(args[2]);
                    sender.sendMessage(presets.isEmpty()
                            ? "No saved presets in group " + args[2] + ". Use /mocap customize equipment save " + args[2] + " <preset-name>."
                            : ChatColor.GOLD + "Presets in " + args[2] + ": " + String.join(", ", presets));
                }
            }
            case "delete" -> {
                if (args.length < 4) { usage(sender, "/mocap customize equipment delete <group-name> <preset-name>"); return; }
                sender.sendMessage(equipment.delete(args[2], args[3]) ? ChatColor.GREEN + "Deleted equipment preset " + args[2] + "/" + args[3] + "."
                        : ChatColor.RED + "Equipment preset not found: " + args[2] + "/" + args[3] + ".");
            }
            case "apply" -> {
                if (args.length < 5) { usage(sender, "/mocap customize equipment apply <playback-id> <group-name> <preset-name>"); return; }
                var preset = equipment.get(args[3], args[4]);
                sender.sendMessage(playbacks.applyPreset(args[2], preset)
                        ? ChatColor.GREEN + "Applied " + args[3] + "/" + args[4] + " to playback " + args[2] + "."
                        : ChatColor.RED + "Playback ID " + args[2] + " was not found. Run /mocap playback list to see active IDs.");
            }
            case "apply_element", "apply-element" -> {
                if (args.length < 6) { usage(sender, "/mocap customize equipment apply-element <playback-id> <actor-index> <group-name> <preset-name>"); return; }
                int index = parseBoundedInteger(args[3], "actor-index", 1, MocapSceneStore.MAX_ELEMENTS);
                var preset = equipment.get(args[4], args[5]);
                sender.sendMessage(playbacks.applyPresetToActor(args[2], index, preset)
                        ? ChatColor.GREEN + "Applied " + args[4] + "/" + args[5] + " to actor " + index + " in playback " + args[2] + "."
                        : ChatColor.RED + "Playback ID was not found or actor-index " + index + " is outside this playback's actor count. Run /mocap playback list and use TAB after apply-element.");
            }
            case "remove" -> {
                if (args.length < 3) { usage(sender, "/mocap customize equipment remove <playback-id>"); return; }
                sender.sendMessage(playbacks.removeArmor(args[2])
                        ? ChatColor.GREEN + "Removed armor from every actor in playback " + args[2] + ". Main/off-hand items were preserved."
                        : ChatColor.RED + "Playback ID " + args[2] + " was not found. Run /mocap playback list.");
            }
            case "remove_element", "remove-element" -> {
                if (args.length < 4) { usage(sender, "/mocap customize equipment remove-element <playback-id> <actor-index>"); return; }
                int index = parseBoundedInteger(args[3], "actor-index", 1, MocapSceneStore.MAX_ELEMENTS);
                sender.sendMessage(playbacks.removeArmorFromActor(args[2], index)
                        ? ChatColor.GREEN + "Removed armor from actor " + index + " in playback " + args[2] + ". Main/off-hand items were preserved."
                        : ChatColor.RED + "Playback ID was not found or actor-index " + index + " is outside this playback's actor count.");
            }
            case "mix" -> {
                if (args.length < 6) { usage(sender, "/mocap customize equipment mix <playback-id> <group-name> <preset-1> <preset-2> [<preset-3> ...]"); return; }
                String playbackId = args[2];
                String group = args[3];
                List<String> selected = Arrays.stream(Arrays.copyOfRange(args, 4, args.length)).map(String::trim).filter(s -> !s.isEmpty()).toList();
                sender.sendMessage(playbacks.mixPresets(playbackId, group, selected)
                        ? ChatColor.GREEN + "Mixed " + selected.size() + " selected armor sets across playback " + playbackId + ". Each actor received one randomly selected armor set; hand items were preserved."
                        : ChatColor.RED + "Playback ID " + playbackId + " was not found. Run /mocap playback list.");
            }
            case "randomize" -> {
                if (args.length < 4) { usage(sender, "/mocap customize equipment randomize <playback-id> <group-name>"); return; }
                sender.sendMessage(playbacks.randomizePresets(args[2], args[3])
                        ? ChatColor.GREEN + "Randomized armor sets from group " + args[3] + " across playback " + args[2] + "; held items were preserved."
                        : ChatColor.RED + "Playback ID " + args[2] + " was not found. Run /mocap playback list.");
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown equipment action: " + args[1]);
                sender.sendMessage("Use /mocap customize equipment help.");
            }
        }
    }

    private void startPlayback(CommandSender sender, String target, boolean loop, boolean here) throws IOException {
        startPlayback(sender, target, loop, here, null, null);
    }

    private void startPlayback(CommandSender sender, String target, boolean loop, boolean here,
                               String displayNameOverride, String profileNameOverride) throws IOException {
        World world;
        org.bukkit.Location location = null;
        if (here) {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return; }
            world = player.getWorld();
            location = player.getLocation();
        } else {
            world = sender instanceof Player player ? player.getWorld() : defaultWorld();
        }
        String id = playbacks.start(target, world, loop, location, displayNameOverride, profileNameOverride);
        sender.sendMessage("Mocap playback started: id " + id + " (" + target + ").");
        if (displayNameOverride != null && !displayNameOverride.isBlank()) {
            sender.sendMessage(ChatColor.GRAY + "Display player name: " + displayNameOverride);
        }
    }

    private void listRecordings(CommandSender sender) {
        List<String> names = scenes.listRecordings();
        sender.sendMessage(names.isEmpty() ? "No Mocap recordings found in world/mocap_files/recordings." : "Recordings (" + names.size() + "): " + String.join(", ", names));
    }

    private void listScenes(CommandSender sender) {
        List<String> names = scenes.listScenes();
        sender.sendMessage(names.isEmpty() ? "No Mocap scenes found in world/mocap_files/scenes." : "Scenes (" + names.size() + "): " + String.join(", ", names));
    }

    private World defaultWorld() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No world is loaded.");
        return Bukkit.getWorlds().get(0);
    }

    private static boolean parseToggle(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("Use on or off.");
        };
    }

    private static boolean eq(String a, String b) { return a.equalsIgnoreCase(b); }

    private static String[] shift(String[] args, int count, String first) {
        if (count < 0 || count >= args.length) throw new IllegalArgumentException("Invalid command argument shift.");
        String[] shifted = new String[args.length - count];
        shifted[0] = first;
        if (shifted.length > 1) {
            System.arraycopy(args, count + 1, shifted, 1, shifted.length - 1);
        }
        return shifted;
    }

    private static boolean usage(CommandSender sender, String line) {
        sender.sendMessage("Usage: " + line);
        return true;
    }

    private static void info(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "          ULTRAOP • MOCAP");
        sender.sendMessage(ChatColor.AQUA + "Paper Edition " + ChatColor.GRAY + " | Minecraft 1.21.11");
        sender.sendMessage(ChatColor.GRAY + "Purpose: " + ChatColor.WHITE + "Mocap recording/scene file playback");
        sender.sendMessage(ChatColor.GRAY + "Maintainer: " + ChatColor.YELLOW + "UltraOP");
        sender.sendMessage(ChatColor.GRAY + "Platform: " + ChatColor.WHITE + "Paper Plugin • Java 21");
        sender.sendMessage(ChatColor.GRAY + "Recording capture: " + ChatColor.GREEN + "Position, rotation, supported poses and recorded player name");
        sender.sendMessage(ChatColor.GRAY + "Actors: " + ChatColor.WHITE + "Paper Mannequin API (not fake players)");
        sender.sendMessage(ChatColor.GRAY + "Help: " + ChatColor.GREEN + "/mocap help");
        sender.sendMessage(ChatColor.GRAY + "Limits: " + ChatColor.WHITE + "See PORT_REPORT.md in the source package.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "             ULTRAOP • MOCAP");
        sender.sendMessage(ChatColor.AQUA + "ROOT " + ChatColor.WHITE + "/mocap <group> <action>");
        sender.sendMessage(ChatColor.YELLOW + "RECORDING CAPTURE");
        sender.sendMessage(ChatColor.WHITE + "/mocap recording <start|stop|discard|save|list> ...");
        sender.sendMessage(ChatColor.GRAY + "  Captures player movement. start can accept [player|selector] [instant-save-name] [custom-session-id].");
        sender.sendMessage(ChatColor.GRAY + "  The custom session ID is optional, positive, collision-checked, and only identifies the active/stopped capture session.");
        sender.sendMessage(ChatColor.YELLOW + "SAVED RECORDINGS");
        sender.sendMessage(ChatColor.WHITE + "/mocap recordings <list|info|copy|rename|remove> ...");
        sender.sendMessage(ChatColor.GRAY + "  recording manages capture sessions; recordings manages saved recording files, so these are different operations.");
        sender.sendMessage(ChatColor.YELLOW + "SCENES");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <add|add_to|rename|remove|remove_from|modify|info|list|play> ...");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify <move <scene>|copy <source-scene> <new-scene>>");
        sender.sendMessage(ChatColor.GRAY + "  move requires a target selected with the tagged placement rod; copy preserves the original and can place the copy at the current target.");
        sender.sendMessage(ChatColor.YELLOW + "PLAYBACK");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback start <recording|.scene> [<player_name>] [skin_from_player <profile>] [loop]");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <list|stop|stopall|freeze|unfreeze|end-freeze|low-lag|nametags> ...");
        sender.sendMessage(ChatColor.GRAY + "  /mocap playing was consolidated into playback; use only /mocap playback.");
        sender.sendMessage(ChatColor.YELLOW + "FORMATION GROUPS & PLACEMENT");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation tool");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation create <group> <source-recording> <rows> <columns> <column-gap> <row-gap>");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation <list|info|play|delete> ...");
        sender.sendMessage(ChatColor.WHITE + "/mocap paste <recording|.scene> [loop]  |  /mocap paste <recording|.scene> <x> <y> <z> [loop]");
        sender.sendMessage(ChatColor.GRAY + "  create saves a named group plus backing scene, anchored at the block selected with the placement rod.");
        sender.sendMessage(ChatColor.YELLOW + "PROFILE LIBRARIES & CUSTOMIZATION");
        sender.sendMessage(ChatColor.WHITE + "/mocap names <groups|subgroups|list|save|add|remove|import|delete> ...");
        sender.sendMessage(ChatColor.WHITE + "/mocap skins <groups|subgroups|list|save|add|remove|import|delete> ...");
        sender.sendMessage(ChatColor.GRAY + "  names and skins are separate libraries; each has group categories and subgroups (named lists within a category).");
        sender.sendMessage(ChatColor.WHITE + "/mocap customize <names|skins|name|equipment> ...");
        sender.sendMessage(ChatColor.GRAY + "  customize names/skins assigns saved profiles to a scene; customize name changes one scene actor's displayed name.");
        sender.sendMessage(ChatColor.YELLOW + "SETTINGS");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings <recording|playback|advanced> [<setting> [<value>]]");
        sender.sendMessage(ChatColor.WHITE + "/mocap info");
        sender.sendMessage(ChatColor.GRAY + "Duplicate root aliases removed: use scenes (not scene), playback (not playing), names (not name), and skins (not skin).");
        sender.sendMessage(ChatColor.GRAY + "Management permission: " + ChatColor.YELLOW + "mocap.admin");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return rootSuggestions("");
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) return rootSuggestions(args[0]);

        if (root.equals("recording")) {
            if (args.length == 2) return filter(List.of("help", "start", "stop", "discard", "save", "list"), args[1]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && action.equals("start")) return playerAndSelectorSuggestions(args[2]);
            if (args.length == 4 && action.equals("start")) return filterOrHint(List.of(), args[3], "<instant-save-name:optional>");
            if (args.length == 5 && action.equals("start")) return filterOrHint(List.of(), args[4], "<custom-session-id:optional-positive-integer>");
            if (args.length == 3 && List.of("stop", "discard", "list").contains(action)) return recordingSessionIdSuggestions(args[2]);
            if (args.length == 3 && action.equals("save")) return filterOrHint(List.of(), args[2], "<recording-file-name>");
            if (args.length == 4 && action.equals("save")) return recordingSessionIdSuggestions(args[3]);
            return List.of();
        }

        if (root.equals("recordings")) {
            if (args.length == 2) return filter(List.of("help", "list", "info", "copy", "rename", "remove"), args[1]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && List.of("info", "copy", "rename", "remove", "delete").contains(action)) return recordingNameSuggestions(args[2]);
            if (args.length == 4 && List.of("copy", "rename").contains(action)) return filterOrHint(List.of(), args[3], "<new-recording-name>");
            return List.of();
        }

        if (root.equals("scenes")) return sceneSuggestions(args);
        if (root.equals("playback")) return playbackSuggestions(args);
        if (root.equals("customize")) return customizeSuggestions(args, 1);
        if (root.equals("formation")) {
            if (args.length == 2) return filter(List.of("help", "tool", "create", "list", "info", "play", "delete", "add"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("create")) return filterOrHint(List.of(), args[2], "<new-group-name>");
            if (args.length == 4 && args[1].equalsIgnoreCase("create")) return recordingNameSuggestions(args[3]);
            if (args.length == 5 && args[1].equalsIgnoreCase("create")) return filterOrHint(List.of(), args[4], "<rows:1-1000>");
            if (args.length == 6 && args[1].equalsIgnoreCase("create")) return filterOrHint(List.of(), args[5], "<columns:1-1000>");
            if (args.length == 7 && args[1].equalsIgnoreCase("create")) return filterOrHint(List.of(), args[6], "<column-gap:0-128; side-to-side>");
            if (args.length == 8 && args[1].equalsIgnoreCase("create")) return filterOrHint(List.of(), args[7], "<row-gap:0-128; front-to-back>");
            if (args.length == 3 && List.of("info", "play", "delete").contains(args[1].toLowerCase(Locale.ROOT))) {
                return filter(scenes.listFormationGroups(), args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("play")) return filter(List.of("loop"), args[3]);
            if (args.length == 3 && args[1].equalsIgnoreCase("add")) return sceneNameSuggestions(args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("add")) return recordingNameSuggestions(args[3]);
            if (args.length == 5 && args[1].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[4], "<rows:1-1000>");
            if (args.length == 6 && args[1].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[5], "<columns:1-1000>");
            if (args.length == 7 && args[1].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[6], "<column-gap:0-128>");
            if (args.length == 8 && args[1].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[7], "<row-gap:0-128>");
            return List.of();
        }
        if (root.equals("paste")) {
            if (args.length == 2) return playableNameSuggestions(args[1]);
            if (args.length == 3) return filter(List.of("loop", "<x:-30000000..30000000>"), args[2]);
            if (args.length == 4) return filterOrHint(List.of(), args[3], "<y:world-height>");
            if (args.length == 5) return filterOrHint(List.of(), args[4], "<z:-30000000..30000000>");
            if (args.length == 6) return filter(List.of("loop"), args[5]);
            return List.of();
        }
        if (root.equals("settings")) return settingsSuggestions(args);
        if (root.equals("names")) return profileLibrarySuggestions(args, "names");
        if (root.equals("skins")) return profileLibrarySuggestions(args, "skins");
        return List.of();
    }

    private List<String> settingsSuggestions(String[] args) {
        if (args.length == 2) return filter(List.of("help", "info", "list", "recording", "playback", "advanced"), args[1]);
        if (args.length == 3) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (action.equals("list") || action.equals("info")) return filter(settings.groups(), args[2]);
            if (settings.groups().contains(action)) return filter(settings.fields(action).stream().map(PaperSettingsStore.Definition::name).toList(), args[2]);
            return List.of();
        }
        if (args.length == 4 && settings.groups().contains(args[1].toLowerCase(Locale.ROOT))) {
            PaperSettingsStore.Definition def = settings.definition(args[1], args[2]);
            if (def == null || !def.supported()) return List.of();
            if (def.type() == PaperSettingsStore.Type.BOOLEAN) return filter(List.of("true", "false"), args[3]);
            if (def.type() == PaperSettingsStore.Type.DOUBLE) return filterOrHint(List.of(), args[3], "<number>");
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("info")) {
            return filter(settings.fields(args[2]).stream().map(PaperSettingsStore.Definition::name).toList(), args[3]);
        }
        return List.of();
    }

    private List<String> sceneSuggestions(String[] args) {
        if (args.length == 2) return filter(List.of("help", "add", "create", "add_to", "rename", "remove",
                "remove_from", "modify", "info", "list", "play", "customize", "formation"), args[1]);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("modify")) {
            if (args.length == 3) {
                List<String> choices = new ArrayList<>(filter(List.of("move", "copy"), args[2]));
                choices.addAll(filter(scenes.listScenes(), args[2]));
                return choices.stream().distinct().limit(100).toList();
            }
            if (args.length == 4 && (args[2].equalsIgnoreCase("move") || args[2].equalsIgnoreCase("copy"))) {
                return sceneNameSuggestions(args[3]);
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("move")) {
                return filter(scenes.sceneElementSuggestions(args[3]), args[4]);
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("copy")) {
                return filterOrHint(List.of(), args[4], "<new-scene-name>");
            }
        }
        if (args.length == 3) {
            return switch (action) {
                case "add", "create" -> filterOrHint(List.of(), args[2], "<new-scene-name>");
                case "rename" -> sceneNameSuggestions(args[2]);
                case "remove", "delete", "info", "list", "play", "add_to", "addto", "add-to", "remove_from", "remove-from", "modify" ->
                        sceneNameSuggestions(args[2]);
                case "customize" -> filter(List.of("names", "skins", "name", "equipment"), args[2]);
                case "formation" -> filter(List.of("add"), args[2]);
                default -> List.of();
            };
        }
        if (action.equals("rename")) {
            return args.length == 4 ? filterOrHint(List.of(), args[3], "<new-scene-name>") : List.of();
        }
        if (List.of("remove_from", "remove-from", "modify").contains(action)) {
            if (args.length == 4) return filterOrHint(scenes.sceneElementSuggestions(args[2]), args[3], "<element-index:scene-may-be-empty>");
            if (action.equals("remove_from") || action.equals("remove-from")) return List.of();
            if (args.length == 5) return filter(List.of("subscene_name", "player_name", "player_skin", "time", "transformations"), args[4]);
            if (args.length >= 6) return sceneModifierSuggestions(args);
        }
        if (action.equals("info") || action.equals("list")) {
            if (args.length == 4) return filterOrHint(scenes.sceneElementSuggestions(args[2]), args[3], "<element-index:scene-may-be-empty>");
            return List.of();
        }
        if (List.of("add_to", "addto", "add-to").contains(action)) {
            if (args.length == 4) return playableNameSuggestions(args[3]);
            if (args.length == 5) return filterOrHint(List.of(), args[4], "<wait-on-start-seconds:0-36000; use 0 for no delay>");
            if (args.length == 6) {
                List<String> values = new ArrayList<>(profileNameSuggestions(args[5]));
                values.add("<player-name:optional>");
                return values.stream().distinct().limit(100).toList();
            }
            if (args.length == 7) return filter(List.of("default", "skin_from_player", "skin_from_file", "skin_from_mineskin"), args[6]);
            if (args.length == 8) {
                String type = args[6].toLowerCase(Locale.ROOT);
                if (type.equals("skin_from_player") || type.equals("from_player")) return profileNameSuggestions(args[7]);
                return filter(List.of("<skin_value>"), args[7]);
            }
            return List.of();
        }
        if (action.equals("play")) {
            if (args.length == 4) return playableNameSuggestions(args[3]);
            if (args.length == 5) return filter(List.of("loop"), args[4]);
        }
        if (action.equals("customize")) {
            String[] shifted = shift(args, 1, "customize");
            return customizeSuggestions(shifted, 1);
        }
        if (action.equals("formation")) {
            if (args.length == 4 && args[2].equalsIgnoreCase("add")) return sceneNameSuggestions(args[3]);
            if (args.length == 5 && args[2].equalsIgnoreCase("add")) return recordingNameSuggestions(args[4]);
            if (args.length == 6 && args[2].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[5], "<rows:1-1000>");
            if (args.length == 7 && args[2].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[6], "<columns:1-1000>");
            if (args.length == 8 && args[2].equalsIgnoreCase("add")) return filterOrHint(List.of(), args[7], "<spacing-blocks:0.5-128>");
        }
        return List.of();
    }

    private List<String> sceneModifierSuggestions(String[] args) {
        int valueCount = args.length - 5;
        String property = args[4].toLowerCase(Locale.ROOT);
        if (property.equals("subscene_name")) return args.length == 6
                ? playableNameSuggestions(args[5]) : List.of();
        if (property.equals("player_name")) {
            if (args.length == 6) return filter(List.of("inherited", "blank", "set"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("set")) return profileNameSuggestions(args[6]);
        }
        if (property.equals("player_skin")) {
            if (args.length == 6) return filter(List.of("default", "from_player", "from_file", "from_mineskin"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("from_player")) return profileNameSuggestions(args[6]);
            if (args.length == 7 && args[5].equalsIgnoreCase("from_file")) return filter(List.of("<skin-file-path>"), args[6]);
            if (args.length == 7 && args[5].equalsIgnoreCase("from_mineskin")) return filter(List.of("<mineskin-token-or-id>"), args[6]);
        }
        if (property.equals("time")) {
            if (args.length == 6) return filter(List.of("start_delay", "wait_on_start", "wait_on_end", "wait_for_parent_end", "loop"), args[5]);
            if (args.length == 7 && List.of("wait_for_parent_end", "loop").contains(args[5].toLowerCase(Locale.ROOT))) return filter(List.of("true", "false"), args[6]);
            if (args.length == 7 && List.of("start_delay", "wait_on_start", "wait_on_end").contains(args[5].toLowerCase(Locale.ROOT))) {
                return filter(List.of("<seconds:0-36000>"), args[6]);
            }
        }
        if (property.equals("transformations")) {
            if (args.length == 6) return filter(List.of("offset", "rotation", "mirror", "scale"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("mirror")) return filter(List.of("none", "x", "z", "xz"), args[6]);
            if (args.length == 7 && args[5].equalsIgnoreCase("scale")) return filter(List.of("of_player", "of_scene"), args[6]);
            if (args.length == 8 && args[5].equalsIgnoreCase("scale")) return filter(List.of("<scale:0-100>"), args[7]);
            if (args.length == 7 && args[5].equalsIgnoreCase("rotation")) return filter(List.of("<degrees>"), args[6]);
            if (args.length == 7 && args[5].equalsIgnoreCase("offset")) return filter(List.of("<x-blocks>"), args[6]);
            if (args.length == 8 && args[5].equalsIgnoreCase("offset")) return filter(List.of("<y-blocks>"), args[7]);
            if (args.length == 9 && args[5].equalsIgnoreCase("offset")) return filter(List.of("<z-blocks>"), args[8]);
        }
        return List.of();
    }

    private List<String> playbackSuggestions(String[] args) {
        if (args.length == 2) return filter(List.of("help", "start", "list", "stop", "stopall", "stop_all",
                "freeze", "unfreeze", "end-freeze", "low-lag", "nametags", "playhere", "modifiers"), args[1]);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && List.of("start", "playhere").contains(action)) return playableNameSuggestions(args[2]);
        if (args.length == 3 && List.of("stop", "freeze", "unfreeze", "end-freeze", "low-lag").contains(action)) return playbackIdSuggestions(args[2]);
        if (args.length == 3 && action.equals("nametags")) {
            List<String> values = new ArrayList<>(filter(List.of("hide", "show"), args[2]));
            values.addAll(playbackIdSuggestions(args[2]));
            return values.stream().distinct().limit(100).toList();
        }
        if (args.length == 4 && action.equals("start")) {
            if (args[3].equalsIgnoreCase("skin_from_player") || args[3].equalsIgnoreCase("from_player")) {
                return profileNameSuggestions("");
            }
            List<String> values = new ArrayList<>(filter(List.of("loop", "skin_from_player"), args[3]));
            values.addAll(profileNameSuggestions(args[3]));
            return values.stream().distinct().limit(100).toList();
        }
        if (args.length == 5 && (action.equals("start") || action.equals("play"))) {
            if (args[3].equalsIgnoreCase("skin_from_player") || args[3].equalsIgnoreCase("from_player")) {
                return profileNameSuggestions(args[4]);
            }
            return filter(List.of("skin_from_player", "loop"), args[4]);
        }
        if (args.length == 6 && (action.equals("start") || action.equals("play"))) {
            if (args[4].equalsIgnoreCase("skin_from_player") || args[4].equalsIgnoreCase("from_player")) {
                return filter(List.of("loop"), args[5]);
            }
        }
        if (args.length == 4 && action.equals("low-lag")) return filter(List.of("on", "off"), args[3]);
        if (args.length == 4 && action.equals("nametags")) {
            if (args[2].equalsIgnoreCase("hide") || args[2].equalsIgnoreCase("show")) return playbackIdSuggestions(args[3]);
            return filter(List.of("hide", "show"), args[3]);
        }
        if (action.equals("modifiers") && args.length == 3) return filter(List.of("help", "list", "reset", "set", "add_to"), args[2]);
        if (action.equals("modifiers") && args.length == 4 && args[2].equalsIgnoreCase("add_to")) return filter(scenes.listScenes(), args[3]);
        if (action.equals("modifiers") && args.length == 5 && args[2].equalsIgnoreCase("add_to")) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[4]);
        return List.of();
    }

    private List<String> rootSuggestions(String prefix) {
        return filter(List.of("help", "info", "recording", "recordings", "scenes", "playback", "settings", "customize", "formation", "paste", "names", "skins"), prefix);
    }

    private List<String> playerAndSelectorSuggestions(String prefix) {
        List<String> values = new ArrayList<>(List.of("@a", "@p", "@s"));
        values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        return filter(values, prefix);
    }

    private List<String> profileNameSuggestions(String prefix) {
        List<String> values = new ArrayList<>(List.of("Steve", "Alex"));
        values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        values.addAll(scenes.knownProfiles());
        return commaSeparatedSuggestions(values, prefix);
    }

    private List<String> commaSeparatedSuggestions(List<String> values, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix;
        int comma = prefix.lastIndexOf(',');
        String existing = comma >= 0 ? prefix.substring(0, comma + 1) : "";
        String partial = comma >= 0 ? prefix.substring(comma + 1).trim() : prefix.trim();
        return filter(values, partial).stream().map(value -> existing + value).distinct().toList();
    }

    private List<String> filterOrHint(List<String> values, String prefix, String hint) {
        List<String> matches = filter(values, prefix == null ? "" : prefix);
        if (!matches.isEmpty()) return matches;
        return prefix == null || prefix.isBlank() ? List.of(hint) : List.of();
    }

    private List<String> recordingNameSuggestions(String prefix) {
        return filterOrHint(scenes.listRecordings(), prefix, "<recording-name:save-a-recording-first>");
    }

    private List<String> sceneNameSuggestions(String prefix) {
        return filterOrHint(scenes.listScenes(), prefix, "<scene-name:create-a-scene-first>");
    }

    private List<String> playableNameSuggestions(String prefix) {
        return filterOrHint(combine(scenes.listRecordings(), scenes.listScenes()), prefix,
                "<recording-name-or-.scene:save-or-create-first>");
    }

    private static List<String> combine(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return values;
    }

    private List<String> recordingSessionIdSuggestions(String prefix) {
        return filterOrHint(recordings.availableIds(), prefix, "<recording-id:use-/mocap-recording-list>");
    }

    private List<String> playbackIds() {
        return playbacks.list().stream().map(s -> s.split(" ")[0]).toList();
    }

    private List<String> playbackIdSuggestions(String prefix) {
        List<String> ids = filter(playbackIds(), prefix);
        if (!ids.isEmpty()) return ids;
        if (prefix == null || prefix.isBlank()) return List.of("<playback-id:see-/mocap-playback-list>");
        return List.of();
    }

    private List<String> combineAndFilter(List<String> first, List<String> second, String prefix) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return filter(values, prefix);
    }

    private List<String> customizeSuggestions(String[] args, int base) {
        int rel = args.length - base;
        if (rel == 1) return filter(List.of("names", "skins", "equipment"), args[base]);
        String action = args[base].toLowerCase(Locale.ROOT);

        if (action.equals("equipment")) {
            if (rel == 2) {
                return filter(List.of("help", "save", "list", "delete", "apply", "apply-element", "remove", "remove-element", "mix", "randomize"), args[base + 1]);
            }
            if (rel < 3) return List.of();
            String sub = args[base + 1].toLowerCase(Locale.ROOT);
            String prefix = args[args.length - 1];

            // /mocap customize equipment <action> <next argument>
            if (rel == 3) {
                return switch (sub) {
                    case "save" -> equipmentGroupSuggestions(prefix, true);
                    case "list", "delete" -> equipmentGroupSuggestions(prefix, false);
                    case "apply", "apply-element", "remove", "remove-element", "mix", "randomize" -> playbackIdSuggestions(prefix);
                    default -> List.of();
                };
            }

            // After apply-element's playback ID, the next argument is the one-based actor index.
            if (rel == 4) {
                return switch (sub) {
                    case "save" -> equipmentPresetSuggestions(args[base + 2], prefix, true);
                    case "delete" -> equipmentPresetSuggestions(args[base + 2], prefix, false);
                    case "apply" -> equipmentGroupSuggestions(prefix, false);
                    case "apply-element" -> {
                        List<String> indexes = playbacks.actorIndexes(args[base + 2]);
                        yield indexes.isEmpty() && (prefix == null || prefix.isBlank())
                                ? List.of("<actor-index:use-playback-list>") : filter(indexes, prefix);
                    }
                    case "randomize", "mix" -> equipmentGroupSuggestions(prefix, false);
                    case "remove-element" -> {
                        List<String> indexes = playbacks.actorIndexes(args[base + 2]);
                        yield indexes.isEmpty() && (prefix == null || prefix.isBlank())
                                ? List.of("<actor-index:use-playback-list>") : filter(indexes, prefix);
                    }
                    default -> List.of();
                };
            }

            if (rel == 5) {
                return switch (sub) {
                    case "apply" -> equipmentPresetSuggestions(args[base + 3], prefix, false);
                    case "apply-element" -> equipmentGroupSuggestions(prefix, false);
                    case "mix" -> equipmentPresetSuggestions(args[base + 3], prefix, false);
                    default -> List.of();
                };
            }

            if (rel >= 6 && sub.equals("mix")) {
                return equipmentPresetSuggestions(args[base + 3], prefix, false);
            }
            if (rel == 6 && sub.equals("apply-element")) {
                return equipmentPresetSuggestions(args[base + 4], prefix, false);
            }
            return List.of();
        }

        if (rel == 2 && List.of("names", "skins").contains(action)) {
            return sceneNameSuggestions(args[base + 1]);
        }
        if (rel == 3 && List.of("names", "skins").contains(action)) {
            String kind = action;
            List<String> options = new ArrayList<>();
            options.add("auto");
            try {
                options.addAll(filter(profileLibraries.listGroups(kind), args[base + 2]));
            } catch (IOException ignored) {
                // Keep TAB completion available even if the profile library cannot be read.
            }
            // Keep the legacy direct-paste mode available without mixing it into official-name suggestions.
            options.add("<comma-separated-profile-names>");
            return filter(options, args[base + 2]).stream().distinct().limit(100).toList();
        }
        if (rel == 4 && List.of("names", "skins").contains(action)) {
            String kind = action;
            String group = args[base + 2];
            List<String> options = new ArrayList<>();
            options.add("auto"); // Always first: it is a mode, not a profile name.
            try {
                if (profileLibraries.listGroups(kind).stream().anyMatch(g -> g.equalsIgnoreCase(group))) {
                    for (String subgroup : profileLibraries.listSubgroups(kind, group)) options.add("subgroup:" + subgroup);
                    options.addAll(profileLibraries.values(kind, group));
                }
            } catch (IOException ignored) {}
            options.add("<comma-separated-profile-names>");
            return filter(options, args[base + 3]).stream().distinct().limit(100).toList();
        }
        if (rel == 5 && List.of("names", "skins").contains(action)) {
            String kind = action;
            String group = args[base + 2];
            String scope = args[base + 3];
            if (scope.startsWith("subgroup:")) return filter(List.of("auto"), args[base + 4]);
            return filter(List.of("auto"), args[base + 4]);
        }
        return List.of();
    }

    private List<String> equipmentGroupSuggestions(String prefix, boolean allowNewGroup) {
        try {
            List<String> matches = new ArrayList<>(filter(equipment.listGroups(), prefix));
            if (allowNewGroup && (prefix == null || prefix.isBlank())) matches.add("<new-group-name>");
            if (allowNewGroup && matches.isEmpty() && "<new-group-name>".startsWith(prefix.toLowerCase(Locale.ROOT))) {
                matches.add("<new-group-name>");
            }
            if (matches.isEmpty() && (prefix == null || prefix.isBlank())) {
                matches.add(allowNewGroup ? "<new-group-name>" : "<no-saved-groups-save-first>");
            }
            return matches.stream().distinct().toList();
        } catch (IOException ignored) {
            return (prefix == null || prefix.isBlank())
                    ? List.of(allowNewGroup ? "<new-group-name>" : "<no-saved-groups-save-first>") : List.of();
        }
    }

    private List<String> equipmentPresetSuggestions(String group, String prefix, boolean allowNewPreset) {
        try {
            List<String> matches = new ArrayList<>(filter(equipment.list(group), prefix));
            if (allowNewPreset && (prefix == null || prefix.isBlank())) matches.add("<preset-name>");
            if (allowNewPreset && matches.isEmpty() && "<preset-name>".startsWith(prefix.toLowerCase(Locale.ROOT))) {
                matches.add("<preset-name>");
            }
            if (matches.isEmpty() && (prefix == null || prefix.isBlank())) {
                matches.add(allowNewPreset ? "<preset-name>" : "<no-presets-save-first>");
            }
            return matches.stream().distinct().toList();
        } catch (IOException ignored) {
            return (prefix == null || prefix.isBlank())
                    ? List.of(allowNewPreset ? "<preset-name>" : "<no-presets-save-first>") : List.of();
        }
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).distinct().limit(100).collect(Collectors.toList());
    }
}
