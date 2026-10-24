package net.mt1006.mocap.paper.scene;

import com.google.gson.*;
import net.mt1006.mocap.paper.playback.MocapRecordingParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class MocapSceneStore {
    private static final int MAX_SCENE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_ELEMENTS = 5000;
    public static final int MAX_FORMATION_ADDITIONS = 1000;
    private static final int MAX_NESTED_DEPTH = 32;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private final Path recordingDir;
    private final Path sceneDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MocapSceneStore(Path worldRoot) throws IOException {
        Path mocap = worldRoot.resolve("mocap_files");
        recordingDir = mocap.resolve("recordings");
        sceneDir = mocap.resolve("scenes");
        Files.createDirectories(recordingDir);
        Files.createDirectories(sceneDir);
    }

    public Path recordingPath(String raw) throws IOException {
        String name = normalizeFileName(raw, ".mcmocap_rec");
        Path file = recordingDir.resolve(name + ".mcmocap_rec").normalize();
        ensureWithin(recordingDir, file);
        if (!Files.isRegularFile(file)) throw new IOException("Recording not found: " + name);
        long size = Files.size(file);
        if (size <= 0 || size > MocapRecordingParser.MAX_FILE_BYTES) throw new IOException("Recording size is outside safety limits: " + name);
        return file;
    }

    public Path scenePath(String raw) throws IOException {
        String name = normalizeSceneName(raw);
        Path file = sceneDir.resolve(name + ".mcmocap_scene").normalize();
        ensureWithin(sceneDir, file);
        return file;
    }

    public List<String> listRecordings() {
        return listFiles(recordingDir, ".mcmocap_rec", false);
    }

    public List<String> listScenes() {
        return listFiles(sceneDir, ".mcmocap_scene", true);
    }

    private List<String> listFiles(Path directory, String extension, boolean dotPrefix) {
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(extension))
                    .map(n -> n.substring(0, n.length() - extension.length()))
                    .map(n -> dotPrefix ? "." + n : n)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public List<ActorSpec> expand(String playableName) throws IOException {
        List<ActorSpec> result = new ArrayList<>();
        expandInto(playableName, List.of(), null, null, new LinkedHashSet<>(), result, 0);
        if (result.isEmpty()) throw new IOException("The scene contains no playable recording actors.");
        if (result.size() > 500) throw new IOException("Paper safety limit is 500 active mannequins per playback.");
        return List.copyOf(result);
    }

    private void expandInto(String playableName, List<Transform> transforms, String playerName, String skinProfile,
                            Set<String> activeScenes, List<ActorSpec> result, int depth) throws IOException {
        if (depth > MAX_NESTED_DEPTH) throw new IOException("Nested scene depth exceeds " + MAX_NESTED_DEPTH + ".");
        if (playableName == null || playableName.isBlank()) throw new IOException("Scene element name is empty.");
        if (playableName.startsWith(".")) {
            String sceneName = normalizeSceneName(playableName);
            if (!activeScenes.add(sceneName.toLowerCase(Locale.ROOT))) {
                throw new IOException("Recursive scene reference detected: ." + sceneName);
            }
            try {
                JsonObject scene = readScene(sceneName);
                JsonArray entries = scene.getAsJsonArray("subscenes");
                if (entries == null) throw new IOException("Scene is missing the subscenes list: ." + sceneName);
                if (entries.size() > MAX_ELEMENTS) throw new IOException("Scene exceeds the element limit.");
                for (JsonElement entry : entries) {
                    if (!entry.isJsonObject()) throw new IOException("Invalid scene element in ." + sceneName);
                    JsonObject el = entry.getAsJsonObject();
                    String childName = getString(el, "name", null);
                    if (childName == null || childName.isBlank()) throw new IOException("Scene element is missing its name.");
                    List<Transform> childTransforms = append(transforms, transformFrom(el.has("transformations") && el.get("transformations").isJsonObject() ? el.getAsJsonObject("transformations") : null));
                    String childPlayerName = firstNonBlank(getString(el, "player_name", null), playerName);
                    String childSkin = skinProfileFrom(el.get("player_skin"));
                    if (childSkin == null) childSkin = skinProfile;
                    expandInto(childName, childTransforms, childPlayerName, childSkin, activeScenes, result, depth + 1);
                    if (result.size() > 500) throw new IOException("Expanded scene exceeds the 500 mannequin Paper safety limit.");
                }
            } finally {
                activeScenes.remove(sceneName.toLowerCase(Locale.ROOT));
            }
        } else {
            Path path = recordingPath(playableName);
            result.add(new ActorSpec(path, path.getFileName().toString().replace(".mcmocap_rec", ""),
                    playerName, skinProfile, List.copyOf(transforms)));
        }
    }

    public void assignRandomProfiles(String sceneName, List<String> rawNames, boolean skins) throws IOException {
        List<String> names = uniqueNames(rawNames, skins ? 256 : 1000);
        if (names.isEmpty()) throw new IOException("Provide at least one valid profile/name, separated by commas.");
        JsonObject scene = readScene(normalizeSceneName(sceneName));
        JsonArray entries = requireEntries(scene);
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) throw new IOException("Scene contains a malformed element.");
            String name = getString(entry.getAsJsonObject(), "name", "");
            if (name.startsWith(".")) throw new IOException("Random assignment currently requires direct recording elements; nested scenes are not changed.");
            if (name.isBlank()) throw new IOException("Scene contains an element without a recording name.");
            recordingPath(name);
        }
        int limit = Math.min(names.size(), entries.size());
        Collections.shuffle(names, new Random());
        for (int i = 0; i < limit; i++) {
            JsonObject element = entries.get(i).getAsJsonObject();
            if (skins) {
                JsonObject skin = element.has("player_skin") && element.get("player_skin").isJsonObject()
                        ? element.getAsJsonObject("player_skin") : new JsonObject();
                skin.addProperty("skin_source", "from_player");
                skin.addProperty("skin_path", names.get(i));
                element.add("player_skin", skin);
            } else {
                element.addProperty("player_name", names.get(i));
            }
        }
        writeScene(normalizeSceneName(sceneName), scene);
    }

    public void setElementPlayerName(String sceneName, int oneBasedIndex, String playerName) throws IOException {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("Player names must contain 1–16 letters, digits, or underscores.");
        }
        JsonObject scene = readScene(normalizeSceneName(sceneName));
        JsonArray entries = requireEntries(scene);
        if (oneBasedIndex < 1 || oneBasedIndex > entries.size()) throw new IOException("Scene element index is out of range.");
        JsonElement entry = entries.get(oneBasedIndex - 1);
        if (!entry.isJsonObject()) throw new IOException("Scene element is malformed.");
        JsonObject element = entry.getAsJsonObject();
        String playable = getString(element, "name", "");
        if (playable.startsWith(".")) throw new IOException("A nested scene can expand to multiple actors; rename a direct recording element instead.");
        recordingPath(playable);
        element.addProperty("player_name", playerName);
        writeScene(normalizeSceneName(sceneName), scene);
    }

    public void addFormation(String sceneName, String recordingName, int rows, int columns, double spacing) throws IOException {
        if (rows < 1 || columns < 1 || (long) rows * columns > MAX_FORMATION_ADDITIONS) {
            throw new IOException("Formation must contain between 1 and " + MAX_FORMATION_ADDITIONS + " actors.");
        }
        if (!Double.isFinite(spacing) || spacing < 0.5 || spacing > 128.0) throw new IOException("Spacing must be between 0.5 and 128.");
        recordingPath(recordingName); // validate the recording exists first
        String baseName = normalizeFileName(recordingName, ".mcmocap_rec");
        JsonObject scene = readScene(normalizeSceneName(sceneName));
        JsonArray entries = requireEntries(scene);
        if ((long) entries.size() + (long) rows * columns > MAX_ELEMENTS) throw new IOException("Formation would exceed the 5,000-element scene limit.");
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                double x = (col - (columns - 1) / 2.0) * spacing;
                double z = (row - (rows - 1) / 2.0) * spacing;
                JsonObject element = new JsonObject();
                element.addProperty("name", baseName);
                JsonObject transformations = new JsonObject();
                JsonArray offset = new JsonArray();
                offset.add(x); offset.add(0.0); offset.add(z);
                transformations.add("offset", offset);
                element.add("transformations", transformations);
                entries.add(element);
            }
        }
        writeScene(normalizeSceneName(sceneName), scene);
    }

    public void copyScene(String source, String target) throws IOException {
        String src = normalizeSceneName(source);
        String dst = normalizeSceneName(target);
        if (src.equalsIgnoreCase(dst)) throw new IOException("Source and target scene names must differ.");
        if (Files.exists(scenePath(dst))) throw new IOException("Target scene already exists; refusing to overwrite it.");
        JsonObject scene = readScene(src);
        writeScene(dst, scene);
    }

    public JsonObject readScene(String rawName) throws IOException {
        Path path = scenePath(rawName);
        if (!Files.isRegularFile(path)) throw new IOException("Scene not found: ." + normalizeSceneName(rawName));
        long size = Files.size(path);
        if (size <= 0 || size > MAX_SCENE_BYTES) throw new IOException("Scene file is outside safety limits.");
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Scene root must be a JSON object.");
            JsonObject scene = parsed.getAsJsonObject();
            JsonElement version = scene.get("version");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Scene does not contain a numeric format version.");
            }
            JsonArray entries = requireEntries(scene);
            if (entries.size() > MAX_ELEMENTS) throw new IOException("Scene exceeds the 5,000-element safety limit.");
            return scene;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Scene JSON is malformed: " + e.getMessage(), e);
        }
    }

    private JsonArray requireEntries(JsonObject scene) throws IOException {
        JsonElement raw = scene.get("subscenes");
        if (raw == null || !raw.isJsonArray()) throw new IOException("Scene is missing a valid subscenes array.");
        return raw.getAsJsonArray();
    }

    private void writeScene(String name, JsonObject scene) throws IOException {
        Path target = scenePath(name);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = gson.toJson(scene).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SCENE_BYTES) throw new IOException("Resulting scene exceeds the 4 MiB limit.");
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    private static List<String> uniqueNames(List<String> input, int max) throws IOException {
        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : input) {
            if (item == null) continue;
            String n = item.trim();
            if (n.isEmpty()) continue;
            if (!n.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Invalid Minecraft profile/name: " + n);
            if (seen.add(n.toLowerCase(Locale.ROOT))) output.add(n);
            if (output.size() > max) throw new IOException("Too many values; maximum is " + max + ".");
        }
        return output;
    }

    private static Transform transformFrom(JsonObject raw) {
        if (raw == null) return Transform.IDENTITY;
        double rotation = number(raw, "rotation", 0.0);
        double scale = 1.0;
        if (raw.has("scale") && raw.get("scale").isJsonObject()) {
            JsonObject scaleObj = raw.getAsJsonObject("scale");
            scale = number(scaleObj, "scene_scale", 1.0);
        }
        double x = 0, y = 0, z = 0;
        if (raw.has("offset") && raw.get("offset").isJsonArray()) {
            JsonArray a = raw.getAsJsonArray("offset");
            if (a.size() == 3) { x = finite(a.get(0)); y = finite(a.get(1)); z = finite(a.get(2)); }
        }
        return new Transform(rotation, scale, x, y, z);
    }

    private static List<Transform> append(List<Transform> base, Transform extra) {
        if (extra == Transform.IDENTITY || (extra.rotation() == 0 && extra.scale() == 1 && extra.x() == 0 && extra.y() == 0 && extra.z() == 0)) return base;
        List<Transform> copy = new ArrayList<>(base);
        copy.add(extra);
        return List.copyOf(copy);
    }

    private static String skinProfileFrom(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) return null;
        JsonObject skin = raw.getAsJsonObject();
        String source = getString(skin, "skin_source", "default").toLowerCase(Locale.ROOT);
        return source.equals("from_player") ? getString(skin, "skin_path", null) : null;
    }

    private static String getString(JsonObject object, String key, String fallback) {
        JsonElement v = object.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : fallback;
    }

    private static double number(JsonObject object, String key, double fallback) {
        JsonElement e = object.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? finite(e) : fallback;
    }

    private static double finite(JsonElement e) {
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : 0.0;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    public static String normalizeSceneName(String raw) throws IOException {
        if (raw == null) throw new IOException("Scene name is required.");
        String name = raw.trim();
        if (name.startsWith(".")) name = name.substring(1);
        return normalizeFileName(name, ".mcmocap_scene");
    }

    private static String normalizeFileName(String raw, String extension) throws IOException {
        if (raw == null) throw new IOException("File name is required.");
        String name = raw.trim();
        if (name.endsWith(extension)) name = name.substring(0, name.length() - extension.length());
        if (!SAFE_NAME.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IOException("Invalid file name: " + raw);
        }
        return name;
    }

    private static void ensureWithin(Path root, Path candidate) throws IOException {
        Path nr = root.toAbsolutePath().normalize();
        Path nc = candidate.toAbsolutePath().normalize();
        if (!nc.startsWith(nr)) throw new IOException("Resolved path leaves the Mocap data directory.");
    }

    public record Transform(double rotation, double scale, double x, double y, double z) {
        public static final Transform IDENTITY = new Transform(0, 1, 0, 0, 0);
        public org.bukkit.Location apply(org.bukkit.Location loc, double anchorX, double anchorY, double anchorZ) {
            double rx = loc.getX() - anchorX, rz = loc.getZ() - anchorZ;
            double radians = Math.toRadians(rotation);
            double rotatedX = (rx * Math.cos(radians) - rz * Math.sin(radians)) * scale;
            double rotatedZ = (rx * Math.sin(radians) + rz * Math.cos(radians)) * scale;
            return new org.bukkit.Location(loc.getWorld(), anchorX + rotatedX + x, anchorY + (loc.getY() - anchorY) * scale + y,
                    anchorZ + rotatedZ + z, (float)(loc.getYaw() + rotation), loc.getPitch());
        }
    }

    public record ActorSpec(Path recordingPath, String recordingName, String playerName, String skinProfile, List<Transform> transforms) {}
}