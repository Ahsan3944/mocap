package net.mt1006.mocap.paper.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes a Mocap v3 recording stream using the compatible legacy movement actions. The existing Paper parser
 * supports this format, including delta position and pitch/yaw movement actions.
 */
public final class MocapRecordingEncoder {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
    private final DataOutputStream output = new DataOutputStream(bytes);

    public MocapRecordingEncoder(double x, double y, double z, float yaw, float pitch) throws IOException {
        this(x, y, z, yaw, pitch, null);
    }

    public MocapRecordingEncoder(double x, double y, double z, float yaw, float pitch,
                                 String recordedPlayerName) throws IOException {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IOException("Recording start position/rotation must be finite.");
        }
        if (recordedPlayerName != null && !recordedPlayerName.isBlank()
                && !recordedPlayerName.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("Recorded player name must be a valid Minecraft name.");
        }

        // Mocap v3 header: version, XYZ doubles, yaw/pitch floats, format flags,
        // then an assigned-profile section with the real recorded player name.
        output.writeByte(3);
        output.writeDouble(x);
        output.writeDouble(y);
        output.writeDouble(z);
        output.writeFloat(yaw);
        output.writeFloat(pitch);
        int flags = 0x02; // packed-size strings
        if (recordedPlayerName != null && !recordedPlayerName.isBlank()) flags |= 0x10;
        output.writeByte(flags);
        if ((flags & 0x10) != 0) {
            output.writeByte(0x01); // assigned-profile contains a name
            writePackedString(recordedPlayerName);
        }
    }

    private void writePackedString(String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length < 255) {
            output.writeByte(encoded.length);
        } else {
            output.writeByte(255);
            output.writeInt(encoded.length);
        }
        output.write(encoded);
    }

    public void nextTick() throws IOException {
        output.writeByte(0); // NEXT_TICK
    }

    public void movement(double dx, double dy, double dz, float pitch, float yaw, boolean onGround) throws IOException {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Float.isFinite(pitch) || !Float.isFinite(yaw)) {
            throw new IOException("Recording movement/rotation must be finite.");
        }
        output.writeByte(1); // MOVEMENT_LEGACY
        output.writeDouble(dx);
        output.writeDouble(dy);
        output.writeDouble(dz);
        output.writeFloat(pitch);
        output.writeFloat(yaw);
        output.writeBoolean(onGround);
    }

    public void pose(int poseId) throws IOException {
        if (poseId < 1 || poseId > 17) throw new IOException("Unsupported recorded pose ID: " + poseId);
        output.writeByte(3); // CHANGE_POSE
        output.writeInt(poseId);
    }

    public int size() {
        return bytes.size();
    }

    public byte[] toByteArray() throws IOException {
        output.flush();
        return bytes.toByteArray();
    }
}
