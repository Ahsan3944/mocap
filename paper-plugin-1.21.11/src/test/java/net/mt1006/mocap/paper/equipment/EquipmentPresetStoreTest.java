package net.mt1006.mocap.paper.equipment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentPresetStoreTest {
    @TempDir Path tempDir;

    @Test
    void groupsAndPresetNamesAreListedIndependentlyAndSorted() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        Path root = tempDir.resolve("equipment-presets");
        Files.createDirectories(root.resolve("weapons"));
        Files.createDirectories(root.resolve("armor"));
        Files.writeString(root.resolve("weapons").resolve("diamond_sword.yml"), "# saved preset fixture");
        Files.writeString(root.resolve("weapons").resolve("bow.yml"), "# saved preset fixture");
        Files.writeString(root.resolve("armor").resolve("iron_set.yml"), "# saved preset fixture");

        assertEquals(List.of("armor", "weapons"), store.listGroups());
        assertEquals(List.of("iron_set"), store.list("armor"));
        assertEquals(List.of("bow", "diamond_sword"), store.list("weapons"));
        assertTrue(store.list("missing-group").isEmpty());
    }

    @Test
    void deleteOnlyRemovesTheRequestedGroupPreset() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        Path group = tempDir.resolve("equipment-presets").resolve("armor");
        Files.createDirectories(group);
        Files.writeString(group.resolve("iron.yml"), "# saved preset fixture");
        Files.writeString(group.resolve("diamond.yml"), "# saved preset fixture");

        assertTrue(store.delete("armor", "iron"));
        assertEquals(List.of("diamond"), store.list("armor"));
        assertFalse(store.delete("armor", "iron"));
    }

    @Test
    void rejectsUnsafeGroupAndPresetNamesBeforeReadingOrWritingAnyPreset() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        assertThrows(IOException.class, () -> store.get("../escape", "name"));
        assertThrows(IOException.class, () -> store.get("armor", "../escape"));
        assertThrows(IOException.class, () -> store.list("../escape"));
    }
}
