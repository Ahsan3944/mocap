package net.mt1006.mocap.paper.equipment;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentPresetStoreTest {
    @TempDir Path tempDir;

    @Test
    void groupsAndPresetNamesAreListedIndependentlyAndSorted() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        store.write("weapons", "diamond_sword", preset(Material.DIAMOND_SWORD));
        store.write("armor", "iron_set", preset(Material.IRON_CHESTPLATE));
        store.write("weapons", "bow", preset(Material.BOW));

        assertEquals(List.of("armor", "weapons"), store.listGroups());
        assertEquals(List.of("iron_set"), store.list("armor"));
        assertEquals(List.of("bow", "diamond_sword"), store.list("weapons"));
        assertEquals(Material.DIAMOND_SWORD, store.get("weapons", "diamond_sword").mainHand().getType());
        assertEquals(Material.IRON_CHESTPLATE, store.get("armor", "iron_set").mainHand().getType());
    }

    @Test
    void deleteOnlyRemovesTheRequestedGroupPreset() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        store.write("armor", "iron", preset(Material.IRON_CHESTPLATE));
        store.write("armor", "diamond", preset(Material.DIAMOND_CHESTPLATE));

        assertTrue(store.delete("armor", "iron"));
        assertEquals(List.of("diamond"), store.list("armor"));
        assertFalse(store.delete("armor", "iron"));
    }

    @Test
    void rejectsUnsafeGroupAndPresetNames() throws Exception {
        EquipmentPresetStore store = new EquipmentPresetStore(tempDir);
        assertThrows(java.io.IOException.class, () -> store.write("../escape", "name", preset(Material.STONE)));
        assertThrows(java.io.IOException.class, () -> store.write("armor", "../escape", preset(Material.STONE)));
    }

    private static EquipmentPresetStore.Preset preset(Material mainHand) {
        return new EquipmentPresetStore.Preset(
                new ItemStack(mainHand), new ItemStack(Material.SHIELD),
                new ItemStack(Material.IRON_HELMET), new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.IRON_LEGGINGS), new ItemStack(Material.IRON_BOOTS));
    }
}
