package net.mt1006.mocap.paper.settings;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paper-side settings registry aligned to the original Mocap group/field names.
 * Only fields whose semantics are actually implemented by this Paper port are writable.
 * Other upstream fields remain visible and explicitly marked unsupported; values are not
 * accepted and silently ignored.
 */
public final class PaperSettingsStore {
    public enum Type { BOOLEAN, DOUBLE, ENUM, TEXT }

    public record Definition(String group, String name, Type type, String defaultValue,
                             boolean supported, String description) {}

    private static final List<Definition> DEFINITIONS = List.of(
        d("recording","track_entities",Type.TEXT,"@vehicles;@projectiles;@items",false,"Entity filters are not captured by the Paper recorder."),
        d("recording","prevent_tracking_played_entities",Type.BOOLEAN,"true",false,"The Paper recorder does not track entities."),
        d("recording","entity_tracking_distance",Type.DOUBLE,"128.0",false,"Entity tracking is not implemented in Paper recording."),
        d("recording","on_death",Type.ENUM,"END_RECORDING",false,"Death-triggered capture policy is not implemented."),
        d("recording","on_change_dimension",Type.ENUM,"END_RECORDING",false,"Dimension-change capture policy is not implemented."),
        d("recording","start_instantly",Type.BOOLEAN,"false",false,"Upstream auto-start behavior is not implemented."),
        d("recording","assign_dimension",Type.BOOLEAN,"true",false,"Original dimension metadata assignment is not implemented."),
        d("recording","assign_profile",Type.ENUM,"NO",false,"The Paper recorder stores the real recorded player profile name in the file header."),
        d("recording","chat_recording",Type.BOOLEAN,"false",false,"Chat events are not recorded by the Paper recorder."),

        d("playback","playback_speed",Type.DOUBLE,"1.0",true,"Playback tick multiplier. Supported range: 0.1–4.0."),
        d("playback","play_entities",Type.TEXT,"*",false,"Entity playback is not supported by Paper Mannequin playback."),
        d("playback","can_push_entities",Type.BOOLEAN,"true",true,"Controls whether Paper playback mannequins are collidable."),
        d("playback","entities_after_playback",Type.ENUM,"REMOVE",false,"Entity lifecycle policy is not implemented for mannequin-only playback."),
        d("playback","block_actions_playback",Type.BOOLEAN,"true",false,"Block actions are not recorded or replayed by this Paper port."),
        d("playback","block_initialization",Type.BOOLEAN,"true",false,"Block initialization is not supported."),
        d("playback","block_allow_scaled",Type.BOOLEAN,"false",false,"Block action scaling is not supported."),
        d("playback","drop_from_blocks",Type.BOOLEAN,"false",false,"Block drops are not replayed."),
        d("playback","start_as_recorded",Type.BOOLEAN,"false",false,"Original dimension/start-position modes are not fully ported."),
        d("playback","chat_playback",Type.BOOLEAN,"true",false,"Recorded chat actions are not available in the Paper recorder."),
        d("playback","invulnerable_playback",Type.BOOLEAN,"true",true,"Controls whether Paper playback mannequins are invulnerable."),
        d("playback","dimension_source",Type.ENUM,"ASSIGNED_OR_CURRENT",false,"Original dimension selection is not implemented."),
        d("playback","player_name_handling",Type.ENUM,"IGNORE_CASING",false,"The original profile name conflict policy is not implemented."),
        d("playback","hit_range",Type.DOUBLE,"0.0",false,"Combat/hit playback is not implemented."),

        d("advanced","fluent_movements",Type.DOUBLE,"32.0",false,"Original packet movement smoothing is not used by Paper mannequins."),
        d("advanced","max_float_pos_value",Type.DOUBLE,"1024.0",false,"This original packet-position guard has no equivalent in this Paper path."),
        d("advanced","allow_mineskin_requests",Type.BOOLEAN,"true",false,"MineSkin requests are not implemented in this Paper port."),
        d("advanced","prevent_saving_entities",Type.BOOLEAN,"true",false,"Original entity-save controls are not used."),
        d("advanced","use_creative_game_mode",Type.BOOLEAN,"false",false,"The plugin does not change the recording player's game mode."),
        d("advanced","allow_ghosts",Type.BOOLEAN,"true",false,"Original ghost player entities are not supported."),
        d("advanced","experimental_release_warning",Type.BOOLEAN,"true",false,"This is an original-mod release-warning preference."),
        d("advanced","pretty_scene_files",Type.BOOLEAN,"true",false,"Paper scene JSON is currently always written in pretty format."),
        d("advanced","show_tips",Type.BOOLEAN,"true",false,"Original-mod tips are not implemented."),
        d("advanced","quick_discard",Type.BOOLEAN,"true",false,"The original quick-discard workflow is not implemented."),
        d("advanced","required_extensions",Type.ENUM,"LET_EXTENSION_DECIDE",false,"Original extension loading is not implemented."),
        d("advanced","nbt_recording_mode",Type.ENUM,"FILTERED",false,"Full original NBT recording is not implemented."),
        d("advanced","use_authlib_services",Type.BOOLEAN,"false",false,"The original Authlib services setting is not used by the Paper API profile resolver.")
    );

    private final Path file;
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private YamlConfiguration yaml;

    public PaperSettingsStore(Path pluginDataFolder) throws IOException {
        Files.createDirectories(pluginDataFolder);
        this.file = pluginDataFolder.resolve("settings.yml").toAbsolutePath().normalize();
        for (Definition definition : DEFINITIONS) {
            definitions.put(key(definition.group(), definition.name()), definition);
        }
        yaml = YamlConfiguration.loadConfiguration(file.toFile());
        // Persist defaults for writable settings only; unsupported upstream fields are documentation,
        // not fake config keys that appear to work while doing nothing.
        for (Definition def : DEFINITIONS) {
            if (def.supported() && yaml.get(key(def.group(), def.name())) == null) {
                yaml.set(key(def.group(), def.name()), parseDefault(def));
            }
        }
        save();
    }

    private static Definition d(String group, String name, Type type, String value, boolean supported, String description) {
        return new Definition(group, name, type, value, supported, description);
    }

    public List<String> groups() {
        return List.of("recording", "playback", "advanced");
    }

    public List<Definition> fields(String group) {
        String normalized = group.toLowerCase(Locale.ROOT);
        return DEFINITIONS.stream().filter(def -> def.group().equals(normalized)).toList();
    }

    public Definition definition(String group, String name) {
        if (group == null || name == null) return null;
        return definitions.get(key(group, name));
    }

    public String currentValue(Definition def) {
        if (def == null) return "";
        if (!def.supported()) return "UNSUPPORTED in Paper";
        Object value = yaml.get(key(def.group(), def.name()), parseDefault(def));
        if (value instanceof Double d) return Double.toString(d);
        return String.valueOf(value);
    }

    public boolean getBoolean(String group, String name) {
        Definition def = definition(group, name);
        if (def == null || !def.supported() || def.type() != Type.BOOLEAN) {
            throw new IllegalArgumentException("Setting is not a supported Paper boolean: " + group + "." + name);
        }
        return yaml.getBoolean(key(group, name), Boolean.parseBoolean(def.defaultValue()));
    }

    public double getDouble(String group, String name) {
        Definition def = definition(group, name);
        if (def == null || !def.supported() || def.type() != Type.DOUBLE) {
            throw new IllegalArgumentException("Setting is not a supported Paper number: " + group + "." + name);
        }
        return yaml.getDouble(key(group, name), Double.parseDouble(def.defaultValue()));
    }

    public String set(String group, String name, String rawValue) throws IOException {
        Definition def = definition(group, name);
        if (def == null) throw new IOException("Unknown original Mocap setting: " + group + "." + name + ".");
        if (!def.supported()) {
            throw new IOException(group + "." + name + " is an upstream setting but is not implemented by the Paper port. It was not changed.");
        }
        Object value;
        switch (def.type()) {
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(rawValue) && !"false".equalsIgnoreCase(rawValue)) {
                    throw new IOException("Expected true or false for " + name + ".");
                }
                value = Boolean.parseBoolean(rawValue);
            }
            case DOUBLE -> {
                final double number;
                try { number = Double.parseDouble(rawValue); }
                catch (NumberFormatException e) { throw new IOException("Expected a number for " + name + ".", e); }
                if (!Double.isFinite(number)) throw new IOException(name + " must be a finite number.");
                if (name.equals("playback_speed") && (number < 0.1 || number > 4.0)) {
                    throw new IOException("playback_speed must be between 0.1 and 4.0.");
                }
                value = number;
            }
            default -> throw new IOException("This Paper setting type is not writable.");
        }
        yaml.set(key(group, name), value);
        save();
        return String.valueOf(value);
    }

    private void save() throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            yaml.save(temp.toFile());
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            try { Files.deleteIfExists(temp); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            if (e instanceof IOException io) throw io;
            throw new IOException("Could not persist Paper settings.", e);
        }
    }

    private static Object parseDefault(Definition def) {
        return switch (def.type()) {
            case BOOLEAN -> Boolean.parseBoolean(def.defaultValue());
            case DOUBLE -> Double.parseDouble(def.defaultValue());
            default -> def.defaultValue();
        };
    }

    private static String key(String group, String name) {
        return group.toLowerCase(Locale.ROOT) + "." + name.toLowerCase(Locale.ROOT);
    }
}
