package net.mt1006.mocap.paper.playback;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MainHand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperPlaybackManager {
    public static final int MAX_ACTIVE_MANNEQUINS = 500;
    private static final int MAX_PROFILE_CACHE = 512;

    private final JavaPlugin plugin;
    private final MocapSceneStore scenes;
    private final MocapRecordingParser parser = new MocapRecordingParser();
    private final EquipmentPresetStore equipment;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, ResolvableProfile> profileCache =
            Collections.synchronizedMap(new LinkedHashMap<String, ResolvableProfile>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, ResolvableProfile> eldest) {
                    return size() > MAX_PROFILE_CACHE;
                }
            });
    private int nextId = 1;
    private int activeActors;

    public PaperPlaybackManager(JavaPlugin plugin, MocapSceneStore scenes, EquipmentPresetStore equipment) {
        this.plugin = plugin;
        this.scenes = scenes;
        this.equipment = equipment;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public String start(String playableName, World world, boolean loop, Location anchor) throws IOException {
        return start(playableName, world, loop, anchor, null, null);
    }

    public String start(String playableName, World world, boolean loop, Location anchor,
                        String displayNameOverride, String profileNameOverride) throws IOException {
        List<MocapSceneStore.ActorSpec> specs = scenes.expand(playableName);
        if (activeActors + specs.size() > MAX_ACTIVE_MANNEQUINS) {
            throw new IOException("Paper playback limit is " + MAX_ACTIVE_MANNEQUINS + " active mannequins.");
        }

        List<PreparedActor> prepared = new ArrayList<>(specs.size());
        int duration = 1;
        for (MocapSceneStore.ActorSpec spec : specs) {
            MocapRecordingParser.Recording recording = parser.parse(spec.recordingPath());
            prepared.add(new PreparedActor(spec, recording));
            duration = Math.max(duration, recording.durationTicks() + spec.delayTicks());
        }

        double anchorX = 0, anchorY = 0, anchorZ = 0;
        if (anchor != null && !prepared.isEmpty()) {
            PreparedActor first = prepared.get(0);
            Location initial = locationAt(first, first.recording().frames().get(0), world, 0, 0, 0);
            anchorX = anchor.getX() - initial.getX();
            anchorY = anchor.getY() - initial.getY();
            anchorZ = anchor.getZ() - initial.getZ();
        }

        String id = Integer.toString(nextId++);
        if (nextId < 0) nextId = 1;
        List<Actor> actors = new ArrayList<>(prepared.size());
        try {
            for (PreparedActor preparedActor : prepared) {
                String profileName = firstNonBlank(profileNameOverride, preparedActor.spec().skinProfile(),
                        preparedActor.recording().recordedProfileName(), preparedActor.spec().playerName(),
                        preparedActor.spec().recordingName(), "Steve");
                String displayName = firstNonBlank(displayNameOverride, preparedActor.spec().playerName(),
                        preparedActor.recording().recordedProfileName(), preparedActor.spec().recordingName(), "Mocap");
                Location initial = locationAt(preparedActor, preparedActor.recording().frames().get(0), world,
                        anchorX, anchorY, anchorZ);
                Mannequin mannequin = world.spawn(initial, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, m -> {
                    m.setCustomName(displayName);
                    // Mannequins show a default "NPC" description below the custom name when omitted.
                    // Explicitly clear it so only the recorded/assigned player name is visible.
                    m.setDescription(null);
                    m.setCustomNameVisible(preparedActor.spec().delayTicks() == 0);
                    m.setInvisible(preparedActor.spec().delayTicks() > 0);
                    m.setPersistent(false);
                    m.setInvulnerable(true);
                    m.setSilent(true);
                    m.setGravity(false);
                    m.setCanPickupItems(false);
                    m.setCollidable(false);
                    m.setImmovable(true);
                    m.setMainHand(MainHand.RIGHT);
                    m.setProfile(Mannequin.defaultProfile());
                });
                Actor actor = new Actor(preparedActor.spec(), preparedActor.recording(), mannequin, profileName, displayName,
                        anchorX, anchorY, anchorZ);
                actors.add(actor);
                requestProfile(actor);
                if (preparedActor.spec().delayTicks() == 0) {
                    actor.apply(preparedActor.recording().frames().get(0), false);
                    actor.frameIndex = 0;
                    actor.started = true;
                }
            }
        } catch (RuntimeException e) {
            for (Actor actor : actors) {
                try { actor.mannequin().remove(); } catch (RuntimeException ignored) {}
            }
            throw new IOException("Paper could not spawn all mannequin actors; partial playback was cleaned up.", e);
        }

        Session session = new Session(id, playableName, actors, duration, loop);
        sessions.put(id, session);
        activeActors += actors.size();
        return id;
    }

    private void requestProfile(Actor actor) {
        String profileName = actor.profileName();
        if (profileName == null || !profileName.matches("[A-Za-z0-9_]{1,16}")) return;

        ResolvableProfile profile;
        synchronized (profileCache) {
            String key = profileName.toLowerCase(Locale.ROOT);
            profile = profileCache.get(key);
            if (profile == null) {
                // A name-based resolvable profile lets Paper/client resolve the Mojang skin
                // without server-thread blocking or depending on the deprecated profile type.
                profile = ResolvableProfile.resolvableProfile().name(profileName).build();
                profileCache.put(key, profile);
            }
        }
        try {
            actor.mannequin().setProfile(profile);
        } catch (RuntimeException e) {
            plugin.getLogger().fine("Could not assign resolvable profile " + profileName + ": " + e.getMessage());
        }
    }

    private void tick() {
        if (sessions.isEmpty()) return;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.frozen || session.heldAtEnd) continue;
            session.tick();
            if (session.currentTick >= session.durationTicks) {
                if (session.loop && !session.endFreeze) {
                    session.currentTick = 0;
                    for (Actor actor : session.actors) actor.resetForLoop();
                } else if (session.endFreeze) {
                    session.heldAtEnd = true;
                    session.applyTagVisibility();
                } else {
                    stop(session.id);
                }
            } else {
                session.currentTick++;
            }
        }
    }

    private Location locationAt(PreparedActor prepared, MocapRecordingParser.Frame frame, World world,
                                double anchorX, double anchorY, double anchorZ) {
        Location loc = new Location(world, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (MocapSceneStore.Transform transform : prepared.spec().transforms()) {
            loc = transform.apply(loc, prepared.recording().startX(), prepared.recording().startY(), prepared.recording().startZ());
        }
        loc.add(anchorX, anchorY, anchorZ);
        return loc;
    }

    public boolean stop(String id) {
        Session session = sessions.remove(id);
        if (session == null) return false;
        session.removeActors();
        activeActors = Math.max(0, activeActors - session.actors.size());
        return true;
    }

    public int stopAll() {
        List<String> ids = new ArrayList<>(sessions.keySet());
        for (String id : ids) stop(id);
        return ids.size();
    }

    public List<String> list() {
        List<String> result = new ArrayList<>();
        for (Session s : sessions.values()) {
            result.add(s.id + " " + s.name + " (actors=" + s.actors.size() + ", tick=" + s.currentTick + "/" + s.durationTicks + ")");
        }
        return result;
    }

    public boolean freeze(String id, boolean value) {
        Session s = sessions.get(id);
        if (s == null) return false;
        s.frozen = value;
        return true;
    }

    public boolean endFreeze(String id) {
        Session s = sessions.get(id);
        if (s == null) return false;
        s.endFreeze = true;
        s.loop = false;
        return true;
    }

    public boolean lowLag(String id, boolean enabled) {
        Session s = sessions.get(id);
        if (s == null) return false;
        s.lowLag = enabled;
        for (Actor actor : s.actors) actor.mannequin().setImmovable(enabled);
        return true;
    }

    public boolean nameTags(String id, boolean hidden) {
        Session s = sessions.get(id);
        if (s == null) return false;
        s.nameTagsHidden = hidden;
        s.applyTagVisibility();
        return true;
    }

    public boolean applyPreset(String id, EquipmentPresetStore.Preset preset) {
        Session s = sessions.get(id);
        if (s == null) return false;
        for (Actor actor : s.actors) {
            actor.equipmentOverride = preset;
            EquipmentPresetStore.apply(actor.mannequin(), preset);
        }
        return true;
    }

    public boolean applyPresetToActor(String id, int oneBasedActorIndex, EquipmentPresetStore.Preset preset) {
        Session s = sessions.get(id);
        if (s == null || oneBasedActorIndex < 1 || oneBasedActorIndex > s.actors.size()) return false;
        Actor actor = s.actors.get(oneBasedActorIndex - 1);
        actor.equipmentOverride = preset;
        EquipmentPresetStore.apply(actor.mannequin(), preset);
        return true;
    }

    public boolean randomizePresets(String id, String group) throws IOException {
        Session s = sessions.get(id);
        if (s == null) return false;
        List<String> names = equipment.list(group);
        if (names.isEmpty()) throw new IOException("No presets found in group " + group + ".");
        Random random = new Random();
        for (Actor actor : s.actors) {
            EquipmentPresetStore.Preset preset = equipment.get(group, names.get(random.nextInt(names.size())));
            actor.equipmentOverride = preset;
            EquipmentPresetStore.apply(actor.mannequin(), preset);
        }
        return true;
    }

    public EquipmentPresetStore.Preset randomPreset(String group) throws IOException {
        return equipment.random(group);
    }

    public EquipmentPresetStore.Preset getPreset(String group, String name) throws IOException {
        return equipment.get(group, name);
    }

    public int activeActors() { return activeActors; }
    public boolean exists(String id) { return sessions.containsKey(id); }

    /** One-based actor indexes accepted by apply-element for the given active playback. */
    public List<String> actorIndexes(String id) {
        Session session = sessions.get(id);
        if (session == null) return List.of();
        List<String> indexes = new ArrayList<>(session.actors.size());
        for (int i = 1; i <= session.actors.size(); i++) indexes.add(Integer.toString(i));
        return List.copyOf(indexes);
    }

    /** Current number of mannequin actors in a playback session, or zero if the ID is unknown. */
    public int actorCount(String id) {
        Session session = sessions.get(id);
        return session == null ? 0 : session.actors.size();
    }

    public void shutdown() { stopAll(); }

    private void applyEquipment(Mannequin mannequin, String[] itemIds) {
        EntityEquipment equipment = mannequin.getEquipment();
        if (equipment == null || itemIds.length < 6) return;
        equipment.setItemInMainHand(item(itemIds[0]));
        equipment.setItemInOffHand(item(itemIds[1]));
        equipment.setBoots(item(itemIds[2]));
        equipment.setLeggings(item(itemIds[3]));
        equipment.setChestplate(item(itemIds[4]));
        equipment.setHelmet(item(itemIds[5]));
        // Mocap's BODY and SADDLE slots have no equivalent in a player mannequin.
    }

    private ItemStack item(String raw) {
        if (raw == null || raw.isBlank()) return new ItemStack(Material.AIR);
        Material type = Material.matchMaterial(raw);
        if (type == null || type.isAir()) return new ItemStack(Material.AIR);
        return new ItemStack(type);
    }

    private static String firstNonBlank(String... names) {
        for (String value : names) if (value != null && !value.isBlank()) return value;
        return "Mocap";
    }

    private record PreparedActor(MocapSceneStore.ActorSpec spec, MocapRecordingParser.Recording recording) {}

    private final class Actor {
        private final MocapSceneStore.ActorSpec spec;
        private final MocapRecordingParser.Recording recording;
        private final Mannequin mannequin;
        private final String profileName;
        private final String displayName;
        private final double anchorX, anchorY, anchorZ;
        private int frameIndex = -1;
        private boolean started;
        private EquipmentPresetStore.Preset equipmentOverride;

        private Actor(MocapSceneStore.ActorSpec spec, MocapRecordingParser.Recording recording, Mannequin mannequin,
                      String profileName, String displayName, double anchorX, double anchorY, double anchorZ) {
            this.spec = spec; this.recording = recording; this.mannequin = mannequin;
            this.profileName = profileName; this.displayName = displayName;
            this.anchorX = anchorX; this.anchorY = anchorY; this.anchorZ = anchorZ;
        }

        private void apply(MocapRecordingParser.Frame frame, boolean applyEquipment) {
            Location target = new Location(mannequin.getWorld(), frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
            for (MocapSceneStore.Transform transform : spec.transforms()) {
                target = transform.apply(target, recording.startX(), recording.startY(), recording.startZ());
            }
            target.add(anchorX, anchorY, anchorZ);
            mannequin.teleport(target);
            mannequin.setRotation(frame.yaw(), frame.pitch());
            try {
                Pose pose = Pose.valueOf(frame.pose());
                if (Mannequin.validPoses().contains(pose)) mannequin.setPose(pose);
            } catch (IllegalArgumentException ignored) {}
            mannequin.setMainHand(frame.rightHand() ? MainHand.RIGHT : MainHand.LEFT);
            if (applyEquipment) applyEquipment(mannequin, frame.equipment());
            // User-selected presets override recorded equipment on every frame and after loops.
            if (equipmentOverride != null) EquipmentPresetStore.apply(mannequin, equipmentOverride);
        }

        private void update(int tick) {
            if (tick < spec.delayTicks()) return;
            List<MocapRecordingParser.Frame> frames = recording.frames();
            int recordingTick = tick - spec.delayTicks();
            if (!started) {
                mannequin.setInvisible(false);
                mannequin.setCustomNameVisible(true);
                frameIndex = 0;
                started = true;
                if (!frames.isEmpty()) apply(frames.get(0), true);
                return;
            }
            int previousFrameIndex = frameIndex;
            while (frameIndex + 1 < frames.size() && frames.get(frameIndex + 1).tick() <= recordingTick) frameIndex++;
            if (frameIndex < 0) frameIndex = 0;
            // Frames are snapshots of state changes. Avoid teleporting and reapplying equipment
            // on every server tick when the current keyframe has not changed.
            if (frameIndex != previousFrameIndex && frameIndex < frames.size()) apply(frames.get(frameIndex), true);
        }

        private void resetForLoop() {
            List<MocapRecordingParser.Frame> frames = recording.frames();
            if (spec.delayTicks() > 0) {
                started = false;
                frameIndex = -1;
                mannequin.setInvisible(true);
                mannequin.setCustomNameVisible(false);
                return;
            }
            if (frames.isEmpty()) {
                frameIndex = -1;
                started = false;
                return;
            }
            started = true;
            frameIndex = 0;
            apply(frames.get(0), true);
        }

        private Mannequin mannequin() { return mannequin; }
        private String profileName() { return profileName; }
    }

    private final class Session {
        private final String id;
        private final String name;
        private final List<Actor> actors;
        private final int durationTicks;
        private boolean loop;
        private boolean frozen;
        private boolean endFreeze;
        private boolean heldAtEnd;
        private boolean lowLag = true;
        private boolean nameTagsHidden;
        private int currentTick;

        private Session(String id, String name, List<Actor> actors, int durationTicks, boolean loop) {
            this.id = id; this.name = name; this.actors = actors; this.durationTicks = durationTicks; this.loop = loop;
        }

        private void tick() {
            for (Actor actor : actors) actor.update(currentTick);
        }

        private void applyTagVisibility() {
            for (Actor actor : actors) actor.mannequin().setCustomNameVisible(!nameTagsHidden);
        }

        private void removeActors() {
            for (Actor actor : actors) {
                try { actor.mannequin().remove(); } catch (RuntimeException e) {
                    plugin.getLogger().warning("Could not remove a Mocap mannequin: " + e.getMessage());
                }
            }
        }
    }
}