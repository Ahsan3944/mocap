package net.mt1006.mocap.paper.command;

import net.mt1006.mocap.paper.MocapPaperPlugin;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.playback.PaperPlaybackManager;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

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

    public PaperMocapCommand(MocapPaperPlugin plugin, MocapSceneStore scenes,
                             EquipmentPresetStore equipment, PaperPlaybackManager playbacks) {
        this.plugin = plugin;
        this.scenes = scenes;
        this.equipment = equipment;
        this.playbacks = playbacks;
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

    private void recordingCommand(CommandSender sender, String[] args) {
        if (args.length == 1 || eq(args[1], "help")) {
            sender.sendMessage("Recording: start | stop | save | discard | list");
            sender.sendMessage("Recording capture is not implemented by the Paper port; use the original Mocap mod to create recordings.");
            return;
        }
        if (eq(args[1], "list")) {
            listRecordings(sender);
            return;
        }
        sender.sendMessage("Recording capture (" + args[1] + ") is not supported by the Paper visual-playback port. Existing recordings can be listed under /mocap recordings list.");
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
            sender.sendMessage("Scene: list | info <name> | copy <source> <target> | customize ... | formation ... | play <name>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> listScenes(sender);
            case "info" -> {
                if (args.length < 3) { usage(sender, "/mocap scene info <name>"); return; }
                var scene = scenes.readScene(args[2]);
                sender.sendMessage("Scene: ." + MocapSceneStore.normalizeSceneName(args[2]));
                sender.sendMessage("Direct elements: " + scene.getAsJsonArray("subscenes").size());
            }
            case "copy" -> {
                if (args.length < 4) { usage(sender, "/mocap scene copy <source> <target>"); return; }
                scenes.copyScene(args[2], args[3]);
                sender.sendMessage("Copied scene " + args[2] + " to ." + MocapSceneStore.normalizeSceneName(args[3]) + ".");
            }
            case "customize" -> customizeCommand(sender, shift(args, 1, "customize"));
            case "formation" -> formationCommand(sender, shift(args, 1, "formation"));
            case "play" -> {
                if (args.length < 3) { usage(sender, "/mocap scene play <scene> [loop]"); return; }
                startPlayback(sender, args[2], args.length > 3 && eq(args[3], "loop"), false);
            }
            default -> { sender.sendMessage("Unknown scene action: " + args[1]); sender.sendMessage("Usage: /mocap scene <list|info|copy|customize|formation|play>"); }
        }
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
        sender.sendMessage("Mocap Paper 1.21.11 visual playback port.");
        sender.sendMessage("Recording capture is not implemented on Paper; use the original Mocap mod to record.");
        sender.sendMessage("Paper actors are Mannequins, not fake ServerPlayer entities. See PORT_REPORT.md for parity limits.");
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("Mocap Paper 1.21.11 — command groups:");
        sender.sendMessage("/mocap recording — recording capability/status");
        sender.sendMessage("/mocap recordings — list and inspect existing recording files");
        sender.sendMessage("/mocap scene — scene list, info, copy, customize, formation, play");
        sender.sendMessage("/mocap playback — start/list/stop/freeze and playback settings");
        sender.sendMessage("/mocap customize — names, skins, equipment presets");
        sender.sendMessage("/mocap formation — create grid formations");
        sender.sendMessage("/mocap copy — copy scenes without overwriting");
        sender.sendMessage("/mocap info");
        sender.sendMessage("Admin actions require mocap.admin. Use /mocap <group> help for details.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("help", "info", "recording", "recordings", "scene", "playback", "customize", "formation", "copy"), args[0]);
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (root) {
                case "recording" -> filter(List.of("help", "start", "stop", "save", "discard", "list"), args[1]);
                case "recordings" -> filter(List.of("help", "list", "info"), args[1]);
                case "scene", "scenes" -> filter(List.of("help", "list", "info", "copy", "customize", "formation", "play"), args[1]);
                case "playback" -> filter(List.of("help", "start", "list", "stop", "stopall", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags"), args[1]);
                case "customize" -> filter(List.of("help", "names", "skins", "name", "equipment"), args[1]);
                case "formation" -> filter(List.of("help", "add"), args[1]);
                case "copy" -> filter(List.of("help", "scene"), args[1]);
                default -> List.of();
            };
        }
        if ((root.equals("recordings") && args.length == 3 && args[1].equalsIgnoreCase("info"))
                || ((root.equals("scene") || root.equals("scenes")) && args.length == 3 && List.of("info", "copy", "play").contains(args[1].toLowerCase(Locale.ROOT)))
                || (root.equals("customize") && args.length == 3 && List.of("names", "skins", "name").contains(args[1].toLowerCase(Locale.ROOT)))
                || (root.equals("copy") && args.length == 3 && args[1].equalsIgnoreCase("scene"))) {
            List<String> values = new ArrayList<>();
            values.addAll(scenes.listScenes());
            values.addAll(scenes.listRecordings());
            return filter(values, args[2]);
        }
        if ((root.equals("scene") || root.equals("scenes")) && args.length == 4 && args[1].equalsIgnoreCase("copy")
                || root.equals("copy") && args.length == 4 && args[1].equalsIgnoreCase("scene")) return filter(scenes.listScenes(), args[3]);
        if (root.equals("scene") && args.length == 3 && args[1].equalsIgnoreCase("formation")) return filter(List.of("add"), args[2]);
        if (root.equals("scene") && args.length == 4 && args[1].equalsIgnoreCase("formation")) return filter(scenes.listRecordings(), args[3]);
        if (root.equals("scene") && args.length == 3 && args[1].equalsIgnoreCase("customize")) return filter(List.of("names", "skins", "name", "equipment"), args[2]);
        if (root.equals("playback")) {
            String action = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3 && action.equals("start")) {
                List<String> values = new ArrayList<>(scenes.listRecordings()); values.addAll(scenes.listScenes());
                return filter(values, args[2]);
            }
            if (args.length == 3 && List.of("stop", "freeze", "unfreeze", "end-freeze", "low-lag", "nametags").contains(action))
                return filter(playbacks.list().stream().map(s -> s.split(" ")[0]).toList(), args[2]);
            if (args.length == 4 && List.of("low-lag", "nametags").contains(action))
                return filter(action.equals("low-lag") ? List.of("on", "off") : List.of("hide", "show"), args[3]);
        }
        if (root.equals("customize") && args.length >= 3 && args[1].equalsIgnoreCase("equipment")) {
            if (args.length == 3) return filter(List.of("save", "list", "delete", "apply", "apply-element", "randomize"), args[2]);
            if (args.length == 4 && List.of("save", "list", "delete").contains(args[2].toLowerCase(Locale.ROOT))) {
                try { return filter(equipment.listGroups(), args[3]); } catch (IOException ignored) { return List.of(); }
            }
            if (args.length == 4 && List.of("apply", "apply-element", "randomize").contains(args[2].toLowerCase(Locale.ROOT)))
                return filter(playbacks.list().stream().map(s -> s.split(" ")[0]).toList(), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).distinct().limit(100).collect(Collectors.toList());
    }
}
