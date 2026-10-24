package net.mt1006.mocap.paper.recording;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Basic Paper-native movement/rotation recorder that writes the legacy Mocap v1
 * binary format accepted by MocapRecordingParser. This deliberately does not
 * claim to capture block, chat, inventory, entity, or plugin-extension actions.
 */
public final class PaperRecordingManager {
    public static final int MAX_RECORDING_TICKS = 720_000;
    public static final int MAX_RECORDING_BYTES = 16 * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private final JavaPlugin plugin;
    private final Path recordingsDirectory;
    private final Map<String, Session> active = new LinkedHashMap<>();
    private final Map<String, Session> stopped = new LinkedHashMap<>();
    private final Map<UUID, String> activeByPlayer = new LinkedHashMap<>();
    private BukkitTask ticker;
    private int nextId = 1;

    public PaperRecordingManager(JavaPlugin plugin, Path worldRoot) throws IOException {
        this.plugin = plugin;
        recordingsDirectory = worldRoot.resolve("mocap_files").resolve("recordings").toAbsolutePath().normalize();
        Files.createDirectories(recordingsDirectory);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public String start(Player player, String instantSaveName) throws IOException {
        if (player == null || !player.isOnline()) throw new IOException("Select an online player to record.");
        if (activeByPlayer.containsKey(player.getUniqueId())) {
            throw new IOException(player.getName() + " is already being recorded (ID " + activeByPlayer.get(player.getUniqueId()) + ").");
        }
        if (instantSaveName != null && !instantSaveName.isBlank()) validateName(instantSaveName);
        String id = Integer.toString(nextId++);
        if (nextId < 1) nextId = 1;
        Session session = new Session(id, player, instantSaveName);
        active.put(id, session);
        activeByPlayer.put(player.getUniqueId(), id);
        return id;
    }

    private void tick() {
        for (Session session : new ArrayList<>(active.values())) {
            Player player = plugin.getServer().getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                finish(session);
                continue;
            }
            try {
                recordTick(session, player);
                if (session.ticks >= MAX_RECORDING_TICKS || session.bytes.size() >= MAX_RECORDING_BYTES) {
                    finish(session);
                    plugin.getLogger().warning("Mocap recording " + session.id + " reached the Paper safety limit and was stopped.");
                }
            } catch (IOException | RuntimeException e) {
                finish(session);
                plugin.getLogger().severe("Mocap recording " + session.id + " stopped after an encoding error: " + e.getMessage());
            }
        }
    }

    private void recordTick(Session session, Player player) throws IOException {
        Location now = player.getLocation();
        if (!Double.isFinite(now.getX()) || !Double.isFinite(now.getY()) || !Double.isFinite(now.getZ())) {
            throw new IOException("Player position was not finite.");
        }
        DataOutputStream out = new DataOutputStream(session.bytes);
        // Action 0 is NEXT_TICK in the Mocap v1 stream.
        out.writeByte(0);
        session.ticks++;

        double dx = now.getX() - session.lastX;
        double dy = now.getY() - session.lastY;
        double dz = now.getZ() - session.lastZ;
        float yaw = now.getYaw();
        float pitch = now.getPitch();
        boolean moved = Math.abs(dx) > 1.0e-7 || Math.abs(dy) > 1.0e-7 || Math.abs(dz) > 1.0e-7;
        boolean rotated = Math.abs(yaw - session.lastYaw) > 1.0e-4 || Math.abs(pitch - session.lastPitch) > 1.0e-4;
        if (moved || rotated) {
            // Action 1 is MOVEMENT_LEGACY: delta XYZ, pitch, yaw, on-ground.
            out.writeByte(1);
            out.writeDouble(dx);
            out.writeDouble(dy);
            out.writeDouble(dz);
            out.writeFloat(pitch);
            out.writeFloat(yaw);
            out.writeBoolean(player.isOnGround());
            session.lastX = now.getX();
            session.lastY = now.getY();
            session.lastZ = now.getZ();
            session.lastYaw = yaw;
            session.lastPitch = pitch;
        }
        if (session.bytes.size() > MAX_RECORDING_BYTES) {
            throw new IOException("Recording exceeded the 16 MiB safety limit.");
        }
    }

    public String stop(String requestedId, Player sender) throws IOException {
        Session session = resolve(requestedId, sender, true);
        if (!active.containsKey(session.id)) throw new IOException("Recording " + session.id + " is not active.");
        finish(session);
        if (session.instantSaveName != null && !session.instantSaveName.isBlank()) {
            String fileName = session.instantSaveName;
            if (active.values().stream().anyMatch(s -> s.instantSaveName != null && s.instantSaveName.equals(fileName))) {
                fileName = fileName + "_" + session.playerName;
            }
            saveSession(session, fileName);
            stopped.remove(session.id);
            return "Stopped and saved recording ID " + session.id + " as " + fileName + ".";
        }
        return "Stopped recording " + session.playerName + " (ID " + session.id + "). Save it with /mocap recording save <name> " + session.id + ".";
    }

    public String save(String name, String requestedId, Player sender) throws IOException {
        validateName(name);
        Session session = resolve(requestedId, sender, false);
        if (active.containsKey(session.id)) throw new IOException("Stop recording ID " + session.id + " before saving it.");
        saveSession(session, name);
        stopped.remove(session.id);
        return "Saved recording ID " + session.id + " as " + name + ".";
    }

    public String discard(String requestedId, Player sender) throws IOException {
        Session session = resolve(requestedId, sender, true);
        boolean wasActive = active.containsKey(session.id);
        if (wasActive) {
            active.remove(session.id);
            activeByPlayer.remove(session.playerId);
        }
        stopped.remove(session.id);
        return (wasActive ? "Stopped and discarded" : "Discarded") + " recording ID " + session.id + " (" + session.playerName + ").";
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        active.values().stream().sorted(Comparator.comparingInt(s -> parseId(s.id))).forEach(s ->
                lines.add(s.id + " " + s.playerName + " [RECORDING, " + s.ticks + " ticks]"));
        stopped.values().stream().sorted(Comparator.comparingInt(s -> parseId(s.id))).forEach(s ->
                lines.add(s.id + " " + s.playerName + " [STOPPED, " + s.ticks + " ticks]"));
        return List.copyOf(lines);
    }

    public List<String> availableIds() {
        List<String> ids = new ArrayList<>(active.keySet());
        ids.addAll(stopped.keySet());
        return ids.stream().distinct().toList();
    }

    public boolean hasRecording(Player player) {
        return player != null && activeByPlayer.containsKey(player.getUniqueId());
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (Session session : new ArrayList<>(active.values())) finish(session);
    }

    private void finish(Session session) {
        active.remove(session.id);
        activeByPlayer.remove(session.playerId);
        if (session.instantSaveName != null && !session.instantSaveName.isBlank()) {
            try {
                saveSession(session, session.instantSaveName);
                stopped.remove(session.id);
            } catch (IOException e) {
                stopped.put(session.id, session);
                plugin.getLogger().warning("Could not instant-save Mocap recording " + session.id + ": " + e.getMessage());
            }
        } else {
            stopped.put(session.id, session);
        }
    }

    private Session resolve(String requestedId, Player sender, boolean allowActive) throws IOException {
        String id = requestedId == null || requestedId.isBlank() ? null : requestedId.trim();
        if (id != null) {
            Session session = active.get(id);
            if (session == null) session = stopped.get(id);
            if (session == null) throw new IOException("Recording ID not found: " + id + ". Use /mocap recording list.");
            if (!allowActive && active.containsKey(id)) throw new IOException("Stop recording ID " + id + " before saving it.");
            return session;
        }
        if (sender != null) {
            String owned = activeByPlayer.get(sender.getUniqueId());
            if (owned != null && active.containsKey(owned)) {
                if (!allowActive) throw new IOException("Stop recording ID " + owned + " before saving it.");
                return active.get(owned);
            }
            Session latest = null;
            for (Session session : stopped.values()) if (session.playerId.equals(sender.getUniqueId())) latest = session;
            if (latest != null) return latest;
        }
        List<Session> candidates = new ArrayList<>(active.values());
        candidates.addAll(stopped.values());
        if (candidates.size() == 1) {
            Session only = candidates.get(0);
            if (!allowActive && active.containsKey(only.id)) throw new IOException("Stop recording ID " + only.id + " before saving it.");
            return only;
        }
        if (candidates.isEmpty()) throw new IOException("No current or stopped Mocap recordings were found.");
        throw new IOException("More than one recording exists. Specify its ID; use /mocap recording list.");
    }

    private void saveSession(Session session, String rawName) throws IOException {
        String name = validateName(rawName);
        Path target = recordingsDirectory.resolve(name + ".mcmocap_rec").normalize();
        if (!target.startsWith(recordingsDirectory)) throw new IOException("Recording path escapes the recordings folder.");
        if (Files.exists(target)) throw new IOException("Recording already exists: " + name + ". Choose a different name.");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, session.bytes.toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private static String validateName(String rawName) throws IOException {
        if (rawName == null) throw new IOException("Provide a recording name.");
        String name = rawName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".mcmocap_rec")) {
            name = name.substring(0, name.length() - ".mcmocap_rec".length());
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IOException("Recording names must start with a letter/digit and contain only letters, digits, _, -, or . (max 64 characters).");
        }
        return name;
    }

    private static int parseId(String raw) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
    }

    private static final class Session {
        private final String id;
        private final UUID playerId;
        private final String playerName;
        private final String instantSaveName;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        private long ticks;
        private double lastX, lastY, lastZ;
        private float lastYaw, lastPitch;

        private Session(String id, Player player, String instantSaveName) throws IOException {
            this.id = id;
            this.playerId = player.getUniqueId();
            this.playerName = player.getName();
            this.instantSaveName = instantSaveName == null || instantSaveName.isBlank() ? null : validateName(instantSaveName);
            Location start = player.getLocation();
            lastX = start.getX(); lastY = start.getY(); lastZ = start.getZ();
            lastYaw = start.getYaw(); lastPitch = start.getPitch();
            DataOutputStream out = new DataOutputStream(bytes);
            // Legacy Mocap v1 header: version, XYZ doubles, yaw float, pitch float.
            out.writeByte(1);
            out.writeDouble(lastX); out.writeDouble(lastY); out.writeDouble(lastZ);
            out.writeFloat(lastYaw); out.writeFloat(lastPitch);
        }
    }
}
