package net.mt1006.mocap.paper.command;

import net.mt1006.mocap.paper.MocapPaperPlugin;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.playback.PaperPlaybackManager;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import net.mt1006.mocap.paper.recording.PaperRecordingManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

    public PaperMocapCommand(MocapPaperPlugin plugin, MocapSceneStore scenes,
                             EquipmentPresetStore equipment, PaperPlaybackManager playbacks,
                             PaperRecordingManager recordings) {
        this.plugin = plugin;
        this.scenes = scenes;
        this.equipment = equipment;
        this.playbacks = playbacks;
        this.recordings = recordings;
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
                case "scene", "scenes" -> sceneCommand(sender, args);
                case "playing", "playback", "play", "playhere", "list", "stop", "stopall", "stop_all", "freeze", "unfreeze",
                     "end_freeze", "low_lag", "nametags", "modifiers" -> playbackCommand(sender, args);
                case "settings" -> settingsCommand(sender, args);
                case "customize", "random_names", "random_skins", "set_name", "equipment" -> customizeCommand(sender, args);
                case "formation" -> formationCommand(sender, args);
                case "copy" -> copyCommand(sender, args);
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
            sender.sendMessage(ChatColor.WHITE + "/mocap recording start [<player|selector>] [<instant-save-name>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording stop [<id>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording save <name> [<id>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap recording discard [<id>] | list [<id>]");
            sender.sendMessage(ChatColor.GRAY + "Paper captures player position and rotation only; block/chat/inventory actions are not recorded.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                String selector = args.length >= 3 && !args[2].isBlank() ? args[2].trim() : null;
                List<Player> targets = resolveRecordingTargets(sender, selector);
                String instantName = args.length >= 4 && !args[3].isBlank() ? args[3].trim() : null;
                int started = 0;
                List<String> errors = new ArrayList<>();
                for (Player target : targets) {
                    String targetName = instantName;
                    if (targetName != null && targets.size() > 1) targetName = targetName + "_" + target.getName();
                    try {
                        String id = recordings.start(target, targetName);
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
            case "copy" -> {
                if (args.length < 4) { usage(sender, "/mocap scenes copy <source> <target>"); return; }
                scenes.copyScene(args[2], args[3]);
                sender.sendMessage(ChatColor.GREEN + "Copied scene " + args[2] + " to ." + MocapSceneStore.normalizeSceneName(args[3]) + ".");
            }
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
                if (args.length < 5) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /mocap scenes modify <scene> <element> <property> <values...>");
                    sender.sendMessage(ChatColor.GRAY + "Properties: subscene_name, player_name, player_skin, time, transformations.");
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
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <copy|rename> <source> <target>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <remove <scene>|remove_from <scene> <element>>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes modify <scene> <element> <property> <values...>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <list [scene]|info <scene> [element]>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes play <recording|.scene> [loop]");
        sender.sendMessage(ChatColor.GRAY + "Modifiers: player_name, player_skin, time, transformations.");
        sender.sendMessage(ChatColor.GRAY + "Use /mocap scenes <action> then press TAB to explore arguments.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void playbackCommand(CommandSender sender, String[] args) throws IOException {
        String action = args[0].toLowerCase(Locale.ROOT);
        int offset = 0;
        boolean upstreamPlayingSyntax = eq(action, "playing");
        if (eq(action, "playing") || eq(action, "playback")) {
            if (args.length == 1 || eq(args[1], "help")) {
                playbackHelp(sender, upstreamPlayingSyntax);
                return;
            }
            action = args[1].toLowerCase(Locale.ROOT);
            offset = 1;
            if (eq(action, "start")) action = "play";
            if (eq(action, "stop_all")) action = "stopall";
        }
        String[] a = offset == 0 ? args : shift(args, 1, action);
        switch (action) {
            case "play" -> {
                if (a.length < 2) {
                    usage(sender, upstreamPlayingSyntax
                            ? "/mocap playing start <recording|.scene> [<player_name>] [<skin_from_player|skin_from_file|skin_from_mineskin> <skin_value>]"
                            : "/mocap playback start <recording|.scene> [loop]");
                    return;
                }
                if (upstreamPlayingSyntax) {
                    String displayName = a.length >= 3 && !a[2].equals("-") ? a[2] : null;
                    String profileName = null;
                    if (a.length >= 4) {
                        String skinMode = a[3].toLowerCase(Locale.ROOT);
                        if (skinMode.equals("skin_from_player") || skinMode.equals("from_player")) {
                            if (a.length < 5) { usage(sender, "/mocap playing start <recording|.scene> [<player_name>] skin_from_player <profile_name>"); return; }
                            profileName = a[4];
                        } else if (skinMode.equals("skin_from_file") || skinMode.equals("skin_from_mineskin")
                                || skinMode.equals("from_file") || skinMode.equals("from_mineskin")) {
                            throw new IOException("This Paper port currently resolves player-name skins only. File/MineSkin skins are not yet implemented.");
                        } else if (!skinMode.equals("default")) {
                            throw new IOException("Unknown skin modifier: " + a[3] + ". Use skin_from_player <profile_name>.");
                        }
                    }
                    startPlayback(sender, a[1], false, false, displayName, profileName);
                } else {
                    startPlayback(sender, a[1], a.length > 2 && eq(a[2], "loop"), false);
                }
            }
            case "playhere" -> {
                if (a.length < 2) { usage(sender, "/mocap playhere <recording|.scene> [loop]"); return; }
                startPlayback(sender, a[1], a.length > 2 && eq(a[2], "loop"), true);
            }
            case "list" -> {
                List<String> active = playbacks.list();
                sender.sendMessage(active.isEmpty() ? "No active Mocap playbacks." : "Active Mocap playbacks (" + active.size() + "):");
                active.forEach(sender::sendMessage);
                sender.sendMessage("Active mannequin actors: " + playbacks.activeActors() + "/" + PaperPlaybackManager.MAX_ACTIVE_MANNEQUINS);
            }
            case "stop" -> {
                if (a.length < 2) { usage(sender, "/mocap playing stop <id>"); return; }
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
                if (a.length < 3) { usage(sender, "/mocap playback nametags <id> <hide|show>"); return; }
                boolean hidden = switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "hide" -> true; case "show" -> false;
                    default -> throw new IllegalArgumentException("Use hide or show.");
                };
                sender.sendMessage(playbacks.nameTags(a[1], hidden) ? "Name tags updated." : "Playback ID not found.");
            }
            case "modifiers" -> playbackModifiersHelp(sender, a);
            default -> sender.sendMessage("Unknown playback action: " + action);
        }
    }

    private void playbackModifiersHelp(CommandSender sender, String[] args) {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "PLAYBACK MODIFIERS");
            sender.sendMessage(ChatColor.WHITE + "/mocap playing modifiers <list|reset|set|add_to>");
            sender.sendMessage(ChatColor.GRAY + "Paper does not yet implement upstream's per-executor modifier context. Store per-actor modifiers in /mocap scenes modify instead.");
            return;
        }
        sender.sendMessage(ChatColor.RED + "Dynamic playback modifier context is not implemented in the Paper port yet. Use /mocap scenes modify <scene> <element> <property> <values...>.");
    }

    private static void playbackHelp(CommandSender sender, boolean upstreamSyntax) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "           MOCAP PLAYBACK");
        if (upstreamSyntax) {
            sender.sendMessage(ChatColor.WHITE + "/mocap playing start <recording|.scene> [<player_name>] [skin_from_player <profile>]");
            sender.sendMessage(ChatColor.WHITE + "/mocap playing stop <id> | stop_all [including_others|excluding_others] | list");
            sender.sendMessage(ChatColor.WHITE + "/mocap playing modifiers <list|reset|set|add_to>");
        } else {
            sender.sendMessage(ChatColor.WHITE + "/mocap playback start <recording|.scene> [loop]");
            sender.sendMessage(ChatColor.WHITE + "/mocap playback <list|stop <id>|stopall|freeze <id>|unfreeze <id>|end-freeze <id>>");
            sender.sendMessage(ChatColor.WHITE + "/mocap playback <low-lag <id> <on|off>|nametags <id> <hide|show>>");
        }
        sender.sendMessage(ChatColor.GRAY + "Paper playback uses Mannequin actors; unsupported original player/packet actions remain unavailable.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private void settingsCommand(CommandSender sender, String[] args) {
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "MOCAP SETTINGS");
        sender.sendMessage(ChatColor.GRAY + "The full upstream setting registry is not ported into this Paper plugin.");
        sender.sendMessage(ChatColor.WHITE + "Current Paper limits: Java 21, movement/rotation/pose recording, max 500 active mannequin actors.");
        sender.sendMessage(ChatColor.GRAY + "Playback controls: /mocap playback low-lag, /mocap playback nametags.");
        sender.sendMessage(ChatColor.GRAY + "Full upstream settings remain available in the original Mocap mod.");
    }

    private void customizeCommand(CommandSender sender, String[] args) throws IOException {
        String action = args[0].toLowerCase(Locale.ROOT);
        if (eq(action, "customize")) {
            if (args.length == 1 || eq(args[1], "help")) {
                sender.sendMessage("Customize: names <scene> <comma-separated-names> | skins <scene> <comma-separated-profile-names> | name <scene> <element-index> <player-name> | equipment ...");
                return;
            }
            action = args[1].toLowerCase(Locale.ROOT);
            args = shift(args, 1, action);
        }
        switch (action) {
            case "random_names", "names" -> assignProfiles(sender, args, false);
            case "random_skins", "skins" -> assignProfiles(sender, args, true);
            case "set_name", "name" -> {
                if (args.length < 4) { usage(sender, "/mocap customize name <scene> <element-index> <player-name>"); return; }
                scenes.setElementPlayerName(args[1], Integer.parseInt(args[2]), args[3]);
                sender.sendMessage("Saved player name for scene ." + MocapSceneStore.normalizeSceneName(args[1]) + " element " + args[2] + ".");
            }
            case "equipment" -> equipmentCommand(sender, args);
            default -> sender.sendMessage("Unknown customize action: " + action);
        }
    }

    private void assignProfiles(CommandSender sender, String[] args, boolean skins) throws IOException {
        if (args.length < 3) {
            usage(sender, "/mocap customize " + (skins ? "skins" : "names") + " <scene> <comma,separated,values>");
            return;
        }
        List<String> values = Arrays.stream(String.join(" ", Arrays.copyOfRange(args, 2, args.length)).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        scenes.assignRandomProfiles(args[1], values, skins);
        sender.sendMessage("Saved randomized " + (skins ? "skin profiles" : "names") + " into scene ." + MocapSceneStore.normalizeSceneName(args[1]) + ".");
    }

    private void formationCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage("Formation: add <scene> <recording> <rows> <columns> <spacing>");
            sender.sendMessage("Example: /mocap formation add battle warrior 5 10 2");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!eq(action, "add")) { sender.sendMessage("Unknown formation action: " + action); return; }
        if (args.length < 7) { usage(sender, "/mocap formation add <scene> <recording> <rows> <columns> <spacing>"); return; }
        int rows = Integer.parseInt(args[4]), columns = Integer.parseInt(args[5]);
        double spacing = Double.parseDouble(args[6]);
        scenes.addFormation(args[2], args[3], rows, columns, spacing);
        sender.sendMessage("Added " + ((long) rows * columns) + " recording actor(s) to scene ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
    }

    private void copyCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage("Copy: scene <source> <target>");
            return;
        }
        if (eq(args[1], "scene")) {
            if (args.length < 4) { usage(sender, "/mocap copy scene <source> <target>"); return; }
            scenes.copyScene(args[2], args[3]);
            sender.sendMessage("Copied scene " + args[2] + " to ." + MocapSceneStore.normalizeSceneName(args[3]) + ".");
        } else {
            sender.sendMessage("Unknown copy action: " + args[1]);
        }
    }

    private void equipmentCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2 || eq(args[1], "help")) {
            sender.sendMessage("/mocap customize equipment save <group> <name> | list [group] | delete <group> <name> | apply <id> <group> <name> | apply-element <id> <actor-index> <group> <name> | randomize <id> <group>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Only a player can save their current equipment."); return; }
                if (args.length < 4) { usage(sender, "/mocap customize equipment save <group> <name>"); return; }
                equipment.saveFrom(player, args[2], args[3]);
                sender.sendMessage("Saved equipment preset " + args[2] + "/" + args[3] + ".");
            }
            case "list" -> {
                if (args.length < 3) sender.sendMessage("Equipment groups: " + String.join(", ", equipment.listGroups()));
                else {
                    List<String> presets = equipment.list(args[2]);
                    sender.sendMessage(presets.isEmpty() ? "No presets in group " + args[2] + "." : "Presets in " + args[2] + ": " + String.join(", ", presets));
                }
            }
            case "delete" -> {
                if (args.length < 4) { usage(sender, "/mocap customize equipment delete <group> <name>"); return; }
                sender.sendMessage(equipment.delete(args[2], args[3]) ? "Equipment preset deleted." : "Equipment preset not found.");
            }
            case "apply" -> {
                if (args.length < 5) { usage(sender, "/mocap customize equipment apply <id> <group> <name>"); return; }
                var preset = equipment.get(args[3], args[4]);
                sender.sendMessage(playbacks.applyPreset(args[2], preset) ? "Equipment preset applied." : "Playback ID not found.");
            }
            case "apply_element", "apply-element" -> {
                if (args.length < 6) { usage(sender, "/mocap customize equipment apply-element <id> <actor-index> <group> <name>"); return; }
                int index = Integer.parseInt(args[3]);
                var preset = equipment.get(args[4], args[5]);
                sender.sendMessage(playbacks.applyPresetToActor(args[2], index, preset) ? "Equipment preset applied to actor " + index + "." : "Playback ID not found or actor index is out of range.");
            }
            case "randomize" -> {
                if (args.length < 4) { usage(sender, "/mocap customize equipment randomize <id> <group>"); return; }
                sender.sendMessage(playbacks.randomizePresets(args[2], args[3]) ? "Randomized equipment presets across playback actors." : "Playback ID not found.");
            }
            default -> sender.sendMessage("Unknown equipment action: " + args[1]);
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
        String[] shifted = new String[args.length - count + 1];
        shifted[0] = first;
        System.arraycopy(args, count + 1, shifted, 1, args.length - count - 1);
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
        sender.sendMessage(ChatColor.YELLOW + "RECORDING");
        sender.sendMessage(ChatColor.WHITE + "/mocap recording <start|stop|discard|save|list>");
        sender.sendMessage(ChatColor.GRAY + "  Captures position, rotation, supported poses and original player name.");
        sender.sendMessage(ChatColor.GRAY + "  Block edits, chat, inventory and combat actions are not recorded by this Paper port.");
        sender.sendMessage(ChatColor.YELLOW + "SAVED RECORDINGS");
        sender.sendMessage(ChatColor.WHITE + "/mocap recordings <list|info|copy|rename|remove> ...");
        sender.sendMessage(ChatColor.YELLOW + "SCENES (UPSTREAM GROUP)");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes add <scene_name>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes add_to <scene> <recording|.nested_scene> [delay] [player_name] [skin_source skin_value]");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes <copy|rename|remove|remove_from|modify|info|list> ...");
        sender.sendMessage(ChatColor.WHITE + "/mocap scenes play <recording|.scene> [loop]");
        sender.sendMessage(ChatColor.YELLOW + "PLAYING");
        sender.sendMessage(ChatColor.WHITE + "/mocap playing start <recording|.scene> [player_name] [skin_from_player <profile>]");
        sender.sendMessage(ChatColor.WHITE + "/mocap playing <stop <id>|stop_all|list>");
        sender.sendMessage(ChatColor.YELLOW + "PAPER PLAYBACK ADD-ONS");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <freeze|unfreeze|end-freeze> <id>");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <low-lag <id> <on|off>|nametags <id> <hide|show>>");
        sender.sendMessage(ChatColor.YELLOW + "CUSTOMIZE / FORMATION");
        sender.sendMessage(ChatColor.WHITE + "/mocap customize <names|skins|name|equipment> ...");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation add <scene> <recording> <rows> <columns> <spacing>");
        sender.sendMessage(ChatColor.WHITE + "/mocap copy scene <source> <target>");
        sender.sendMessage(ChatColor.WHITE + "/mocap settings");
        sender.sendMessage(ChatColor.WHITE + "/mocap info");
        sender.sendMessage(ChatColor.GRAY + "Use TAB or /mocap <group> help for group-specific arguments.");
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
            if (args.length == 4 && action.equals("start")) return filter(List.of("my_recording", "session_1"), args[3]);
            if (args.length == 3 && List.of("stop", "discard", "list").contains(action)) return filter(recordings.availableIds(), args[2]);
            if (args.length == 3 && action.equals("save")) return filter(List.of("my_recording", "session_1"), args[2]);
            if (args.length == 4 && action.equals("save")) return filter(recordings.availableIds(), args[3]);
            return List.of();
        }

        if (root.equals("recordings")) {
            if (args.length == 2) return filter(List.of("help", "list", "info", "copy", "rename", "remove"), args[1]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && List.of("info", "copy", "rename", "remove", "delete").contains(action)) return filter(scenes.listRecordings(), args[2]);
            if (args.length == 4 && List.of("copy", "rename").contains(action)) return filter(List.of("new_recording", "recording_copy"), args[3]);
            return List.of();
        }

        if (root.equals("scene") || root.equals("scenes")) return sceneSuggestions(args);
        if (root.equals("playing") || root.equals("playback")) return playbackSuggestions(args, root.equals("playing"));
        if (root.equals("customize")) return customizeSuggestions(args, 1);
        if (root.equals("formation")) {
            if (args.length == 2) return filter(List.of("help", "add"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("add")) return filter(scenes.listScenes(), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("add")) return filter(scenes.listRecordings(), args[3]);
            if (args.length == 5 && args[1].equalsIgnoreCase("add")) return filter(List.of("5", "3", "1"), args[4]);
            if (args.length == 6 && args[1].equalsIgnoreCase("add")) return filter(List.of("10", "5", "3"), args[5]);
            if (args.length == 7 && args[1].equalsIgnoreCase("add")) return filter(List.of("2", "3", "4"), args[6]);
            return List.of();
        }
        if (root.equals("copy")) {
            if (args.length == 2) return filter(List.of("help", "scene"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("scene")) return filter(scenes.listScenes(), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("scene")) return filter(List.of("new_scene", "scene_copy"), args[3]);
            return List.of();
        }
        if (root.equals("settings")) return args.length == 2 ? filter(List.of("help", "info", "list"), args[1]) : List.of();
        return List.of();
    }

    private List<String> sceneSuggestions(String[] args) {
        if (args.length == 2) return filter(List.of("help", "add", "create", "add_to", "copy", "rename", "remove",
                "remove_from", "modify", "info", "list", "play", "customize", "formation"), args[1]);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3) {
            return switch (action) {
                case "add", "create" -> filter(List.of("new_scene", "scene_1"), args[2]);
                case "copy", "rename" -> filter(scenes.listScenes(), args[2]);
                case "remove", "delete", "info", "list", "play", "add_to", "addto", "add-to", "remove_from", "remove-from", "modify" ->
                        filter(scenes.listScenes(), args[2]);
                case "customize" -> filter(List.of("names", "skins", "name", "equipment"), args[2]);
                case "formation" -> filter(List.of("add"), args[2]);
                default -> List.of();
            };
        }
        if (action.equals("copy") || action.equals("rename")) {
            return args.length == 4 ? filter(List.of("new_scene", "scene_copy"), args[3]) : List.of();
        }
        if (List.of("remove_from", "remove-from", "modify").contains(action)) {
            if (args.length == 4) return filter(scenes.sceneElementSuggestions(args[2]), args[3]);
            if (action.equals("remove_from") || action.equals("remove-from")) return List.of();
            if (args.length == 5) return filter(List.of("subscene_name", "player_name", "player_skin", "time", "transformations"), args[4]);
            if (args.length >= 6) return sceneModifierSuggestions(args);
        }
        if (action.equals("info") || action.equals("list")) {
            if (args.length == 4) return filter(scenes.sceneElementSuggestions(args[2]), args[3]);
            return List.of();
        }
        if (List.of("add_to", "addto", "add-to").contains(action)) {
            if (args.length == 4) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[3]);
            if (args.length == 5) return filter(List.of("0", "1", "2", "5"), args[4]);
            if (args.length == 6) return profileNameSuggestions(args[5]);
            if (args.length == 7) return filter(List.of("default", "skin_from_player", "skin_from_file", "skin_from_mineskin"), args[6]);
            if (args.length == 8) {
                String type = args[6].toLowerCase(Locale.ROOT);
                if (type.equals("skin_from_player") || type.equals("from_player")) return profileNameSuggestions(args[7]);
                return filter(List.of("<skin_value>"), args[7]);
            }
            return List.of();
        }
        if (action.equals("play")) {
            if (args.length == 4) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[3]);
            if (args.length == 5) return filter(List.of("loop"), args[4]);
        }
        if (action.equals("customize")) {
            String[] shifted = shift(args, 1, "customize");
            return customizeSuggestions(shifted, 1);
        }
        if (action.equals("formation")) {
            if (args.length == 4 && args[2].equalsIgnoreCase("add")) return filter(scenes.listScenes(), args[3]);
            if (args.length == 5 && args[2].equalsIgnoreCase("add")) return filter(scenes.listRecordings(), args[4]);
            if (args.length == 6 && args[2].equalsIgnoreCase("add")) return filter(List.of("5", "3", "1"), args[5]);
            if (args.length == 7 && args[2].equalsIgnoreCase("add")) return filter(List.of("10", "5", "3"), args[6]);
            if (args.length == 8 && args[2].equalsIgnoreCase("add")) return filter(List.of("2", "3", "4"), args[7]);
        }
        return List.of();
    }

    private List<String> sceneModifierSuggestions(String[] args) {
        int valueCount = args.length - 5;
        String property = args[4].toLowerCase(Locale.ROOT);
        if (property.equals("subscene_name")) return args.length == 6
                ? combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[5]) : List.of();
        if (property.equals("player_name")) {
            if (args.length == 6) return filter(List.of("inherited", "blank", "set"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("set")) return profileNameSuggestions(args[6]);
        }
        if (property.equals("player_skin")) {
            if (args.length == 6) return filter(List.of("default", "from_player", "from_file", "from_mineskin"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("from_player")) return profileNameSuggestions(args[6]);
        }
        if (property.equals("time")) {
            if (args.length == 6) return filter(List.of("start_delay", "wait_on_start", "wait_on_end", "wait_for_parent_end", "loop"), args[5]);
            if (args.length == 7 && List.of("wait_for_parent_end", "loop").contains(args[5].toLowerCase(Locale.ROOT))) return filter(List.of("true", "false"), args[6]);
            if (args.length == 7 && List.of("start_delay", "wait_on_start", "wait_on_end").contains(args[5].toLowerCase(Locale.ROOT))) return filter(List.of("0", "1", "2", "5"), args[6]);
        }
        if (property.equals("transformations")) {
            if (args.length == 6) return filter(List.of("offset", "rotation", "mirror", "scale"), args[5]);
            if (args.length == 7 && args[5].equalsIgnoreCase("mirror")) return filter(List.of("none", "x", "z", "xz"), args[6]);
            if (args.length == 7 && args[5].equalsIgnoreCase("scale")) return filter(List.of("of_player", "of_scene"), args[6]);
            if (args.length == 8 && args[5].equalsIgnoreCase("scale")) return filter(List.of("0.5", "1", "2"), args[7]);
            if (args.length == 7 && List.of("rotation", "offset").contains(args[5].toLowerCase(Locale.ROOT))) return filter(List.of("0", "90", "180", "270"), args[6]);
            if (args.length >= 7 && args[5].equalsIgnoreCase("offset")) return filter(List.of("0", "1", "-1"), args[args.length - 1]);
        }
        return List.of();
    }

    private List<String> playbackSuggestions(String[] args, boolean upstreamSyntax) {
        if (args.length == 2) {
            return filter(upstreamSyntax
                    ? List.of("help", "start", "stop", "stop_all", "modifiers", "list")
                    : List.of("help", "start", "list", "stop", "stopall", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && action.equals("start")) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[2]);
        if (args.length == 3 && List.of("stop", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags").contains(action)) return filter(playbackIds(), args[2]);
        if (args.length == 3 && action.equals("stop_all")) return filter(List.of("including_others", "excluding_others"), args[2]);
        if (upstreamSyntax && args.length == 4 && action.equals("start")) return profileNameSuggestions(args[3]);
        if (upstreamSyntax && args.length == 5 && action.equals("start")) return filter(List.of("skin_from_player", "skin_from_file", "skin_from_mineskin", "default"), args[4]);
        if (upstreamSyntax && args.length == 6 && action.equals("start")
                && (args[4].equalsIgnoreCase("skin_from_player") || args[4].equalsIgnoreCase("from_player"))) return profileNameSuggestions(args[5]);
        if (args.length == 4 && action.equals("start") && !upstreamSyntax) return filter(List.of("loop"), args[3]);
        if (args.length == 4 && action.equals("low-lag")) return filter(List.of("on", "off"), args[3]);
        if (args.length == 4 && action.equals("nametags")) return filter(List.of("hide", "show"), args[3]);
        if (action.equals("modifiers") && args.length == 3) return filter(List.of("help", "list", "reset", "set", "add_to"), args[2]);
        if (action.equals("modifiers") && args.length == 4 && args[2].equalsIgnoreCase("add_to")) return filter(scenes.listScenes(), args[3]);
        if (action.equals("modifiers") && args.length == 5 && args[2].equalsIgnoreCase("add_to")) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[4]);
        return List.of();
    }

    private List<String> rootSuggestions(String prefix) {
        return filter(List.of("help", "info", "recording", "recordings", "scenes", "scene", "playing", "playback", "settings", "customize", "formation", "copy"), prefix);
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

    private List<String> playbackIds() {
        return playbacks.list().stream().map(s -> s.split(" ")[0]).toList();
    }

    private List<String> combineAndFilter(List<String> first, List<String> second, String prefix) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return filter(values, prefix);
    }

    private List<String> customizeSuggestions(String[] args, int base) {
        int rel = args.length - base;
        if (rel == 1) return filter(List.of("names", "skins", "name", "equipment"), args[base]);
        String action = args[base].toLowerCase(Locale.ROOT);
        if (action.equals("equipment")) {
            if (rel == 2) return filter(List.of("help", "save", "list", "delete", "apply", "apply-element", "randomize"), args[base + 1]);
            if (rel < 3) return List.of();
            String sub = args[base + 1].toLowerCase(Locale.ROOT);
            if (rel == 3 && List.of("apply", "apply-element", "randomize").contains(sub)) return filter(playbackIds(), args[base + 2]);
            if (rel == 3 && List.of("save", "list", "delete").contains(sub)) {
                try { return filter(equipment.listGroups(), args[base + 2]); } catch (IOException ignored) { return List.of(); }
            }
            if (rel == 4 && sub.equals("apply")) {
                try { return filter(equipment.list(args[base + 2]), args[base + 3]); } catch (IOException ignored) { return List.of("<preset-name>"); }
            }
            if (rel == 4 && List.of("save", "delete").contains(sub)) return filter(List.of("<preset-name>"), args[base + 3]);
            if (rel == 4 && sub.equals("randomize")) {
                try { return filter(equipment.listGroups(), args[base + 3]); } catch (IOException ignored) { return List.of(); }
            }
            if (rel == 5 && sub.equals("apply-element")) return filter(List.of("<actor-index>"), args[base + 4]);
            if (rel == 6 && sub.equals("apply-element")) {
                try { return filter(equipment.listGroups(), args[base + 5]); } catch (IOException ignored) { return List.of(); }
            }
            if (rel == 7 && sub.equals("apply-element")) {
                try { return filter(equipment.list(args[base + 5]), args[base + 6]); } catch (IOException ignored) { return List.of("<preset-name>"); }
            }
            return List.of();
        }
        if (rel == 2 && List.of("names", "skins", "name").contains(action)) return filter(scenes.listScenes(), args[base + 1]);
        if (rel == 3 && action.equals("name")) return filter(List.of("<element-index>"), args[base + 2]);
        if (rel == 4 && action.equals("name")) return profileNameSuggestions(args[base + 3]);
        if (rel == 3 && List.of("names", "skins").contains(action)) return profileNameSuggestions(args[base + 2]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).distinct().limit(100).collect(Collectors.toList());
    }
}
