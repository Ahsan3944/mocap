package net.mt1006.mocap.paper.playback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Defensive reader for Mocap v1-v5 .mcmocap_rec streams.
 * It extracts visible mannequin keyframes and safely consumes actions not represented by Paper's
 * stable Mannequin API. It never executes recorded block/chat/entity actions.
 */
public final class MocapRecordingParser {
    public static final int CURRENT_FORMAT_VERSION = 5;
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ACTIONS = 1_000_000;
    public static final int MAX_KEYFRAMES = 500_000;
    public static final int MAX_TICKS = 720_000;
    private static final int MAX_MAP_ENTRIES = 100_000;
    private static final int MAX_STRING_BYTES = 1_048_576;

    public Recording parse(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0 || size > MAX_FILE_BYTES) {
            throw new IOException("Recording size must be between 1 byte and 64 MiB.");
        }
        return parseBytes(Files.readAllBytes(path));
    }

    public Recording parseBytes(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Recording size is outside the supported range.");
        }
        Reader in = new Reader(bytes);
        byte signedVersion = in.readByte();
        int version = Math.abs((int) signedVersion);
        boolean experimental = signedVersion < 0;
        if (version < 1 || version > CURRENT_FORMAT_VERSION) {
            throw new IOException("Unsupported Mocap recording format version " + version + ".");
        }

        double startX = in.readDouble();
        double startY = in.readDouble();
        double startZ = in.readDouble();
        // Mocap's recording header stores yaw first and pitch second.
        float startYaw = in.readFloat();
        float startPitch = in.readFloat();

        List<String> itemIds = new ArrayList<>();
        itemIds.add("minecraft:air");
        String recordedProfileName = null;
        String embeddedSkinValue = null;
        String embeddedSkinSignature = null;

        if (version != 1 && version != 2) {
            int flags = in.readUnsignedByte();
            in.legacyStrings = (flags & 0x02) == 0;
            if ((flags & 0x04) != 0) {
                int itemCount = checkedCount(in.readInt(), MAX_MAP_ENTRIES, "item map");
                for (int i = 0; i < itemCount; i++) itemIds.add(in.readString());
                int blockCount = checkedCount(in.readInt(), MAX_MAP_ENTRIES, "block-state map");
                for (int i = 0; i < blockCount; i++) {
                    in.readString(); // block identifier
                    int propertyCount = in.readShort();
                    if (propertyCount < 0 || propertyCount > 1024) throw new IOException("Invalid block-state property count.");
                    for (int p = 0; p < propertyCount; p++) {
                        in.readString();
                        in.readString();
                    }
                }
            }
            if ((flags & 0x08) != 0) in.readString(); // assigned dimension
            if ((flags & 0x10) != 0) {
                int profileFlags = in.readUnsignedByte();
                if ((profileFlags & 0x01) != 0) recordedProfileName = in.readString();
                if ((profileFlags & 0x02) != 0) { in.readLong(); in.readLong(); }
                if ((profileFlags & 0x04) != 0) embeddedSkinValue = in.readString();
                if ((profileFlags & 0x08) != 0) embeddedSkinSignature = in.readString();
            }
            if ((flags & 0x20) != 0) {
                int extensions = in.readUnsignedByte();
                if (extensions > 255) throw new IOException("Invalid extension header count.");
                for (int i = 0; i < extensions; i++) {
                    in.readString();
                    in.readShort();
                    in.readByte();
                    int headerSize = in.readPackedInt();
                    in.skipChecked(headerSize);
                }
            }
            boolean hasExperimentalSubversion = (flags & 0x40) != 0;
            if (hasExperimentalSubversion) in.readUnsignedByte();
            if (experimental && version == 5 && !hasExperimentalSubversion) {
                throw new IOException("This experimental recording uses an obsolete incompatible format.");
            }
        }

        State state = new State(startX, startY, startZ, startYaw, startPitch);
        List<Frame> frames = new ArrayList<>();
        state.snapshot(frames, 0);
        int tick = 0;
        int actionCount = 0;
        while (in.hasRemaining()) {
            if (++actionCount > MAX_ACTIONS) throw new IOException("Recording contains too many actions.");
            int id = in.readUnsignedByte();
            int advance = decodeAction(in, id, state, frames, tick, itemIds, true, 0);
            if (advance > 0) tick = safeAddTicks(tick, advance);
            if (tick > MAX_TICKS) throw new IOException("Recording duration exceeds the 10-hour safety limit.");
        }
        int duration = Math.max(1, tick);
        if (frames.isEmpty()) state.snapshot(frames, 0);
        return new Recording(version, experimental, startX, startY, startZ, startYaw, startPitch,
                recordedProfileName, embeddedSkinValue, embeddedSkinSignature, duration, List.copyOf(frames));
    }

    private static int decodeAction(Reader in, int id, State state, List<Frame> frames, int tick,
                                    List<String> itemIds, boolean apply, int depth) throws IOException {
        if (depth > 32) throw new IOException("Nested entity action depth exceeded the safety limit.");
        switch (id) {
            case 0: // NEXT_TICK
                return 1;
            case 1: { // MOVEMENT_LEGACY: additive position, absolute rotation
                double dx = in.readDouble(), dy = in.readDouble(), dz = in.readDouble();
                float pitch = in.readFloat(), yaw = in.readFloat();
                in.readBoolean();
                if (apply) {
                    state.x += dx; state.y += dy; state.z += dz;
                    state.pitch = pitch; state.yaw = yaw;
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 2: // separate head rotation; Mannequin has no public head-only rotation control
                in.readFloat();
                return 0;
            case 3: { // CHANGE_POSE
                int poseId = in.readInt();
                if (apply) {
                    state.pose = poseName(poseId);
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 4: { // CHANGE_ITEM
                int first = in.readByte();
                int itemCount;
                if (first >= 0) {
                    in.rewindOne();
                    itemCount = 6; // legacy six-slot representation
                } else {
                    itemCount = first == Byte.MIN_VALUE ? 0 : -first;
                }
                if (itemCount > 8) throw new IOException("Invalid equipment slot count.");
                String[] newItems = apply ? state.items.clone() : null;
                for (int i = 0; i < itemCount; i++) {
                    int type = in.readUnsignedByte();
                    if (type < 0 || type > 3) throw new IOException("Invalid item payload type.");
                    int itemId = (type == 1 || type == 2 || type == 3) ? in.readInt() : 0;
                    if (type == 2 || type == 3) in.readString();
                    if (apply && newItems != null) {
                        newItems[i] = itemId >= 0 && itemId < itemIds.size() ? itemIds.get(itemId) : "minecraft:air";
                    }
                }
                if (apply && newItems != null) {
                    state.items = newItems;
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 5: // SET_ENTITY_FLAGS
            case 6: // SET_LIVING_ENTITY_FLAGS
                in.readByte();
                return 0;
            case 7: { // SET_MAIN_HAND: true=right, false=left
                boolean right = in.readBoolean();
                if (apply) {
                    state.rightHand = right;
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 8: // SWING (Paper Mannequin API does not expose recorded player swing animation)
                in.readBoolean();
                return 0;
            case 9: // BREAK_BLOCK: state id + block pos
                in.readInt(); skipBlockPos(in); return 0;
            case 10: // PLACE_BLOCK: old state + new state + block pos
            case 15: // PLACE_BLOCK_SILENTLY
                in.readInt(); in.readInt(); skipBlockPos(in); return 0;
            case 11: // RIGHT_CLICK_BLOCK
                skipVec3(in); skipBlockPos(in); in.readByte(); in.readBoolean(); in.readBoolean(); return 0;
            case 12: // SET_EFFECT_COLOR
                in.readInt(); in.readBoolean(); return 0;
            case 13: // SET_ARROW_COUNT
                in.readInt(); in.readInt(); return 0;
            case 14: { // SLEEP
                boolean sleeping = in.readBoolean();
                if (sleeping) skipBlockPos(in);
                if (apply) {
                    state.pose = sleeping ? "SLEEPING" : "STANDING";
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 16: { // ENTITY_UPDATE
                int type = in.readUnsignedByte();
                in.readInt();
                if (type == 1) { in.readString(); skipVec3(in); }
                return 0;
            }
            case 17: { // ENTITY_ACTION; consume its nested action but don't apply it to the mannequin
                in.readInt();
                int nestedId = in.readUnsignedByte();
                decodeAction(in, nestedId, state, frames, tick, itemIds, false, depth + 1);
                return 0;
            }
            case 18: // HURT
                in.readByte(); return 0;
            case 19: { // deprecated SET_VEHICLE_DATA
                if (in.readBoolean()) {
                    in.readByte(); in.readBoolean(); in.readBoolean();
                    in.readInt(); in.readInt(); in.readInt(); in.readFloat();
                }
                return 0;
            }
            case 20: // BREAK_BLOCK_PROGRESS
                skipBlockPos(in); in.readInt(); return 0;
            case 21: { // MOVEMENT delta stream
                int flags = in.readUnsignedByte();
                if ((flags & 0x80) != 0) throw new IOException("Reserved movement flag bit is set.");
                int yMode = flags & 0x03;
                double dy = switch (yMode) {
                    case 1 -> in.readFloat();
                    case 2 -> in.readDouble();
                    case 3 -> in.readShort() / 2.0;
                    default -> 0.0;
                };
                int xzMode = flags & 0x0C;
                if (xzMode == 0x0C) throw new IOException("Reserved movement X/Z encoding is not supported.");
                double dx = xzMode == 0x04 ? in.readFloat() : xzMode == 0x08 ? in.readDouble() : 0.0;
                double dz = xzMode == 0x04 ? in.readFloat() : xzMode == 0x08 ? in.readDouble() : 0.0;
                int rotationMode = flags & 0x30;
                float pitch = state.pitch, yaw = state.yaw;
                if (rotationMode != 0) {
                    pitch = unpackRotation(in.readShort());
                    yaw = unpackRotation(in.readShort());
                    if (rotationMode == 0x30) in.readShort(); // separate head yaw is not exposed by the stable API
                }
                if (apply) {
                    if (xzMode != 0) { state.x = state.startX + dx; state.z = state.startZ + dz; }
                    if (yMode == 1 || yMode == 2) state.y = state.startY + dy;
                    else if (yMode == 3) state.y = dy;
                    if (rotationMode != 0) { state.pitch = pitch; state.yaw = yaw; }
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 22: // SKIP_TICKS
                return in.readUnsignedByte();
            case 23: // DIE
            case 24: { // RESPAWN
                if (apply) {
                    state.pose = id == 23 ? "DYING" : "STANDING";
                    state.snapshot(frames, tick);
                }
                return 0;
            }
            case 25: // CHAT_MESSAGE
                in.readByte(); in.readString(); return 0;
            case 26: // SET_SPECTATOR
                in.readBoolean(); return 0;
            case 27: // DUMMY / unknown extension fallback
            case 28: // CLOSE_CONTAINER
                return 0;
            case 29: { // SET_EFFECT_PARTICLES
                int count = checkedCount(in.readPackedInt(), 100_000, "particle list");
                for (int i = 0; i < count; i++) in.readString();
                in.readBoolean();
                return 0;
            }
            case 30: { // SET_NON_PLAYER_ENTITY_DATA
                int flags = in.readUnsignedByte();
                if ((flags & 0x04) != 0) in.readByte();
                if ((flags & 0x08) != 0) in.readPackedInt();
                if ((flags & 0x10) != 0) in.readPackedInt();
                if ((flags & 0x20) != 0) in.readPackedInt();
                if ((flags & 0x40) != 0) in.readFloat();
                return 0;
            }
            case 255: { // custom extension action is length-prefixed, so it can be safely skipped
                in.readUnsignedByte(); // extension id
                in.readUnsignedByte(); // extension action id
                int length = in.readPackedInt();
                in.skipChecked(length);
                return 0;
            }
            default:
                throw new IOException("Unsupported Mocap action id " + id + "; playback was not started.");
        }
    }

    private static int checkedCount(int count, int max, String label) throws IOException {
        if (count < 0 || count > max) throw new IOException("Invalid " + label + " size.");
        return count;
    }

    private static int safeAddTicks(int tick, int advance) throws IOException {
        if (advance < 0 || tick > MAX_TICKS - advance) throw new IOException("Invalid recording tick count.");
        return tick + advance;
    }

    private static void skipVec3(Reader in) throws IOException {
        in.readDouble(); in.readDouble(); in.readDouble();
    }

    private static void skipBlockPos(Reader in) throws IOException {
        in.readInt(); in.readInt(); in.readInt();
    }

    private static float unpackRotation(short packed) {
        return (float) (((double) packed / 65536.0) * 360.0);
    }

    private static String poseName(int id) {
        return switch (id) {
            case 1 -> "STANDING";
            case 2 -> "FALL_FLYING";
            case 3 -> "SLEEPING";
            case 4 -> "SWIMMING";
            case 5 -> "SPIN_ATTACK";
            case 6 -> "CROUCHING";
            case 7 -> "DYING";
            case 8 -> "LONG_JUMPING";
            case 9 -> "CROAKING";
            case 10 -> "USING_TONGUE";
            case 11 -> "SITTING";
            case 12 -> "ROARING";
            case 13 -> "SNIFFING";
            case 14 -> "EMERGING";
            case 15 -> "DIGGING";
            case 16 -> "SLIDING";
            case 17 -> "SHOOTING";
            case 18 -> "INHALING";
            default -> "STANDING";
        };
    }

    public record Recording(int formatVersion, boolean experimental, double startX, double startY, double startZ,
                            float startYaw, float startPitch, String recordedProfileName, String embeddedSkinValue,
                            String embeddedSkinSignature, int durationTicks, List<Frame> frames) {}

    public record Frame(int tick, double x, double y, double z, float yaw, float pitch, String pose,
                        String[] equipment, boolean rightHand) {
        public Frame {
            equipment = equipment.clone();
        }
        @Override public String[] equipment() { return equipment.clone(); }
    }

    private static final class State {
        private final double startX, startY, startZ;
        private double x, y, z;
        private float yaw, pitch;
        private String pose = "STANDING";
        private String[] items = {
                "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air",
                "minecraft:air", "minecraft:air", "minecraft:air", "minecraft:air"
        };
        private boolean rightHand = true;

        private State(double x, double y, double z, float yaw, float pitch) {
            this.startX = this.x = x;
            this.startY = this.y = y;
            this.startZ = this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        private void snapshot(List<Frame> frames, int tick) throws IOException {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IOException("Recording contains a non-finite position or rotation.");
            }
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 30_000_000) {
                throw new IOException("Recording position exceeds the world-coordinate safety limit.");
            }
            if (frames.size() >= MAX_KEYFRAMES && (frames.isEmpty() || frames.get(frames.size() - 1).tick() != tick)) {
                throw new IOException("Recording contains too many visual keyframes.");
            }
            Frame frame = new Frame(tick, x, y, z, yaw, pitch, pose, items, rightHand);
            if (!frames.isEmpty() && frames.get(frames.size() - 1).tick() == tick) {
                frames.set(frames.size() - 1, frame);
            } else {
                frames.add(frame);
            }
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;
        private boolean legacyStrings = true;

        private Reader(byte[] data) { this.data = data; }
        private boolean hasRemaining() { return offset < data.length; }
        private byte readByte() throws IOException { require(1); return data[offset++]; }
        private int readUnsignedByte() throws IOException { return readByte() & 0xFF; }
        private short readShort() throws IOException { require(2); int v = ((data[offset]&255)<<8)|(data[offset+1]&255); offset+=2; return (short)v; }
        private int readInt() throws IOException {
            require(4); int v=((data[offset]&255)<<24)|((data[offset+1]&255)<<16)|((data[offset+2]&255)<<8)|(data[offset+3]&255); offset+=4; return v;
        }
        private long readLong() throws IOException {
            require(8); long v=((long)(data[offset]&255)<<56)|((long)(data[offset+1]&255)<<48)|((long)(data[offset+2]&255)<<40)|((long)(data[offset+3]&255)<<32)|((long)(data[offset+4]&255)<<24)|((long)(data[offset+5]&255)<<16)|((long)(data[offset+6]&255)<<8)|(long)(data[offset+7]&255); offset+=8; return v;
        }
        private float readFloat() throws IOException { return Float.intBitsToFloat(readInt()); }
        private double readDouble() throws IOException { return Double.longBitsToDouble(readLong()); }
        private boolean readBoolean() throws IOException { return readByte() == 1; }
        private int readPackedInt() throws IOException { int v=readUnsignedByte(); return v==255 ? readInt() : v; }
        private String readString() throws IOException {
            int length = legacyStrings ? readInt() : readPackedInt();
            if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid recording string length.");
            require(length);
            String value = new String(data, offset, length, StandardCharsets.UTF_8);
            offset += length; return value;
        }
        private void rewindOne() throws IOException {
            if (offset <= 0) throw new IOException("Cannot rewind before start of recording.");
            offset--;
        }
        private void skipChecked(int length) throws IOException {
            if (length < 0) throw new IOException("Negative payload length.");
            require(length); offset += length;
        }
        private void require(int length) throws IOException {
            if (length < 0 || offset < 0 || offset > data.length - length) throw new IOException("Recording is truncated or malformed.");
        }
    }
}