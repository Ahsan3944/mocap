package net.mt1006.mocap.paper.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileLibraryStoreTest {
    @TempDir Path tempDir;

    @Test
    void savesCommaSeparatedBulkListsWithStableIdsAndSubgroupIsolation() throws Exception {
        ProfileLibraryStore store = new ProfileLibraryStore(tempDir);
        var saved = store.save("names", "soldiers", "check_01", List.of("Steve,Alex,UltraOP"));
        assertEquals(List.of("0001", "0002", "0003"), saved.stream().map(ProfileLibraryStore.Entry::id).toList());
        assertEquals(List.of("soldiers"), store.listGroups("names"));
        assertEquals(List.of("check_01"), store.listSubgroups("names", "soldiers"));
        assertEquals(List.of("Steve", "Alex", "UltraOP"), store.values("names", "soldiers", "check_01"));
        assertTrue(store.values("skins", "soldiers").isEmpty());
    }

    @Test
    void addSkipsDuplicatesAndRemovedIdsAreNotReused() throws Exception {
        ProfileLibraryStore store = new ProfileLibraryStore(tempDir);
        store.save("skins", "official", "march", List.of("Steve,Alex"));
        var added = store.add("skins", "official", "march", List.of("Alex,UltraOP,Builder"));
        assertEquals(List.of("0003", "0004"), added.stream().map(ProfileLibraryStore.Entry::id).toList());

        assertTrue(store.remove("skins", "official", "march", "0002"));
        assertTrue(store.remove("skins", "official", "march", "Builder"));
        var later = store.add("skins", "official", "march", List.of("NewPlayer"));
        assertEquals("0005", later.get(0).id());
        assertEquals(List.of("Steve", "UltraOP", "NewPlayer"), store.values("skins", "official", "march"));
    }

    @Test
    void saveReplacesOnlyTheSelectedSubgroupAndPreservesMonotonicIds() throws Exception {
        ProfileLibraryStore store = new ProfileLibraryStore(tempDir);
        store.save("names", "squad", "one", List.of("Steve,Alex"));
        store.save("names", "squad", "two", List.of("UltraOP"));
        store.save("names", "squad", "one", List.of("Builder"));
        assertEquals(List.of("Builder"), store.values("names", "squad", "one"));
        assertEquals(List.of("Steve", "Alex", "Builder", "UltraOP").size(), store.values("names", "squad").size());
        assertEquals("0003", store.list("names", "squad", "one").get(0).id());
        assertEquals("0001", store.list("names", "squad", "two").get(0).id());
    }

    @Test
    void rejectsInvalidOfficialProfileNamesAndUnsafePaths() throws Exception {
        ProfileLibraryStore store = new ProfileLibraryStore(tempDir);
        assertThrows(IOException.class, () -> store.save("names", "../escape", "x", List.of("Steve")));
        assertThrows(IOException.class, () -> store.save("skins", "valid", "../escape", List.of("Steve")));
        assertThrows(IOException.class, () -> store.save("skins", "valid", "players", List.of("Not A Valid Name")));
        assertThrows(IOException.class, () -> store.save("names", "valid", "players", List.of("ThisNameIsWayTooLongForMojang")));
    }

    @Test
    void acceptsLargeBulkListsUpToThreeThousandUniqueNames() throws Exception {
        ProfileLibraryStore store = new ProfileLibraryStore(tempDir);
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 3000; i++) names.add("Player" + i);
        var saved = store.save("names", "large", "all", names);
        assertEquals(3000, saved.size());
        assertEquals("3000", saved.get(2999).id());
    }
}
