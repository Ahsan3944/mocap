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
 * Hierarchical command surface for the Paper port.
 *
 * Recording capture is not available in this Paper visual-playback port. The
 * recording group therefore reports that limitation explicitly instead of
 * implying that a recording was started.
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
                case "playback", "play", "playhere", "list", "stop", "stopall", "freeze", "unfreeze",
                     "end_freeze", "low_lag", "nametags" -> playbackCommand(sender, args);
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
            sender.sendMessage("Recordings: list | info <name>");
            sender.sendMessage("Use /mocap recordings list to see saved .mcmocap_rec files.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listRecordings(sender);
            case "info" -> {
                if (args.length < 3) { usage(sender, "/mocap recordings info <name>"); return; }
                var path = scenes.recordingPath(args[2]);
                sender.sendMessage("Recording: " + path.getFileName());
                sender.sendMessage("Size: " + Files.size(path) + " bytes");
            }
            default -> { sender.sendMessage("Unknown recordings action: " + args[1]); sender.sendMessage("Usage: /mocap recordings <list|info>"); }
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
        if (eq(action, "playback")) {
            if (args.length == 1 || eq(args[1], "help")) {
                sender.sendMessage("Playback: start <recording|.scene> [loop] | list | stop <id> | stopall | freeze <id> | unfreeze <id> | end-freeze <id> | low-lag <id> <on|off> | nametags <id> <hide|show>");
                return;
            }
            action = args[1].toLowerCase(Locale.ROOT);
            offset = 1;
            if (eq(action, "start")) action = "play";
        }
        String[] a = offset == 0 ? args : shift(args, 1, action);
        switch (action) {
            case "play" -> {
                if (a.length < 2) { usage(sender, "/mocap playback start <recording|.scene> [loop]"); return; }
                startPlayback(sender, a[1], a.length > 2 && eq(a[2], "loop"), false);
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
                if (a.length < 2) { usage(sender, "/mocap playback stop <id>"); return; }
                sender.sendMessage(playbacks.stop(a[1]) ? "Stopped Mocap playback " + a[1] + "." : "Playback ID not found.");
            }
            case "stopall" -> sender.sendMessage("Stopped " + playbacks.stopAll() + " Mocap playback(s).");
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
            default -> sender.sendMessage("Unknown playback action: " + action);
        }
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
        World world;
        org.bukkit.Location location = null;
        if (here) {
            if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return; }
            world = player.getWorld();
            location = player.getLocation();
        } else {
            world = sender instanceof Player player ? player.getWorld() : defaultWorld();
        }
        String id = playbacks.start(target, world, loop, location);
        sender.sendMessage("Mocap playback started: id " + id + " (" + target + ")");
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
        sender.sendMessage(ChatColor.GRAY + "Recording capture: " + ChatColor.GREEN + "Position/rotation only (Paper limited mode)");
        sender.sendMessage(ChatColor.GRAY + "Actors: " + ChatColor.WHITE + "Paper Mannequin API (not fake players)");
        sender.sendMessage(ChatColor.GRAY + "Help: " + ChatColor.GREEN + "/mocap help");
        sender.sendMessage(ChatColor.GRAY + "Limits: " + ChatColor.WHITE + "See PORT_REPORT.md in the source package.");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "           ULTRAOP MOCAP HELP");
        sender.sendMessage(ChatColor.AQUA + "ROOT " + ChatColor.WHITE + "/mocap <group> <action>");
        sender.sendMessage(ChatColor.YELLOW + "RECORDING");
        sender.sendMessage(ChatColor.WHITE + "/mocap recording <start|stop|save|discard|list>");
        sender.sendMessage(ChatColor.GRAY + "  Captures player position/rotation only; block/chat/inventory actions are not recorded.");
        sender.sendMessage(ChatColor.YELLOW + "SAVED RECORDINGS");
        sender.sendMessage(ChatColor.WHITE + "/mocap recordings <list|info <name>>");
        sender.sendMessage(ChatColor.YELLOW + "SCENES");
        sender.sendMessage(ChatColor.WHITE + "/mocap scene <list|info <name>|copy <source> <target>|play <scene> [loop]>");
        sender.sendMessage(ChatColor.WHITE + "/mocap scene <customize|formation> ...");
        sender.sendMessage(ChatColor.YELLOW + "PLAYBACK");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <start <recording|scene> [loop]|list|stop <id>|stopall>");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <freeze|unfreeze|end-freeze> <id>");
        sender.sendMessage(ChatColor.WHITE + "/mocap playback <low-lag <id> <on|off>|nametags <id> <hide|show>>");
        sender.sendMessage(ChatColor.YELLOW + "CUSTOMIZE");
        sender.sendMessage(ChatColor.WHITE + "/mocap customize <names|skins|name|equipment> ...");
        sender.sendMessage(ChatColor.YELLOW + "OTHER");
        sender.sendMessage(ChatColor.WHITE + "/mocap formation add <scene> <recording> <rows> <columns> <spacing>");
        sender.sendMessage(ChatColor.WHITE + "/mocap copy scene <source> <target>");
        sender.sendMessage(ChatColor.WHITE + "/mocap info");
        sender.sendMessage(ChatColor.GRAY + "Use /mocap <group> help for group-specific usage.");
        sender.sendMessage(ChatColor.GRAY + "Management permission: " + ChatColor.YELLOW + "mocap.admin");
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) return rootSuggestions("");
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) return rootSuggestions(args[0]);

        if (root.equals("recording")) {
            if (args.length == 2) return filter(List.of("help", "start", "stop", "save", "discard", "list"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("start")) return playerAndSelectorSuggestions(args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("start")) return filter(List.of("my_recording", "session_1"), args[3]);
            if (args.length == 3 && List.of("stop", "discard", "list").contains(args[1].toLowerCase(Locale.ROOT))) return filter(recordings.availableIds(), args[2]);
            if (args.length == 3 && args[1].equalsIgnoreCase("save")) return filter(List.of("my_recording", "session_1"), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("save")) return filter(recordings.availableIds(), args[3]);
            return List.of();
        }
        if (root.equals("recordings")) {
            if (args.length == 2) return filter(List.of("help", "list", "info"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("info")) return filter(scenes.listRecordings(), args[2]);
            return List.of();
        }
        if (root.equals("scene") || root.equals("scenes")) {
            if (args.length == 2) return filter(List.of("help", "list", "info", "copy", "customize", "formation", "play"), args[1]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && List.of("info", "play").contains(action)) return filter(scenes.listScenes(), args[2]);
            if (args.length == 3 && action.equals("copy")) return filter(scenes.listScenes(), args[2]);
            if (args.length == 4 && action.equals("copy")) return filter(scenes.listScenes(), args[3]);
            if (args.length == 3 && action.equals("customize")) return filter(List.of("names", "skins", "name", "equipment"), args[2]);
            if (action.equals("customize")) return customizeSuggestions(args, 2);
            if (args.length == 3 && action.equals("formation")) return filter(List.of("add"), args[2]);
            if (args.length == 4 && action.equals("formation") && args[2].equalsIgnoreCase("add")) return filter(scenes.listScenes(), args[3]);
            if (args.length == 5 && action.equals("formation") && args[2].equalsIgnoreCase("add")) return filter(scenes.listRecordings(), args[4]);
            if (args.length == 6 && action.equals("formation") && args[2].equalsIgnoreCase("add")) return filter(List.of("<rows>"), args[5]);
            if (args.length == 7 && action.equals("formation") && args[2].equalsIgnoreCase("add")) return filter(List.of("<columns>"), args[6]);
            if (args.length == 8 && action.equals("formation") && args[2].equalsIgnoreCase("add")) return filter(List.of("<spacing>"), args[7]);
            return List.of();
        }
        if (root.equals("playback")) {
            if (args.length == 2) return filter(List.of("help", "start", "list", "stop", "stopall", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags"), args[1]);
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && action.equals("start")) return combineAndFilter(scenes.listRecordings(), scenes.listScenes(), args[2]);
            if (args.length == 3 && List.of("stop", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags").contains(action)) return filter(playbackIds(), args[2]);
            if (args.length == 4 && action.equals("start")) return filter(List.of("loop"), args[3]);
            if (args.length == 4 && action.equals("low-lag")) return filter(List.of("on", "off"), args[3]);
            if (args.length == 4 && action.equals("nametags")) return filter(List.of("hide", "show"), args[3]);
            return List.of();
        }
        if (root.equals("customize")) return customizeSuggestions(args, 1);
        if (root.equals("formation")) {
            if (args.length == 2) return filter(List.of("help", "add"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("add")) return filter(scenes.listScenes(), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("add")) return filter(scenes.listRecordings(), args[3]);
            if (args.length == 5 && args[1].equalsIgnoreCase("add")) return filter(List.of("<rows>"), args[4]);
            if (args.length == 6 && args[1].equalsIgnoreCase("add")) return filter(List.of("<columns>"), args[5]);
            if (args.length == 7 && args[1].equalsIgnoreCase("add")) return filter(List.of("<spacing>"), args[6]);
            return List.of();
        }
        if (root.equals("copy")) {
            if (args.length == 2) return filter(List.of("help", "scene"), args[1]);
            if (args.length == 3 && args[1].equalsIgnoreCase("scene")) return filter(scenes.listScenes(), args[2]);
            if (args.length == 4 && args[1].equalsIgnoreCase("scene")) return filter(scenes.listScenes(), args[3]);
        }
        return List.of();
    }

    private List<String> rootSuggestions(String prefix) {
        return filter(List.of("help", "info", "recording", "recordings", "scene", "playback", "customize", "formation", "copy"), prefix);
    }

    private List<String> playerAndSelectorSuggestions(String prefix) {
        List<String> values = new ArrayList<>(List.of("@a", "@p", "@s"));
        values.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        return filter(values, prefix);
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
        if (rel == 4 && action.equals("name")) return playerAndSelectorSuggestions(args[base + 3]);
        if (rel == 3 && List.of("names", "skins").contains(action)) return filter(List.of("<comma,separated,values>"), args[base + 2]);
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).distinct().limit(100).collect(Collectors.toList());
    }
}
