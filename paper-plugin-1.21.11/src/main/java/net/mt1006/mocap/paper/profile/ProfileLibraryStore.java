package net.mt1006.mocap.paper.profile;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Persistent, grouped library of Mojang player-profile names for random name/skin assignment.
 * Group and optional subgroup files retain stable entry IDs; removals never renumber other entries.
 */
public final class ProfileLibraryStore {
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,47}");
    private static final Pattern PROFILE = Pattern.compile("[A-Za-z0-9_]{1,16}");
    public static final int MAX_ENTRIES_PER_LIST = 3000;
    private static final long MAX_IMPORT_BYTES = 512 * 1024;
    private static final Pattern SAFE_IMPORT_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\\.txt");

    public record Entry(String id, String value) {}
    private final Path root;
    private final Path importsRoot;

    public ProfileLibraryStore(Path pluginDataFolder) throws IOException {
        root = pluginDataFolder.resolve("profile-libraries").toAbsolutePath().normalize();
        importsRoot = root.resolve("imports").normalize();
        Files.createDirectories(root.resolve("names"));
        Files.createDirectories(root.resolve("skins"));
        Files.createDirectories(importsRoot);
    }

    public List<String> listGroups(String kind) throws IOException {
        Path kindRoot = kindRoot(kind);
        try (var stream = Files.list(kindRoot)) {
            return stream.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(ProfileLibraryStore::safe).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public List<String> listSubgroups(String kind, String group) throws IOException {
        Path groupRoot = groupRoot(kind, group);
        if (!Files.isDirectory(groupRoot)) return List.of();
        try (var stream = Files.list(groupRoot)) {
            return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.yml$", ""))
                    .filter(ProfileLibraryStore::safe).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public List<Entry> list(String kind, String group, String subgroup) throws IOException {
        Path file = file(kind, group, subgroup);
        if (!Files.isRegularFile(file)) return List.of();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) return List.of();
        List<Entry> result = new ArrayList<>();
        for (String id : entries.getKeys(false)) {
            String value = entries.getString(id);
            if (value != null && validProfile(value)) result.add(new Entry(id, value));
        }
        result.sort(Comparator.comparingInt((Entry e) -> parseId(e.id())).thenComparing(Entry::id));
        return List.copyOf(result);
    }

    public List<Entry> save(String kind, String group, String subgroup, Collection<String> rawValues) throws IOException {
        List<String> values = normalizeValues(rawValues);
        Path target = file(kind, group, subgroup);
        Files.createDirectories(target.getParent());
        YamlConfiguration yaml = load(target);
        int nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection old = yaml.getConfigurationSection("entries");
        if (old != null) {
            for (String key : old.getKeys(false)) nextId = Math.max(nextId, parseId(key) + 1);
        }
        yaml.set("entries", null);
        List<Entry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value.toLowerCase(Locale.ROOT))) continue;
            if (result.size() >= MAX_ENTRIES_PER_LIST) throw new IOException("A list can contain at most " + MAX_ENTRIES_PER_LIST + " unique profile names.");
            String id = String.format(Locale.ROOT, "%04d", nextId++);
            yaml.set("entries." + id, value);
            result.add(new Entry(id, value));
        }
        yaml.set("next-id", nextId);
        persist(target, yaml);
        return List.copyOf(result);
    }

    public List<Entry> add(String kind, String group, String subgroup, Collection<String> rawValues) throws IOException {
        List<String> values = normalizeValues(rawValues);
        Path target = file(kind, group, subgroup);
        Files.createDirectories(target.getParent());
        YamlConfiguration yaml = load(target);
        ConfigurationSection section = yaml.getConfigurationSection("entries");
        Map<String, String> existing = new LinkedHashMap<>();
        int nextId = Math.max(1, yaml.getInt("next-id", 1));
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String value = section.getString(id);
                if (value != null) {
                    existing.put(id, value);
                    nextId = Math.max(nextId, parseId(id) + 1);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        existing.values().forEach(v -> seen.add(v.toLowerCase(Locale.ROOT)));
        List<Entry> added = new ArrayList<>();
        for (String value : values) {
            if (!seen.add(value.toLowerCase(Locale.ROOT))) continue;
            if (existing.size() + added.size() >= MAX_ENTRIES_PER_LIST) throw new IOException("A list can contain at most " + MAX_ENTRIES_PER_LIST + " unique profile names.");
            String id = String.format(Locale.ROOT, "%04d", nextId++);
            yaml.set("entries." + id, value);
            added.add(new Entry(id, value));
        }
        yaml.set("next-id", nextId);
        persist(target, yaml);
        return List.copyOf(added);
    }

    /** Import comma- or newline-separated official profile names from a server-side UTF-8 text file. */
    public List<Entry> importFile(String kind, String group, String subgroup, String fileName, boolean append) throws IOException {
        if (fileName == null || !SAFE_IMPORT_FILE.matcher(fileName).matches()) {
            throw new IOException("Import file name must be a simple .txt file name without folders.");
        }
        Path source = importsRoot.resolve(fileName).normalize();
        if (!source.startsWith(importsRoot) || !Files.isRegularFile(source)) {
            throw new IOException("Import file not found. Put the UTF-8 text file under plugins/MocapPaper/profile-libraries/imports/.");
        }
        long size = Files.size(source);
        if (size <= 0 || size > MAX_IMPORT_BYTES) {
            throw new IOException("Import text file must be between 1 byte and 512 KiB.");
        }
        String raw = Files.readString(source, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\\r", ",").replace("\\n", ",");
        return append ? add(kind, group, subgroup, List.of(raw)) : save(kind, group, subgroup, List.of(raw));
    }

    public boolean remove(String kind, String group, String subgroup, String idOrValue) throws IOException {
        Path target = file(kind, group, subgroup);
        if (!Files.isRegularFile(target)) return false;
        YamlConfiguration yaml = load(target);
        ConfigurationSection entries = yaml.getConfigurationSection("entries");
        if (entries == null) return false;
        String match = null;
        if (entries.contains(idOrValue)) match = idOrValue;
        if (match == null) {
            for (String id : entries.getKeys(false)) {
                if (idOrValue.equalsIgnoreCase(entries.getString(id, ""))) { match = id; break; }
            }
        }
        if (match == null) return false;
        yaml.set("entries." + match, null);
        persist(target, yaml);
        return true;
    }

    public boolean delete(String kind, String group, String subgroup) throws IOException {
        Path target = file(kind, group, subgroup);
        return Files.deleteIfExists(target);
    }

    public List<String> values(String kind, String group) throws IOException {
        List<String> result = new ArrayList<>();
        for (String subgroup : listSubgroups(kind, group)) {
            for (Entry entry : list(kind, group, subgroup)) result.add(entry.value());
        }
        return result.stream().distinct().toList();
    }

    public List<String> values(String kind, String group, String subgroup) throws IOException {
        return list(kind, group, subgroup).stream().map(Entry::value).toList();
    }

    public List<Entry> list(String kind, String group) throws IOException {
        List<Entry> result = new ArrayList<>();
        for (String subgroup : listSubgroups(kind, group)) {
            for (Entry entry : list(kind, group, subgroup)) {
                result.add(new Entry(subgroup + "/" + entry.id(), entry.value()));
            }
        }
        return List.copyOf(result);
    }

    private Path kindRoot(String kind) throws IOException {
        if (!List.of("names", "skins").contains(kind == null ? "" : kind.toLowerCase(Locale.ROOT))) {
            throw new IOException("Profile library kind must be names or skins.");
        }
        return root.resolve(kind.toLowerCase(Locale.ROOT)).normalize();
    }

    private Path groupRoot(String kind, String group) throws IOException {
        if (!safe(group)) throw new IOException("Invalid profile library group: " + group);
        Path base = kindRoot(kind);
        Path dir = base.resolve(group).normalize();
        if (!dir.startsWith(base)) throw new IOException("Profile library group path escapes its data folder.");
        return dir;
    }

    private Path file(String kind, String group, String subgroup) throws IOException {
        Path groupRoot = groupRoot(kind, group);
        if (!safe(subgroup)) throw new IOException("Invalid profile library subgroup: " + subgroup);
        Path target = groupRoot.resolve(subgroup + ".yml").normalize();
        if (!target.startsWith(groupRoot)) throw new IOException("Profile library subgroup path escapes its data folder.");
        return target;
    }

    private static YamlConfiguration load(Path path) {
        return YamlConfiguration.loadConfiguration(path.toFile());
    }

    private static void persist(Path target, YamlConfiguration yaml) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            yaml.save(temporary.toFile());
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof IOException io) throw io;
            throw new IOException("Could not save the profile list.", e);
        }
    }

    private static List<String> normalizeValues(Collection<String> rawValues) throws IOException {
        if (rawValues == null) throw new IOException("At least one profile name is required.");
        List<String> result = new ArrayList<>();
        for (String raw : rawValues) {
            if (raw == null) continue;
            for (String candidate : raw.split(",")) {
                String value = candidate.trim();
                if (value.isEmpty()) continue;
                if (!validProfile(value)) throw new IOException("Invalid Mojang profile name '" + value + "'. Use official names containing 1–16 letters, numbers or underscores.");
                result.add(value);
                if (result.size() > MAX_ENTRIES_PER_LIST) throw new IOException("A list can contain at most " + MAX_ENTRIES_PER_LIST + " submitted profile names.");
            }
        }
        if (result.isEmpty()) throw new IOException("No profile names were supplied. Paste names separated by commas.");
        return result;
    }

    private static boolean validProfile(String value) { return value != null && PROFILE.matcher(value).matches(); }
    private static boolean safe(String value) { return value != null && SAFE.matcher(value).matches(); }
    private static int parseId(String id) {
        try { return Integer.parseInt(id); } catch (NumberFormatException ignored) { return 0; }
    }
}
