package net.mt1006.mocap.paper.equipment;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class EquipmentPresetStore {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,47}");
    private final Path root;

    public EquipmentPresetStore(Path pluginDataFolder) throws IOException {
        root = pluginDataFolder.resolve("equipment-presets").toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public void saveFrom(Player player, String group, String name) throws IOException {
        validate(group); validate(name);
        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) throw new IOException("Player equipment is unavailable.");
        Preset preset = new Preset(copy(equipment.getItemInMainHand()), copy(equipment.getItemInOffHand()),
                copy(equipment.getHelmet()), copy(equipment.getChestplate()), copy(equipment.getLeggings()), copy(equipment.getBoots()));
        write(group, name, preset);
    }

    public void write(String group, String name, Preset preset) throws IOException {
        Path target = path(group, name);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("main-hand", preset.mainHand());
        yaml.set("off-hand", preset.offHand());
        yaml.set("helmet", preset.helmet());
        yaml.set("chestplate", preset.chestplate());
        yaml.set("leggings", preset.leggings());
        yaml.set("boots", preset.boots());
        try {
            yaml.save(temporary.toFile());
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof IOException io) throw io;
            throw new IOException("Unable to save equipment preset.", e);
        }
    }

    public Preset get(String group, String name) throws IOException {
        Path file = path(group, name);
        if (!Files.isRegularFile(file)) throw new IOException("Equipment preset not found: " + group + "/" + name);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        return new Preset(item(yaml, "main-hand"), item(yaml, "off-hand"), item(yaml, "helmet"),
                item(yaml, "chestplate"), item(yaml, "leggings"), item(yaml, "boots"));
    }

    public List<String> listGroups() throws IOException {
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(EquipmentPresetStore::safe).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public List<String> list(String group) throws IOException {
        validate(group);
        Path dir = root.resolve(group).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.yml$", ""))
                    .filter(EquipmentPresetStore::safe).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public boolean delete(String group, String name) throws IOException {
        Path file = path(group, name);
        return Files.deleteIfExists(file);
    }

    public Preset random(String group) throws IOException {
        List<String> names = list(group);
        if (names.isEmpty()) throw new IOException("No equipment presets found in group " + group + ".");
        return get(group, names.get(new Random().nextInt(names.size())));
    }

    public static void apply(Mannequin mannequin, Preset preset) {
        EntityEquipment e = mannequin.getEquipment();
        if (e == null) return;
        e.setItemInMainHand(copy(preset.mainHand()));
        e.setItemInOffHand(copy(preset.offHand()));
        e.setHelmet(copy(preset.helmet()));
        e.setChestplate(copy(preset.chestplate()));
        e.setLeggings(copy(preset.leggings()));
        e.setBoots(copy(preset.boots()));
    }

    private Path path(String group, String name) throws IOException {
        validate(group); validate(name);
        Path dir = root.resolve(group).normalize();
        Path target = dir.resolve(name + ".yml").normalize();
        if (!dir.startsWith(root) || !target.startsWith(root)) throw new IOException("Preset path escapes the data folder.");
        return target;
    }

    private static ItemStack item(YamlConfiguration yaml, String key) {
        Object value = yaml.get(key);
        return value instanceof ItemStack stack ? stack.clone() : new ItemStack(Material.AIR);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.getType().isAir() ? new ItemStack(Material.AIR) : stack.clone();
    }

    private static void validate(String name) throws IOException {
        if (!safe(name)) throw new IOException("Invalid preset group/name: " + name);
    }

    private static boolean safe(String name) {
        return name != null && SAFE.matcher(name).matches();
    }

    public record Preset(ItemStack mainHand, ItemStack offHand, ItemStack helmet,
                         ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        public Preset {
            mainHand = copy(mainHand); offHand = copy(offHand); helmet = copy(helmet);
            chestplate = copy(chestplate); leggings = copy(leggings); boots = copy(boots);
        }
        @Override public ItemStack mainHand() { return copy(mainHand); }
        @Override public ItemStack offHand() { return copy(offHand); }
        @Override public ItemStack helmet() { return copy(helmet); }
        @Override public ItemStack chestplate() { return copy(chestplate); }
        @Override public ItemStack leggings() { return copy(leggings); }
        @Override public ItemStack boots() { return copy(boots); }
    }
}