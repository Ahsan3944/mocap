package net.mt1006.mocap.paper.playback;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class MocapRecordingParserTest {
    private final MocapRecordingParser parser = new MocapRecordingParser();

    @Test void parsesLegacyHeaderAndNextTickAction() throws Exception {
        var recording = parser.parseBytes(legacyRecord(new byte[] {0}));
        assertEquals(2, recording.formatVersion());
        assertFalse(recording.experimental());
        assertEquals(1, recording.durationTicks());
        assertEquals(1, recording.frames().size());
        assertEquals(10.0, recording.frames().get(0).x());
        assertEquals(64.0, recording.frames().get(0).y());
    }

    @Test void preservesHeaderYawThenPitchOrder() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 2, false, 37.0f, -12.0f);
        out.writeByte(0);
        var frame = parser.parseBytes(bytes.toByteArray()).frames().get(0);
        assertEquals(37.0f, frame.yaw(), 0.01f);
        assertEquals(-12.0f, frame.pitch(), 0.01f);
    }

    @Test void decodesLegacyMovementPitchThenYaw() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 2, false, 37.0f, -12.0f);
        out.writeByte(1); // MOVEMENT_LEGACY
        out.writeDouble(1.0);
        out.writeDouble(2.0);
        out.writeDouble(3.0);
        out.writeFloat(7.0f);  // legacy action stores pitch first
        out.writeFloat(80.0f); // then yaw
        out.writeBoolean(true);
        var frame = parser.parseBytes(bytes.toByteArray()).frames().get(0);
        assertEquals(11.0, frame.x());
        assertEquals(66.0, frame.y());
        assertEquals(13.0, frame.z());
        assertEquals(80.0f, frame.yaw(), 0.01f);
        assertEquals(7.0f, frame.pitch(), 0.01f);
    }

    @Test void rejectsReservedMovementEncoding() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 2, false);
        out.writeByte(21); // MOVEMENT
        out.writeByte(0x0C); // reserved X/Z encoding
        assertThrows(IOException.class, () -> parser.parseBytes(bytes.toByteArray()));
    }

    @Test void decodesMovementDeltaAndRotation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 2, false);
        out.writeByte(21);
        out.writeByte(0x6A);
        out.writeDouble(1.0);
        out.writeDouble(2.0);
        out.writeDouble(3.0);
        out.writeShort(0);
        out.writeShort(16384);
        var frame = parser.parseBytes(bytes.toByteArray()).frames().get(0);
        assertEquals(12.0, frame.x());
        assertEquals(65.0, frame.y());
        assertEquals(13.0, frame.z());
        assertEquals(90.0f, frame.yaw(), 0.01f);
        assertEquals(0.0f, frame.pitch(), 0.01f);
    }

    @Test void parsesCurrentHeaderFlags() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 5, true);
        out.writeByte(0);
        assertEquals(5, parser.parseBytes(bytes.toByteArray()).formatVersion());
    }

    @Test void rejectsTruncatedHeader() {
        assertThrows(IOException.class, () -> parser.parseBytes(new byte[] {2}));
    }

    @Test void rejectsUnsupportedFormatVersion() {
        assertThrows(IOException.class, () -> parser.parseBytes(new byte[] {99}));
    }

    @Test void rejectsUnknownActionInsteadOfMisaligningStream() throws Exception {
        assertThrows(IOException.class, () -> parser.parseBytes(legacyRecord(new byte[] {99})));
    }

    @Test void rejectsEmptyInput() {
        assertThrows(IOException.class, () -> parser.parseBytes(new byte[0]));
    }

    private static byte[] legacyRecord(byte[] actions) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeHeader(out, 2, false);
        out.write(actions);
        return bytes.toByteArray();
    }

    private static void writeHeader(DataOutputStream out, int version, boolean currentHeader) throws IOException {
        writeHeader(out, version, currentHeader, 0.0f, 0.0f);
    }

    private static void writeHeader(DataOutputStream out, int version, boolean currentHeader, float yaw, float pitch) throws IOException {
        out.writeByte(version);
        out.writeDouble(10); out.writeDouble(64); out.writeDouble(10);
        out.writeFloat(yaw); out.writeFloat(pitch);
        if (currentHeader) out.writeByte(0x02);
    }
}
