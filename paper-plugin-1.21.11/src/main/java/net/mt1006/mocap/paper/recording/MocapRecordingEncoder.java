package net.mt1006.mocap.paper.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes the Mocap v1 legacy recording stream. The existing Paper parser
 * supports this format, including delta position and pitch/yaw movement actions.
 */
public final class MocapRecordingEncoder {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
    private final DataOutputStream output = new DataOutputStream(bytes);

    public MocapRecordingEncoder(double x, double y, double z, float yaw, float pitch) throws IOException {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IOException("Recording start position/rotation must be finite.");
        }
        // Mocap v1 header: version, XYZ doubles, yaw float, pitch float.
        output.writeByte(1);
        output.writeDouble(x);
        output.writeDouble(y);
        output.writeDouble(z);
        output.writeFloat(yaw);
        output.writeFloat(pitch);
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
