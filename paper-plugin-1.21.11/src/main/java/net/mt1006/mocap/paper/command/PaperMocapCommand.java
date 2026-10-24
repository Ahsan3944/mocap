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
import java.util.*;
import java.util.stream.Collectors;

public final class PaperMocapCommand implements CommandExecutor, TabCompleter {
    private final MocapPaperPlugin plugin;
    private final MocapSceneStore scenes;
    private final EquipmentPresetStore equipment;
    private final PaperPlaybackManager playbacks;

    public PaperMocapCommand(MocapPaperPlugin plugin, MocapSceneStore scenes,
                             EquipmentPresetStore equipment, PaperPlaybackManager playbacks) {
        this.plugin = plugin; this.scenes = scenes; this.equipment = equipment; this.playbacks = playbacks;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(sender); return true; }
        if (!sender.hasPermission("mocap.admin")) { sender.sendMessage("You do not have permission to manage Mocap playbacks."); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "recordings" -> {
                    List<String> names = scenes.listRecordings();
                    sender.sendMessage(names.isEmpty() ? "No Mocap recordings found." : "Recordings: " + String.join(", ", names));
                }
                case "scenes" -> {
                    List<String> names = scenes.listScenes();
                    sender.sendMessage(names.isEmpty() ? "No Mocap scenes found." : "Scenes: " + String.join(", ", names));
                }
                case "play" -> {
                    if (args.length < 2) return usage(sender, "/mocap play <recording|.scene> [loop]");
                    boolean loop = args.length >= 3 && args[2].equalsIgnoreCase("loop");
                    World world = sender instanceof Player player ? player.getWorld() : defaultWorld();
                    String id = playbacks.start(args[1], world, loop, null);
                    sender.sendMessage("Mocap playback started: id " + id + " (" + args[1] + ")");
                }
                case "playhere" -> {
                    if (!(sender instanceof Player player)) { sender.sendMessage("This command can only be used by a player."); return true; }
                    if (args.length < 2) return usage(sender, "/mocap playhere <recording|.scene> [loop]");
                    boolean loop = args.length >= 3 && args[2].equalsIgnoreCase("loop");
                    String id = playbacks.start(args[1], player.getWorld(), loop, player.getLocation());
                    sender.sendMessage("Mocap playback started at your location: id " + id);
                }
                case "list" -> {
                    List<String> active = playbacks.list();
                    sender.sendMessage(active.isEmpty() ? "No active Mocap playbacks." : "Active Mocap playbacks (" + active.size() + "):");
                    active.forEach(sender::sendMessage);
                    sender.sendMessage("Active mannequin actors: " + playbacks.activeActors() + "/" + PaperPlaybackManager.MAX_ACTIVE_MANNEQUINS);
                }
                case "stop" -> {
                    if (args.length < 2) return usage(sender, "/mocap stop <id>");
                    sender.sendMessage(playbacks.stop(args[1]) ? "Stopped Mocap playback " + args[1] + "." : "Playback ID not found.");
                }
                case "stopall" -> sender.sendMessage("Stopped " + playbacks.stopAll() + " Mocap playback(s).");
                case "freeze" -> changeFreeze(sender, args, true);
                case "unfreeze" -> changeFreeze(sender, args, false);
                case "end_freeze" -> {
                    if (args.length < 2) return usage(sender, "/mocap end_freeze <id>");
                    sender.sendMessage(playbacks.endFreeze(args[1]) ? "Playback will hold its final pose at the end." : "Playback ID not found.");
                }
                case "low_lag" -> {
                    if (args.length < 3) return usage(sender, "/mocap low_lag <id> <on|off>");
                    boolean enabled = parseToggle(args[2]);
                    sender.sendMessage(playbacks.lowLag(args[1], enabled) ? "Collision-light mannequin mode " + (enabled ? "enabled." : "disabled.") : "Playback ID not found.");
                }
                case "nametags" -> {
                    if (args.length < 3) return usage(sender, "/mocap nametags <id> <hide|show>");
                    boolean hidden = switch (args[2].toLowerCase(Locale.ROOT)) {
                        case "hide" -> true; case "show" -> false;
                        default -> throw new IOException("Use hide or show.");
                    };
                    sender.sendMessage(playbacks.nameTags(args[1], hidden) ? "Name tags " + (hidden ? "hidden." : "shown.") : "Playback ID not found.");
                }
                case "random_names", "random_skins" -> {
                    if (args.length < 3) return usage(sender, "/mocap " + args[0] + " <scene> <comma,separated,names>");
                    List<String> names = Arrays.stream(String.join(" ", Arrays.copyOfRange(args, 2, args.length)).split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    scenes.assignRandomProfiles(args[1], names, args[0].equalsIgnoreCase("random_skins"));
                    sender.sendMessage("Saved randomized " + (args[0].equalsIgnoreCase("random_skins") ? "skin profiles" : "names")
                            + " into scene ." + MocapSceneStore.normalizeSceneName(args[1]) + ". Existing names/profile modifiers not targeted by this command remain saved.");
                }
                case "set_name" -> {
                    if (args.length < 4) return usage(sender, "/mocap set_name <scene> <element-index> <player-name>");
                    int index = Integer.parseInt(args[2]);
                    scenes.setElementPlayerName(args[1], index, args[3]);
                    sender.sendMessage("Saved player name for scene ." + MocapSceneStore.normalizeSceneName(args[1]) + " element " + index + ".");
                }
                case "formation" -> {
                    if (args.length < 6) return usage(sender, "/mocap formation <scene> <recording> <rows> <columns> <spacing>");
                    int rows = Integer.parseInt(args[3]), columns = Integer.parseInt(args[4]);
                    double spacing = Double.parseDouble(args[5]);
                    scenes.addFormation(args[1], args[2], rows, columns, spacing);
                    sender.sendMessage("Added " + ((long) rows * columns) + " recording actor(s) to scene ." + MocapSceneStore.normalizeSceneName(args[1]) + ".");
                }
                case "copy_scene" -> {
                    if (args.length < 3) return usage(sender, "/mocap copy_scene <source> <target>");
                    scenes.copyScene(args[1], args[2]);
                    sender.sendMessage("Copied scene " + args[1] + " to ." + MocapSceneStore.normalizeSceneName(args[2]) + ".");
                }
                case "equipment" -> equipmentCommand(sender, args);
                default -> { help(sender); }
            }
        } catch (IOException | IllegalArgumentException e) {
            sender.sendMessage("Mocap: " + e.getMessage());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Mocap command failed: " + e.getMessage());
            sender.sendMessage("Mocap command failed safely; check the server log.");
        }
        return true;
    }

    private void equipmentCommand(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) { equipmentHelp(sender); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "save" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Only a player can save their current equipment."); return; }
                if (args.length < 4) { usage(sender, "/mocap equipment save <group> <name>"); return; }
                equipment.saveFrom(player, args[2], args[3]);
                sender.sendMessage("Saved equipment preset " + args[2] + "/" + args[3] + ".");
            }
            case "list" -> {
                if (args.length < 3) { sender.sendMessage("Equipment groups: " + String.join(", ", equipment.listGroups())); return; }
                List<String> presets = equipment.list(args[2]);
                sender.sendMessage(presets.isEmpty() ? "No presets in group " + args[2] + "." : "Presets in " + args[2] + ": " + String.join(", ", presets));
            }
            case "delete" -> {
                if (args.length < 4) { usage(sender, "/mocap equipment delete <group> <name>"); return; }
                sender.sendMessage(equipment.delete(args[2], args[3]) ? "Equipment preset deleted." : "Equipment preset not found.");
            }
            case "apply" -> {
                if (args.length < 5) { usage(sender, "/mocap equipment apply <playback-id> <group> <name>"); return; }
                EquipmentPresetStore.Preset preset = equipment.get(args[3], args[4]);
                sender.sendMessage(playbacks.applyPreset(args[2], preset) ? "Equipment preset applied." : "Playback ID not found.");
            }
            case "apply_element" -> {
                if (args.length < 6) { usage(sender, "/mocap equipment apply_element <playback-id> <actor-index> <group> <name>"); return; }
                int actorIndex = Integer.parseInt(args[3]);
                EquipmentPresetStore.Preset preset = equipment.get(args[4], args[5]);
                sender.sendMessage(playbacks.applyPresetToActor(args[2], actorIndex, preset)
                        ? "Equipment preset applied to actor " + actorIndex + "."
                        : "Playback ID not found or actor index is out of range.");
            }
            case "randomize" -> {
                if (args.length < 4) { usage(sender, "/mocap equipment randomize <playback-id> <group>"); return; }
                sender.sendMessage(playbacks.randomizePresets(args[2], args[3]) ? "Randomized equipment presets across playback actors." : "Playback ID not found.");
            }
            default -> equipmentHelp(sender);
        }
    }

    private void changeFreeze(CommandSender sender, String[] args, boolean frozen) {
        if (args.length < 2) { usage(sender, "/mocap " + (frozen ? "freeze" : "unfreeze") + " <id>"); return; }
        sender.sendMessage(playbacks.freeze(args[1], frozen) ? (frozen ? "Playback frozen." : "Playback resumed.") : "Playback ID not found.");
    }

    private static boolean parseToggle(String value) throws IOException {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IOException("Use on or off.");
        };
    }

    private World defaultWorld() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No world is loaded.");
        return Bukkit.getWorlds().get(0);
    }

    private static boolean usage(CommandSender sender, String line) {
        sender.sendMessage("Usage: " + line);
        return true;
    }

    private static void help(CommandSender sender) {
        sender.sendMessage("Mocap Paper 1.21.11 commands:");
        sender.sendMessage("/mocap recordings | scenes | play <recording|.scene> [loop] | playhere <recording|.scene> [loop]");
        sender.sendMessage("/mocap list | stop <id> | stopall | freeze <id> | unfreeze <id> | end_freeze <id>");
        sender.sendMessage("/mocap low_lag <id> <on|off> | nametags <id> <hide|show>");
        sender.sendMessage("/mocap random_names <scene> <comma,names> | random_skins <scene> <comma,profiles> | set_name <scene> <element-index> <name>");
        sender.sendMessage("/mocap formation <scene> <recording> <rows> <columns> <spacing> | copy_scene <source> <target>");
        equipmentHelp(sender);
        sender.sendMessage("Admin commands require permission mocap.admin. Paper playback uses mannequins, not fake ServerPlayer entities.");
    }

    private static void equipmentHelp(CommandSender sender) {
        sender.sendMessage("/mocap equipment save <group> <name> | list [group] | delete <group> <name>");
        sender.sendMessage("/mocap equipment apply <id> <group> <name> | apply_element <id> <actor-index> <group> <name> | randomize <id> <group>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("help","recordings","scenes","play","playhere","list","stop","stopall","freeze","unfreeze","end_freeze","low_lag","nametags","random_names","random_skins","formation","copy_scene","equipment"), args[0]);
        if (args.length == 2 && List.of("play","playhere").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>(scenes.listRecordings()); names.addAll(scenes.listScenes()); return filter(names,args[1]);
        }
        if (args.length == 2 && List.of("stop","freeze","unfreeze","end_freeze","low_lag","nametags").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(playbacks.list().stream().map(s -> s.split(" ")[0]).toList(), args[1]);
        }
        if (args.length == 2 && List.of("random_names","random_skins","set_name","formation","copy_scene").contains(args[0].toLowerCase(Locale.ROOT))) return filter(scenes.listScenes(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("equipment")) return filter(List.of("save","list","delete","apply","apply_element","randomize"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("formation")) return filter(scenes.listRecordings(), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("equipment") && List.of("list","save","delete").contains(args[1].toLowerCase(Locale.ROOT))) {
            try { return filter(equipment.listGroups(), args[2]); } catch (IOException ignored) { return List.of(); }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("equipment") && List.of("apply","apply_element","randomize").contains(args[1].toLowerCase(Locale.ROOT))) {
            return filter(playbacks.list().stream().map(s -> s.split(" ")[0]).toList(), args[2]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("equipment") && args[1].equalsIgnoreCase("apply")) {
            try { return filter(equipment.list(args[3]), args[4]); } catch (IOException ignored) { return List.of(); }
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("equipment") && args[1].equalsIgnoreCase("apply_element")) {
            try { return filter(equipment.list(args[4]), args[5]); } catch (IOException ignored) { return List.of(); }
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).limit(100).collect(Collectors.toList());
    }
}