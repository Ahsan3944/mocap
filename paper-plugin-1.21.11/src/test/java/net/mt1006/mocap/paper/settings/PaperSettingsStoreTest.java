package net.mt1006.mocap.paper.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperSettingsStoreTest {
    @TempDir Path tempDir;

    @Test
    void exposesOriginalSettingsGroupsAndFields() throws Exception {
        PaperSettingsStore store = new PaperSettingsStore(tempDir);
        assertEquals(List.of("recording", "playback", "advanced"), store.groups());
        assertTrue(store.fields("recording").stream().anyMatch(f -> f.name().equals("chat_recording")));
        assertTrue(store.fields("playback").stream().anyMatch(f -> f.name().equals("playback_speed")));
        assertTrue(store.fields("advanced").stream().anyMatch(f -> f.name().equals("pretty_scene_files")));
    }

    @Test
    void supportedSettingsPersistAcrossStoreInstances() throws Exception {
        PaperSettingsStore first = new PaperSettingsStore(tempDir);
        assertEquals("1.0", first.currentValue(first.definition("playback", "playback_speed")));
        first.set("playback", "playback_speed", "1.5");
        first.set("playback", "invulnerable_playback", "false");
        first.set("playback", "can_push_entities", "false");

        PaperSettingsStore reloaded = new PaperSettingsStore(tempDir);
        assertEquals("1.5", reloaded.currentValue(reloaded.definition("playback", "playback_speed")));
        assertEquals("false", reloaded.currentValue(reloaded.definition("playback", "invulnerable_playback")));
        assertEquals("false", reloaded.currentValue(reloaded.definition("playback", "can_push_entities")));
    }

    @Test
    void unsupportedOriginalSettingsAreVisibleButCannotSilentlyChange() throws Exception {
        PaperSettingsStore store = new PaperSettingsStore(tempDir);
        IOException ex = assertThrows(IOException.class, () -> store.set("recording", "chat_recording", "true"));
        assertTrue(ex.getMessage().contains("not implemented"));
        assertEquals("UNSUPPORTED in Paper", store.currentValue(store.definition("recording", "chat_recording")));
    }

    @Test
    void rejectsInvalidSpeedValuesAndBooleanValues() throws Exception {
        PaperSettingsStore store = new PaperSettingsStore(tempDir);
        assertThrows(IOException.class, () -> store.set("playback", "playback_speed", "5"));
        assertThrows(IOException.class, () -> store.set("playback", "playback_speed", "NaN"));
        assertThrows(IOException.class, () -> store.set("playback", "invulnerable_playback", "maybe"));
    }
}
