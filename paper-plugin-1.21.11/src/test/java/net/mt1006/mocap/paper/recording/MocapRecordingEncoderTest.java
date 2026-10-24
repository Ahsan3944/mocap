package net.mt1006.mocap.paper.recording;

import net.mt1006.mocap.paper.playback.MocapRecordingParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MocapRecordingEncoderTest {
    @Test
    void writesLegacyRecordingThatThePlaybackParserCanRead() throws Exception {
        MocapRecordingEncoder encoder = new MocapRecordingEncoder(10.0, 64.0, 10.0, 37.0f, -12.0f);
        encoder.nextTick();
        encoder.movement(1.25, 0.5, -3.0, -5.0f, 90.0f, true);
        encoder.pose(6); // CROUCHING

        MocapRecordingParser.Recording recording = new MocapRecordingParser().parseBytes(encoder.toByteArray());

        assertEquals(1, recording.formatVersion());
        assertEquals(1, recording.durationTicks());
        assertTrue(recording.frames().size() >= 2);
        var start = recording.frames().get(0);
        assertEquals(10.0, start.x(), 0.0001);
        assertEquals(64.0, start.y(), 0.0001);
        assertEquals(37.0f, start.yaw(), 0.001f);
        assertEquals(-12.0f, start.pitch(), 0.001f);

        var moved = recording.frames().stream()
                .filter(frame -> Math.abs(frame.x() - 11.25) < 0.0001)
                .findFirst().orElseThrow();
        assertEquals(64.5, moved.y(), 0.0001);
        assertEquals(7.0, moved.z(), 0.0001);
        assertEquals(90.0f, moved.yaw(), 0.001f);
        assertEquals(-5.0f, moved.pitch(), 0.001f);
        assertEquals("CROUCHING", recording.frames().get(recording.frames().size() - 1).pose());
    }

    @Test
    void rejectsNonFiniteStartCoordinates() {
        assertThrows(java.io.IOException.class,
                () -> new MocapRecordingEncoder(Double.NaN, 64.0, 0.0, 0.0f, 0.0f));
    }
}
