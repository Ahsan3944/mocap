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
    private final Path formationDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private String knownProfileSignature = "";
    private List<String> knownProfileCache = List.of();

    public MocapSceneStore(Path worldRoot) throws IOException {
        Path mocap = worldRoot.resolve("mocap_files");
        recordingDir = mocap.resolve("recordings");
        sceneDir = mocap.resolve("scenes");
        formationDir = mocap.resolve("formations");
        Files.createDirectories(recordingDir);
        Files.createDirectories(sceneDir);
        Files.createDirectories(formationDir);
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

    public void copyRecording(String rawSource, String rawTarget) throws IOException {
        Path source = recordingPath(rawSource);
        String targetName = normalizeFileName(rawTarget, ".mcmocap_rec");
        Path target = recordingDir.resolve(targetName + ".mcmocap_rec").normalize();
        ensureWithin(recordingDir, target);
        if (Files.exists(target)) throw new IOException("Target recording already exists: " + targetName + ".");
        Files.copy(source, target);
    }

    public void renameRecording(String rawSource, String rawTarget) throws IOException {
        Path source = recordingPath(rawSource);
        String targetName = normalizeFileName(rawTarget, ".mcmocap_rec");
        Path target = recordingDir.resolve(targetName + ".mcmocap_rec").normalize();
        ensureWithin(recordingDir, target);
        if (Files.exists(target)) throw new IOException("Target recording already exists: " + targetName + ".");
        Files.move(source, target);
    }

    public void removeRecording(String rawName) throws IOException {
        Path file = recordingPath(rawName);
        Files.delete(file);
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
        expandInto(playableName, List.of(), List.of(), null, null, 0, new LinkedHashSet<>(), result, 0);
        if (result.isEmpty()) throw new IOException("The scene contains no playable recording actors.");
        if (result.size() > 500) throw new IOException("Paper safety limit is 500 active mannequins per playback.");
        return List.copyOf(result);
    }

    private void expandInto(String playableName, List<Transform> transforms, List<Transform> postTransforms,
                            String playerName, String skinProfile, int inheritedDelayTicks,
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
                List<Transform> childPostTransforms = append(postTransforms, globalOffsetTransform(scene));
                for (JsonElement entry : entries) {
                    if (!entry.isJsonObject()) throw new IOException("Invalid scene element in ." + sceneName);
                    JsonObject el = entry.getAsJsonObject();
                    String childName = getString(el, "name", null);
                    if (childName == null || childName.isBlank()) throw new IOException("Scene element is missing its name.");
                    List<Transform> childTransforms = append(transforms, transformFrom(el.has("transformations") && el.get("transformations").isJsonObject() ? el.getAsJsonObject("transformations") : null));
                    List<Transform> childPostTransformsWithElementOffset = append(childPostTransforms, globalOffsetTransform(el));
                    // An explicit modifier on this scene element overrides inherited context.
                    // Otherwise carry the parent override through every nested scene level.
                    String elementPlayerName = getString(el, "player_name", null);
                    // A parent element override is inherited all the way down a nested tree.
                    // Only an explicit override on a direct recording element replaces that inherited name.
                    String childPlayerName;
                    if (childName.startsWith(".")) {
                        childPlayerName = firstNonBlank(elementPlayerName, playerName);
                    } else {
                        childPlayerName = firstNonBlank(elementPlayerName, playerName);
                    }
                    String childSkin = skinProfileFrom(el.get("player_skin"));
                    // Explicit skin override on this element wins; otherwise inherit parent context.
                    if (childSkin == null) childSkin = skinProfile;
                    int childDelayTicks = inheritedDelayTicks + delayTicks(el.get("time"));
                    if (childDelayTicks > MocapRecordingParser.MAX_TICKS) throw new IOException("Scene delay exceeds the 10-hour safety limit.");
                    expandInto(childName, childTransforms, childPostTransformsWithElementOffset, childPlayerName, childSkin, childDelayTicks, activeScenes, result, depth + 1);
                    if (result.size() > 500) throw new IOException("Expanded scene exceeds the 500 mannequin Paper safety limit.");
                }
            } finally {
                activeScenes.remove(sceneName.toLowerCase(Locale.ROOT));
            }
        } else {
            Path path = recordingPath(playableName);
            List<Transform> finalTransforms = new ArrayList<>(transforms);
            finalTransforms.addAll(postTransforms);
            result.add(new ActorSpec(path, path.getFileName().toString().replace(".mcmocap_rec", ""),
                    playerName, skinProfile, List.copyOf(finalTransforms), inheritedDelayTicks));
        }
    }

    public void createScene(String rawName) throws IOException {
        String name = normalizeSceneName(rawName);
        Path target = scenePath(name);
        if (Files.exists(target)) throw new IOException("Scene already exists: ." + name + ".");
        JsonObject scene = new JsonObject();
        scene.addProperty("version", 4);
        scene.add("subscenes", new JsonArray());
        writeScene(name, scene);
    }

    public void renameScene(String rawSource, String rawTarget) throws IOException {
        String source = normalizeSceneName(rawSource);
        String target = normalizeSceneName(rawTarget);
        if (source.equalsIgnoreCase(target)) throw new IOException("Source and target scene names must differ.");
        Path sourcePath = scenePath(source);
        Path targetPath = scenePath(target);
        Path sourceGroupPath = formationPath(source);
        Path targetGroupPath = formationPath(target);
        if (!Files.isRegularFile(sourcePath)) throw new IOException("Scene not found: ." + source);
        if (Files.exists(targetPath)) throw new IOException("Target scene already exists: ." + target + ".");
        if (Files.exists(sourceGroupPath) && Files.exists(targetGroupPath)) throw new IOException("Target Formation Group already exists: " + target + ".");
        readScene(source); // validate before changing the filesystem
        Files.move(sourcePath, targetPath);
        if (Files.isRegularFile(sourceGroupPath)) {
            try {
                JsonObject group = readFormationGroup(source);
                group.addProperty("group_name", target);
                group.addProperty("scene_name", target);
                writeFormationGroup(target, group, false);
                Files.delete(sourceGroupPath);
            } catch (IOException e) {
                try { Files.move(targetPath, sourcePath); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                try { Files.deleteIfExists(targetGroupPath); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        }
    }

    public void removeScene(String rawName) throws IOException {
        String name = normalizeSceneName(rawName);
        Path file = scenePath(name);
        if (!Files.isRegularFile(file)) throw new IOException("Scene not found: ." + name);
        readScene(name); // don't remove a malformed or unsupported file accidentally
        Files.delete(file);
        Files.deleteIfExists(formationPath(name));
    }

    public void addSceneElement(String rawSceneName, String rawPlayable, double waitOnStart,
                                String rawPlayerName, String rawSkinSource, String rawSkinPath) throws IOException {
        String sceneName = normalizeSceneName(rawSceneName);
        if (!Double.isFinite(waitOnStart) || waitOnStart < 0 || waitOnStart > 36000) {
            throw new IOException("wait_on_start must be between 0 and 36000 seconds.");
        }
        if (rawPlayable == null || rawPlayable.isBlank()) throw new IOException("Recording or scene name is required.");
        String playable = rawPlayable.trim();
        if (playable.startsWith(".")) {
            String nested = normalizeSceneName(playable);
            if (nested.equalsIgnoreCase(sceneName)) throw new IOException("A scene cannot contain itself.");
            readScene(nested); // validate existence and format before linking
        } else {
            recordingPath(playable);
            playable = normalizeFileName(playable, ".mcmocap_rec");
        }

        String playerName = rawPlayerName == null || rawPlayerName.isBlank() || rawPlayerName.equals("-")
                ? null : rawPlayerName.trim();
        if (playerName != null && !playerName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("Player name must contain 1–16 letters, digits, or underscores.");
        }

        String skinSource = rawSkinSource == null || rawSkinSource.isBlank() ? "default"
                : rawSkinSource.trim().toLowerCase(Locale.ROOT);
        if (skinSource.startsWith("skin_")) skinSource = skinSource.substring(5);
        if (skinSource.equals("from_player")) skinSource = "from_player";
        if (skinSource.equals("from_file")) skinSource = "from_file";
        if (skinSource.equals("from_mineskin")) skinSource = "from_mineskin";
        if (!List.of("default", "from_player", "from_file", "from_mineskin").contains(skinSource)) {
            throw new IOException("Skin source must be default, from_player, from_file, or from_mineskin.");
        }
        String skinPath = rawSkinPath == null || rawSkinPath.isBlank() || rawSkinPath.equals("-") ? null : rawSkinPath.trim();
        if (!skinSource.equals("default") && (skinPath == null || skinPath.isBlank())) {
            throw new IOException("A skin source value is required for " + skinSource + ".");
        }
        if (skinSource.equals("from_player") && !skinPath.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("from_player skin source must be a Minecraft player/profile name.");
        }

        JsonObject scene = readScene(sceneName);
        JsonArray entries = requireEntries(scene);
        if (entries.size() >= MAX_ELEMENTS) throw new IOException("Scene exceeds the 5,000-element safety limit.");
        JsonObject element = new JsonObject();
        element.addProperty("name", playable);
        if (playerName != null) element.addProperty("player_name", playerName);
        if (waitOnStart > 0) {
            JsonObject time = new JsonObject();
            time.addProperty("wait_on_start", waitOnStart);
            element.add("time", time);
        }
        if (!skinSource.equals("default")) {
            JsonObject skin = new JsonObject();
            skin.addProperty("skin_source", skinSource);
            skin.addProperty("skin_path", skinPath);
            element.add("player_skin", skin);
        }
        entries.add(element);
        writeScene(sceneName, scene);
    }

    public void removeSceneElement(String rawSceneName, String rawPosition) throws IOException {
        String sceneName = normalizeSceneName(rawSceneName);
        ElementPosition position = parseElementPosition(rawPosition);
        JsonObject scene = readScene(sceneName);
        JsonArray entries = requireEntries(scene);
        if (position.index() < 1 || position.index() > entries.size()) {
            throw new IOException("Scene element index is out of range.");
        }
        JsonElement item = entries.get(position.index() - 1);
        if (!item.isJsonObject()) throw new IOException("Scene element is malformed.");
        String currentName = getString(item.getAsJsonObject(), "name", "");
        if (position.expectedName() != null && !position.expectedName().equals(currentName)) {
            throw new IOException("Scene element name changed; refresh suggestions and retry.");
        }
        entries.remove(position.index() - 1);
        writeScene(sceneName, scene);
    }

    public void modifySceneElement(String rawSceneName, String rawPosition, String property,
                                   String[] values) throws IOException {
        String sceneName = normalizeSceneName(rawSceneName);
        ElementPosition position = parseElementPosition(rawPosition);
        JsonObject scene = readScene(sceneName);
        JsonArray entries = requireEntries(scene);
        if (position.index() < 1 || position.index() > entries.size()) throw new IOException("Scene element index is out of range.");
        JsonElement item = entries.get(position.index() - 1);
        if (!item.isJsonObject()) throw new IOException("Scene element is malformed.");
        JsonObject element = item.getAsJsonObject();
        String currentName = getString(element, "name", "");
        if (position.expectedName() != null && !position.expectedName().equals(currentName)) {
            throw new IOException("Scene element name changed; refresh suggestions and retry.");
        }
        if (values == null) values = new String[0];
        String key = property == null ? "" : property.toLowerCase(Locale.ROOT);
        switch (key) {
            case "subscene_name", "name" -> {
                requireValues(values, 1, "scenes modify <scene> <element> subscene_name <recording|.scene>");
                String next = values[0];
                validatePlayable(next);
                element.addProperty("name", next);
            }
            case "player_name" -> {
                requireValues(values, 1, "player_name <inherited|blank|set> [<name>]");
                switch (values[0].toLowerCase(Locale.ROOT)) {
                    case "inherited", "default" -> element.remove("player_name");
                    case "blank" -> element.addProperty("player_name", "");
                    case "set" -> {
                        requireValues(values, 2, "player_name set <name>");
                        if (!values[1].matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Player name must contain 1–16 letters, digits, or underscores.");
                        element.addProperty("player_name", values[1]);
                    }
                    default -> throw new IOException("Use player_name inherited, blank, or set <name>.");
                }
            }
            case "time" -> modifyTime(element, values);
            case "transformations" -> modifyTransformations(element, values);
            case "player_skin" -> modifySkin(element, values);
            default -> throw new IOException("Unsupported scene modifier: " + property + ". Supported: subscene_name, player_name, player_skin, time, transformations.");
        }
        writeScene(sceneName, scene);
    }

    public List<String> sceneElementSuggestions(String rawSceneName) {
        try {
            JsonObject scene = readScene(rawSceneName);
            JsonArray entries = requireEntries(scene);
            List<String> result = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (!entries.get(i).isJsonObject()) continue;
                JsonObject element = entries.get(i).getAsJsonObject();
                String name = getString(element, "name", "unknown");
                result.add(String.format(Locale.ROOT, "%03d-%s", i + 1, name));
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    public synchronized List<String> knownProfiles() {
        List<String> recordingNames = listRecordings();
        List<String> sceneNames = listScenes();
        StringBuilder signature = new StringBuilder();
        appendFileSignature(signature, recordingDir, recordingNames, ".mcmocap_rec");
        appendFileSignature(signature, sceneDir, sceneNames.stream().map(s -> s.startsWith(".") ? s.substring(1) : s).toList(), ".mcmocap_scene");
        String currentSignature = signature.toString();
        if (currentSignature.equals(knownProfileSignature)) return knownProfileCache;

        Set<String> profiles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Include real recorded player/profile names so skins can be randomized from the saved
        // recording population without forcing users to manually retype every original name.
        // Results are cached by the recording/scene file size and modification signature.
        MocapRecordingParser parser = new MocapRecordingParser();
        for (String recordingName : recordingNames) {
            try {
                MocapRecordingParser.Recording recording = parser.parse(recordingPath(recordingName));
                String recordedProfile = recording.recordedProfileName();
                if (recordedProfile != null && recordedProfile.matches("[A-Za-z0-9_]{1,16}")) profiles.add(recordedProfile);
            } catch (IOException | RuntimeException ignored) {}
        }
        for (String sceneName : sceneNames) {
            try {
                JsonObject scene = readScene(sceneName);
                for (JsonElement raw : requireEntries(scene)) {
                    if (!raw.isJsonObject()) continue;
                    JsonObject element = raw.getAsJsonObject();
                    String playerName = getString(element, "player_name", null);
                    if (playerName != null && playerName.matches("[A-Za-z0-9_]{1,16}")) profiles.add(playerName);
                    JsonElement skinRaw = element.get("player_skin");
                    if (skinRaw != null && skinRaw.isJsonObject()) {
                        String skinSource = getString(skinRaw.getAsJsonObject(), "skin_source", "");
                        String skinPath = getString(skinRaw.getAsJsonObject(), "skin_path", null);
                        if ("from_player".equals(skinSource) && skinPath != null && skinPath.matches("[A-Za-z0-9_]{1,16}")) profiles.add(skinPath);
                    }
                }
            } catch (IOException | RuntimeException ignored) {}
        }
        knownProfileSignature = currentSignature;
        knownProfileCache = List.copyOf(profiles);
        return knownProfileCache;
    }

    private static void appendFileSignature(StringBuilder signature, Path root, List<String> names, String extension) {
        for (String name : names) {
            Path file = root.resolve(name + extension);
            try {
                signature.append(name).append(':').append(Files.size(file)).append(':')
                        .append(Files.getLastModifiedTime(file).toString()).append(';');
            } catch (IOException ignored) {
                signature.append(name).append(":missing;");
            }
        }
    }

    private void validatePlayable(String raw) throws IOException {
        if (raw == null || raw.isBlank()) throw new IOException("Recording or scene name is required.");
        if (raw.startsWith(".")) readScene(normalizeSceneName(raw));
        else recordingPath(raw);
    }

    private static ElementPosition parseElementPosition(String raw) throws IOException {
        if (raw == null || raw.isBlank()) throw new IOException("Scene element index is required.");
        int dash = raw.indexOf('-');
        String indexPart = dash >= 0 ? raw.substring(0, dash) : raw;
        String expectedName = dash >= 0 ? raw.substring(dash + 1) : null;
        try {
            int index = Integer.parseInt(indexPart);
            return new ElementPosition(index, expectedName);
        } catch (NumberFormatException e) {
            throw new IOException("Scene element must be a 1-based index, for example 001-recording_name.", e);
        }
    }

    private static void requireValues(String[] values, int minimum, String usage) throws IOException {
        if (values.length < minimum) throw new IOException("Usage: " + usage);
    }

    private void modifyTime(JsonObject element, String[] values) throws IOException {
        requireValues(values, 2, "time <start_delay|wait_on_start|wait_on_end|wait_for_parent_end|loop> <value>");
        String option = values[0].toLowerCase(Locale.ROOT);
        JsonObject time = element.has("time") && element.get("time").isJsonObject()
                ? element.getAsJsonObject("time") : new JsonObject();
        switch (option) {
            case "start_delay", "wait_on_start", "wait_on_end" -> {
                double seconds;
                try { seconds = Double.parseDouble(values[1]); } catch (NumberFormatException e) { throw new IOException("Time value must be a number.", e); }
                if (!Double.isFinite(seconds) || seconds < 0 || seconds > 36000) throw new IOException("Time must be between 0 and 36000 seconds.");
                if (seconds == 0) time.remove(option); else time.addProperty(option, seconds);
            }
            case "wait_for_parent_end", "loop" -> {
                boolean value;
                if (values[1].equalsIgnoreCase("true") || values[1].equalsIgnoreCase("on")) value = true;
                else if (values[1].equalsIgnoreCase("false") || values[1].equalsIgnoreCase("off")) value = false;
                else throw new IOException("Expected true/false (or on/off).");
                if ((option.equals("wait_for_parent_end") && value) || (option.equals("loop") && !value)) time.remove(option);
                else time.addProperty(option, value);
            }
            default -> throw new IOException("Unknown time modifier: " + option);
        }
        if (time.size() == 0) element.remove("time"); else element.add("time", time);
    }

    private void modifyTransformations(JsonObject element, String[] values) throws IOException {
        requireValues(values, 1, "transformations <offset|rotation|mirror|scale> ...");
        String option = values[0].toLowerCase(Locale.ROOT);
        JsonObject transform = element.has("transformations") && element.get("transformations").isJsonObject()
                ? element.getAsJsonObject("transformations") : new JsonObject();
        switch (option) {
            case "offset" -> {
                requireValues(values, 4, "transformations offset <x> <y> <z>");
                double x = parseFinite(values[1], "x"), y = parseFinite(values[2], "y"), z = parseFinite(values[3], "z");
                if (x == 0 && y == 0 && z == 0) transform.remove("offset");
                else {
                    JsonArray offset = new JsonArray(); offset.add(x); offset.add(y); offset.add(z); transform.add("offset", offset);
                }
            }
            case "rotation" -> {
                requireValues(values, 2, "transformations rotation <degrees>");
                double degree = parseFinite(values[1], "degrees");
                if (degree == 0) transform.remove("rotation"); else transform.addProperty("rotation", degree);
            }
            case "mirror" -> {
                requireValues(values, 2, "transformations mirror <none|x|z|xz>");
                String mirror = values[1].toLowerCase(Locale.ROOT);
                if (!List.of("none", "x", "z", "xz").contains(mirror)) throw new IOException("Mirror must be none, x, z, or xz.");
                if (mirror.equals("none")) transform.remove("mirror"); else transform.addProperty("mirror", mirror);
            }
            case "scale" -> {
                requireValues(values, 3, "transformations scale <of_player|of_scene> <scale>");
                String kind = values[1].toLowerCase(Locale.ROOT);
                if (!List.of("of_player", "of_scene").contains(kind)) throw new IOException("Scale must be of_player or of_scene.");
                double scale = parseFinite(values[2], "scale");
                if (scale < 0 || scale > 100) throw new IOException("Scale must be between 0 and 100.");
                JsonObject scaleObj = transform.has("scale") && transform.get("scale").isJsonObject()
                        ? transform.getAsJsonObject("scale") : new JsonObject();
                String field = kind.equals("of_scene") ? "scene_scale" : "player_scale";
                if (scale == 1) scaleObj.remove(field); else scaleObj.addProperty(field, scale);
                if (scaleObj.size() == 0) transform.remove("scale"); else transform.add("scale", scaleObj);
            }
            default -> throw new IOException("Unknown transformation modifier: " + option);
        }
        if (transform.size() == 0) element.remove("transformations"); else element.add("transformations", transform);
    }

    private void modifySkin(JsonObject element, String[] values) throws IOException {
        requireValues(values, 1, "player_skin <default|from_player|from_file|from_mineskin> [<path>]");
        String source = values[0].toLowerCase(Locale.ROOT);
        if (source.equals("default")) {
            element.remove("player_skin");
            return;
        }
        if (source.startsWith("skin_")) source = source.substring(5);
        requireValues(values, 2, "player_skin <from_player|from_file|from_mineskin> <path>");
        if (!List.of("from_player", "from_file", "from_mineskin").contains(source)) {
            throw new IOException("Skin source must be default, from_player, from_file, or from_mineskin.");
        }
        String path = values[1];
        if (source.equals("from_player") && !path.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("from_player requires a Minecraft profile name.");
        JsonObject skin = new JsonObject();
        skin.addProperty("skin_source", source);
        skin.addProperty("skin_path", path);
        element.add("player_skin", skin);
    }

    private static double parseFinite(String raw, String label) throws IOException {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) throw new IOException(label + " must be a finite number.");
            return value;
        } catch (NumberFormatException e) { throw new IOException(label + " must be a number.", e); }
    }

    private record ElementPosition(int index, String expectedName) {}

    public void assignRandomProfiles(String sceneName, List<String> rawNames, boolean skins) throws IOException {
        List<String> names = uniqueNames(rawNames, 3000);
        if (names.isEmpty()) throw new IOException("Provide at least one valid profile/name, separated by commas.");
        JsonObject scene = readScene(normalizeSceneName(sceneName));
        JsonArray entries = requireEntries(scene);
        if (entries.isEmpty()) throw new IOException("Scene is empty. Add recordings with /mocap scenes add_to first.");
        Random random = new Random();
        for (JsonElement entry : entries) {
            if (!entry.isJsonObject()) throw new IOException("Scene contains a malformed element.");
            JsonObject element = entry.getAsJsonObject();
            String name = getString(element, "name", "");
            validatePlayable(name);
            // Pick independently per element, with replacement, so every actor in a large
            // scene gets a randomized value even when the candidate list is short.
            String selected = names.get(random.nextInt(names.size()));
            if (skins) {
                JsonObject skin = element.has("player_skin") && element.get("player_skin").isJsonObject()
                        ? element.getAsJsonObject("player_skin") : new JsonObject();
                skin.addProperty("skin_source", "from_player");
                skin.addProperty("skin_path", selected);
                element.add("player_skin", skin);
            } else {
                element.addProperty("player_name", selected);
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

    /**
     * Create a named Formation Group and its backing scene from one saved player recording.
     * The placement anchor is baked into the scene as a root-level post-transform so that the
     * first actor begins at the selected position while every other actor keeps the formation offsets.
     */
    public void createFormationGroup(String rawGroupName, String rawRecordingName, int rows, int columns,
                                     double columnSpacing, double rowSpacing,
                                     org.bukkit.Location anchor) throws IOException {
        String groupName = normalizeSceneName(rawGroupName);
        String recordingName = normalizeFileName(rawRecordingName, ".mcmocap_rec");
        if (anchor == null || !finiteCoordinate(anchor.getX()) || !finiteCoordinate(anchor.getY()) || !finiteCoordinate(anchor.getZ())) {
            throw new IOException("Select a valid placement anchor with /mocap formation tool before creating a Formation Group.");
        }
        if (rows < 1 || columns < 1 || (long) rows * columns > MAX_FORMATION_ADDITIONS) {
            throw new IOException("Rows × columns must be between 1 and " + MAX_FORMATION_ADDITIONS + ".");
        }
        validateFormationGap(columnSpacing, "column-gap");
        validateFormationGap(rowSpacing, "row-gap");

        Path groupFile = formationPath(groupName);
        Path targetScene = scenePath(groupName);
        if (Files.exists(groupFile)) throw new IOException("Formation Group already exists: " + groupName + ".");
        if (Files.exists(targetScene)) throw new IOException("A scene with this name already exists: ." + groupName + ". Choose a different group name.");

        Path sourcePath = recordingPath(recordingName);
        MocapRecordingParser.Recording source = new MocapRecordingParser().parse(sourcePath);
        if (source.frames().isEmpty()) throw new IOException("The selected source recording has no frames.");
        if ((long) rows * columns > MAX_ELEMENTS) throw new IOException("Formation exceeds the scene's 5,000-element limit.");

        double yaw = Math.toRadians(source.startYaw());
        double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double behindX = Math.sin(yaw), behindZ = -Math.cos(yaw);
        JsonObject scene = new JsonObject();
        scene.addProperty("version", 4);
        JsonArray entries = new JsonArray();
        scene.add("subscenes", entries);
        JsonArray globalOffset = new JsonArray();
        globalOffset.add(roundFormationOffset(anchor.getX() - source.startX()));
        globalOffset.add(roundFormationOffset(anchor.getY() - source.startY()));
        globalOffset.add(roundFormationOffset(anchor.getZ() - source.startZ()));
        scene.add("mocap_global_offset", globalOffset);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                double x = col * columnSpacing * rightX + row * rowSpacing * behindX;
                double z = col * columnSpacing * rightZ + row * rowSpacing * behindZ;
                JsonObject element = new JsonObject();
                element.addProperty("name", recordingName);
                if (x != 0 || z != 0) {
                    JsonObject transformation = new JsonObject();
                    JsonArray offset = new JsonArray();
                    offset.add(roundFormationOffset(x));
                    offset.add(0.0);
                    offset.add(roundFormationOffset(z));
                    transformation.add("offset", offset);
                    element.add("transformations", transformation);
                }
                entries.add(element);
            }
        }

        JsonObject group = new JsonObject();
        group.addProperty("version", 1);
        group.addProperty("group_name", groupName);
        group.addProperty("scene_name", groupName);
        group.addProperty("source_recording", recordingName);
        group.addProperty("rows", rows);
        group.addProperty("columns", columns);
        group.addProperty("column_gap", columnSpacing);
        group.addProperty("row_gap", rowSpacing);
        group.addProperty("anchor_world", anchor.getWorld() == null ? "" : anchor.getWorld().getName());
        JsonArray anchorPosition = new JsonArray();
        anchorPosition.add(anchor.getX()); anchorPosition.add(anchor.getY()); anchorPosition.add(anchor.getZ());
        group.add("anchor", anchorPosition);

        writeScene(groupName, scene);
        try {
            writeFormationGroup(groupName, group, false);
        } catch (IOException e) {
            try { Files.deleteIfExists(targetScene); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    public List<String> listFormationGroups() {
        return listFiles(formationDir, ".json", false);
    }

    public JsonObject formationGroupInfo(String rawGroupName) throws IOException {
        return readFormationGroup(normalizeSceneName(rawGroupName));
    }

    public String formationGroupScene(String rawGroupName) throws IOException {
        JsonObject group = readFormationGroup(normalizeSceneName(rawGroupName));
        String scene = getString(group, "scene_name", null);
        if (scene == null) throw new IOException("Formation Group metadata is missing its scene name.");
        readScene(scene);
        return normalizeSceneName(scene);
    }

    public void deleteFormationGroup(String rawGroupName) throws IOException {
        String groupName = normalizeSceneName(rawGroupName);
        JsonObject group = readFormationGroup(groupName);
        String sceneName = getString(group, "scene_name", groupName);
        Path scene = scenePath(sceneName);
        if (Files.exists(scene)) removeScene(sceneName);
        Files.deleteIfExists(formationPath(groupName));
    }

    /** Move every expanded actor in a scene as a unit by changing the scene's final/root translation. */
    public void moveSceneTo(String rawSceneName, org.bukkit.Location target) throws IOException {
        String sceneName = normalizeSceneName(rawSceneName);
        if (target == null || !finiteCoordinate(target.getX()) || !finiteCoordinate(target.getY()) || !finiteCoordinate(target.getZ())) {
            throw new IOException("A valid target location must be selected with the placement tool.");
        }
        List<ActorSpec> actors = expand("." + sceneName);
        ActorSpec first = actors.get(0);
        MocapRecordingParser.Recording recording = new MocapRecordingParser().parse(first.recordingPath());
        MocapRecordingParser.Frame frame = recording.frames().get(0);
        org.bukkit.Location current = new org.bukkit.Location(target.getWorld(), frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (Transform transform : first.transforms()) {
            current = transform.apply(current, recording.startX(), recording.startY(), recording.startZ());
        }
        double dx = target.getX() - current.getX();
        double dy = target.getY() - current.getY();
        double dz = target.getZ() - current.getZ();

        JsonObject scene = readScene(sceneName);
        addGlobalOffset(scene, dx, dy, dz);
        writeScene(sceneName, scene);
        updateFormationGroupAnchor(sceneName, target);
    }

    /** Move one 1-based top-level scene element (or its nested scene subtree) to the selected target. */
    public void moveSceneElementTo(String rawSceneName, String rawElementPosition, org.bukkit.Location target) throws IOException {
        String sceneName = normalizeSceneName(rawSceneName);
        ElementPosition position = parseElementPosition(rawElementPosition);
        if (target == null || !finiteCoordinate(target.getX()) || !finiteCoordinate(target.getY()) || !finiteCoordinate(target.getZ())) {
            throw new IOException("A valid target location must be selected with the placement tool.");
        }
        JsonObject scene = readScene(sceneName);
        JsonArray entries = requireEntries(scene);
        if (position.index() < 1 || position.index() > entries.size()) throw new IOException("Scene element index is out of range.");
        JsonElement rawElement = entries.get(position.index() - 1);
        if (!rawElement.isJsonObject()) throw new IOException("Scene element is malformed.");
        JsonObject element = rawElement.getAsJsonObject();
        String name = getString(element, "name", "");
        if (position.expectedName() != null && !position.expectedName().equals(name)) {
            throw new IOException("Scene element name changed; refresh suggestions and retry.");
        }

        List<ActorSpec> actors = expandSceneElement(sceneName, scene, element);
        if (actors.isEmpty()) throw new IOException("Selected scene element contains no playable recording actors.");
        ActorSpec first = actors.get(0);
        MocapRecordingParser.Recording recording = new MocapRecordingParser().parse(first.recordingPath());
        MocapRecordingParser.Frame frame = recording.frames().get(0);
        org.bukkit.Location current = new org.bukkit.Location(target.getWorld(), frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (Transform transform : first.transforms()) {
            current = transform.apply(current, recording.startX(), recording.startY(), recording.startZ());
        }
        addGlobalOffset(element, target.getX() - current.getX(), target.getY() - current.getY(), target.getZ() - current.getZ());
        writeScene(sceneName, scene);
        if (position.index() == 1) updateFormationGroupAnchor(sceneName, target);
    }

    private List<ActorSpec> expandSceneElement(String sceneName, JsonObject scene, JsonObject element) throws IOException {
        List<ActorSpec> result = new ArrayList<>();
        String childName = getString(element, "name", null);
        if (childName == null || childName.isBlank()) throw new IOException("Scene element is missing its name.");
        List<Transform> transforms = append(List.of(), transformFrom(
                element.has("transformations") && element.get("transformations").isJsonObject()
                        ? element.getAsJsonObject("transformations") : null));
        List<Transform> postTransforms = append(List.of(), globalOffsetTransform(scene));
        postTransforms = append(postTransforms, globalOffsetTransform(element));
        String playerName = getString(element, "player_name", null);
        String skin = skinProfileFrom(element.get("player_skin"));
        int delay = delayTicks(element.get("time"));
        Set<String> activeScenes = new LinkedHashSet<>();
        activeScenes.add(sceneName.toLowerCase(Locale.ROOT));
        expandInto(childName, transforms, postTransforms, playerName, skin, delay, activeScenes, result, 1);
        return List.copyOf(result);
    }

    private static void addGlobalOffset(JsonObject object, double dx, double dy, double dz) throws IOException {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) throw new IOException("Move offsets must be finite numbers.");
        JsonArray old = object.has("mocap_global_offset") && object.get("mocap_global_offset").isJsonArray()
                ? object.getAsJsonArray("mocap_global_offset") : new JsonArray();
        double oldX = old.size() > 0 ? finite(old.get(0)) : 0;
        double oldY = old.size() > 1 ? finite(old.get(1)) : 0;
        double oldZ = old.size() > 2 ? finite(old.get(2)) : 0;
        double x = roundFormationOffset(oldX + dx), y = roundFormationOffset(oldY + dy), z = roundFormationOffset(oldZ + dz);
        if (!finiteCoordinate(x) || !finiteCoordinate(y) || !finiteCoordinate(z)) throw new IOException("Move would put the scene outside supported world-coordinate limits.");
        JsonArray next = new JsonArray();
        next.add(x); next.add(y); next.add(z);
        if (Math.abs(x) < 0.0001 && Math.abs(y) < 0.0001 && Math.abs(z) < 0.0001) object.remove("mocap_global_offset");
        else object.add("mocap_global_offset", next);
    }

    public static boolean finiteCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000;
    }

    private static void validateFormationGap(double value, String label) throws IOException {
        if (!Double.isFinite(value) || value < 0 || value > 128) throw new IOException(label + " must be between 0 and 128 blocks.");
    }

    private Path formationPath(String rawName) throws IOException {
        String name = normalizeSceneName(rawName);
        Path file = formationDir.resolve(name + ".json").normalize();
        ensureWithin(formationDir, file);
        return file;
    }

    private JsonObject readFormationGroup(String name) throws IOException {
        Path file = formationPath(name);
        if (!Files.isRegularFile(file)) throw new IOException("Formation Group not found: " + name);
        if (Files.size(file) <= 0 || Files.size(file) > 128 * 1024) throw new IOException("Formation Group metadata size is outside safety limits.");
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Formation Group metadata must be a JSON object.");
            JsonObject group = parsed.getAsJsonObject();
            if (!group.has("version") || group.get("version").getAsInt() != 1) throw new IOException("Unsupported Formation Group metadata version.");
            return group;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Formation Group metadata is malformed: " + e.getMessage(), e);
        }
    }

    private void writeFormationGroup(String name, JsonObject group, boolean replace) throws IOException {
        Path target = formationPath(name);
        if (!replace && Files.exists(target)) throw new IOException("Formation Group already exists: " + name + ".");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(group), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                if (replace) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                if (replace) Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, target);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    private void updateFormationGroupAnchor(String sceneName, org.bukkit.Location anchor) throws IOException {
        Path path = formationPath(sceneName);
        if (!Files.isRegularFile(path)) return;
        JsonObject group = readFormationGroup(sceneName);
        JsonArray position = new JsonArray();
        position.add(anchor.getX()); position.add(anchor.getY()); position.add(anchor.getZ());
        group.add("anchor", position);
        group.addProperty("anchor_world", anchor.getWorld() == null ? "" : anchor.getWorld().getName());
        writeFormationGroup(sceneName, group, true);
    }

    private static Transform globalOffsetTransform(JsonObject scene) {
        JsonElement raw = scene.get("mocap_global_offset");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().size() != 3) return Transform.IDENTITY;
        JsonArray offset = raw.getAsJsonArray();
        return new Transform(0, 1, finite(offset.get(0)), finite(offset.get(1)), finite(offset.get(2)), false, false);
    }

    /** Backwards-compatible equal-spacing overload. */
    public void addFormation(String sceneName, String recordingName, int rows, int columns, double spacing) throws IOException {
        addFormation(sceneName, recordingName, rows, columns, spacing, spacing);
    }

    /**
     * Builds a formation with independent gaps between columns (side-to-side) and rows (front-to-back).
     * The source recording anchors at row 1 / column 1 (zero offset). Columns grow to the character's
     * right and rows grow behind the character relative to the recording's starting yaw.
     */
    public void addFormation(String sceneName, String recordingName, int rows, int columns,
                             double columnSpacing, double rowSpacing) throws IOException {
        if (rows < 1 || columns < 1 || (long) rows * columns > MAX_FORMATION_ADDITIONS) {
            throw new IOException("Formation must contain between 1 and " + MAX_FORMATION_ADDITIONS + " actors.");
        }
        if (!Double.isFinite(columnSpacing) || columnSpacing < 0.0 || columnSpacing > 128.0) {
            throw new IOException("Column spacing must be between 0 and 128 blocks.");
        }
        if (!Double.isFinite(rowSpacing) || rowSpacing < 0.0 || rowSpacing > 128.0) {
            throw new IOException("Row spacing must be between 0 and 128 blocks.");
        }
        Path recording = recordingPath(recordingName);
        MocapRecordingParser.Recording source = new MocapRecordingParser().parse(recording);
        String baseName = normalizeFileName(recordingName, ".mcmocap_rec");
        JsonObject scene = readScene(normalizeSceneName(sceneName));
        JsonArray entries = requireEntries(scene);
        if ((long) entries.size() + (long) rows * columns > MAX_ELEMENTS) {
            throw new IOException("Formation would exceed the 5,000-element scene limit.");
        }

        double yaw = Math.toRadians(source.startYaw());
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double behindX = Math.sin(yaw);
        double behindZ = -Math.cos(yaw);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                double x = col * columnSpacing * rightX + row * rowSpacing * behindX;
                double z = col * columnSpacing * rightZ + row * rowSpacing * behindZ;
                JsonObject element = new JsonObject();
                element.addProperty("name", baseName);
                JsonObject transformations = new JsonObject();
                JsonArray offset = new JsonArray();
                offset.add(roundFormationOffset(x));
                offset.add(0.0);
                offset.add(roundFormationOffset(z));
                transformations.add("offset", offset);
                element.add("transformations", transformations);
                entries.add(element);
            }
        }
        writeScene(normalizeSceneName(sceneName), scene);
    }

    private static double roundFormationOffset(double value) {
        if (Math.abs(value) < 1.0e-9) return 0.0;
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    public void copyScene(String source, String target) throws IOException {
        String src = normalizeSceneName(source);
        String dst = normalizeSceneName(target);
        if (src.equalsIgnoreCase(dst)) throw new IOException("Source and target scene names must differ.");
        if (Files.exists(scenePath(dst))) throw new IOException("Target scene already exists; refusing to overwrite it.");
        if (Files.exists(formationPath(dst))) throw new IOException("Target Formation Group already exists: " + dst + ".");
        JsonObject scene = readScene(src);
        writeScene(dst, scene);
        Path sourceGroupPath = formationPath(src);
        if (Files.isRegularFile(sourceGroupPath)) {
            try {
                JsonObject group = readFormationGroup(src);
                group.addProperty("group_name", dst);
                group.addProperty("scene_name", dst);
                writeFormationGroup(dst, group, false);
            } catch (IOException e) {
                try { Files.deleteIfExists(scenePath(dst)); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                try { Files.deleteIfExists(formationPath(dst)); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        }
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
            final int versionNumber;
            try {
                versionNumber = version.getAsInt();
            } catch (RuntimeException e) {
                throw new IOException("Scene format version is invalid.", e);
            }
            int absoluteVersion = Math.abs(versionNumber);
            if (absoluteVersion < 1 || absoluteVersion > 4) {
                throw new IOException("Unsupported Mocap scene format version " + versionNumber + "; this Paper port supports versions 1–4.");
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

    private static int delayTicks(JsonElement raw) throws IOException {
        if (raw == null || !raw.isJsonObject()) return 0;
        JsonObject time = raw.getAsJsonObject();
        double startDelay = number(time, "start_delay", 0.0);
        double waitOnStart = number(time, "wait_on_start", 0.0);
        double seconds = startDelay + waitOnStart;
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > MocapRecordingParser.MAX_TICKS / 20.0) {
            throw new IOException("Scene start delay must be finite and no longer than 10 hours.");
        }
        return (int)Math.round(seconds * 20.0);
    }

    private static Transform transformFrom(JsonObject raw) {
        if (raw == null) return Transform.IDENTITY;
        double rotation = number(raw, "rotation", 0.0);
        double scale = 1.0;
        if (raw.has("scale") && raw.get("scale").isJsonObject()) {
            JsonObject scaleObj = raw.getAsJsonObject("scale");
            scale = number(scaleObj, "scene_scale", 1.0);
        }
        String mirror = getString(raw, "mirror", "none").toLowerCase(Locale.ROOT);
        boolean mirrorX = mirror.equals("x") || mirror.equals("xz");
        boolean mirrorZ = mirror.equals("z") || mirror.equals("xz");
        double x = 0, y = 0, z = 0;
        if (raw.has("offset") && raw.get("offset").isJsonArray()) {
            JsonArray a = raw.getAsJsonArray("offset");
            if (a.size() == 3) { x = finite(a.get(0)); y = finite(a.get(1)); z = finite(a.get(2)); }
        }
        return new Transform(rotation, scale, x, y, z, mirrorX, mirrorZ);
    }

    private static List<Transform> append(List<Transform> base, Transform extra) {
        if (extra == Transform.IDENTITY || (extra.rotation() == 0 && extra.scale() == 1 && extra.x() == 0 && extra.y() == 0 && extra.z() == 0 && !extra.mirrorX() && !extra.mirrorZ())) return base;
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

    public record Transform(double rotation, double scale, double x, double y, double z, boolean mirrorX, boolean mirrorZ) {
        public static final Transform IDENTITY = new Transform(0, 1, 0, 0, 0, false, false);
        public org.bukkit.Location apply(org.bukkit.Location loc, double anchorX, double anchorY, double anchorZ) {
            double rx = loc.getX() - anchorX, rz = loc.getZ() - anchorZ;
            if (mirrorX) rx = -rx;
            if (mirrorZ) rz = -rz;
            double radians = Math.toRadians(rotation);
            double rotatedX = (rx * Math.cos(radians) - rz * Math.sin(radians)) * scale;
            double rotatedZ = (rx * Math.sin(radians) + rz * Math.cos(radians)) * scale;
            float yaw = loc.getYaw() + (float)rotation;
            if (mirrorX) yaw = -yaw;
            if (mirrorZ) yaw = -(yaw + 90.0f) - 90.0f;
            return new org.bukkit.Location(loc.getWorld(), anchorX + rotatedX + x, anchorY + (loc.getY() - anchorY) * scale + y,
                    anchorZ + rotatedZ + z, yaw, loc.getPitch());
        }
    }

    public record ActorSpec(Path recordingPath, String recordingName, String playerName, String skinProfile,
                            List<Transform> transforms, int delayTicks) {}
}