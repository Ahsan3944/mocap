package net.mt1006.mocap.paper.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MocapSceneStoreTest {
    @TempDir Path tempDir;

    @Test
    void rejectsFutureSceneFormatVersion() throws Exception {
        Path scenesDir = tempDir.resolve("mocap_files").resolve("scenes");
        Files.createDirectories(scenesDir);
        Files.writeString(scenesDir.resolve("future.mcmocap_scene"),
                "{\"version\":5,\"subscenes\":[]}", StandardCharsets.UTF_8);
        MocapSceneStore store = new MocapSceneStore(tempDir);
        IOException ex = assertThrows(IOException.class, () -> store.readScene(".future"));
        assertTrue(ex.getMessage().contains("Unsupported Mocap scene format version"));
    }

    @Test
    void rejectsPathTraversalNames() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        assertThrows(IOException.class, () -> store.scenePath("../outside"));
        assertThrows(IOException.class, () -> store.recordingPath("../outside"));
    }

    @Test
    void createsSceneAddsRecordingAndPersistsUpstreamModifiers() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        Files.write(recordingDir.resolve("alex_run.mcmocap_rec"), new byte[]{1, 2, 3});

        store.createScene("battle");
        store.addSceneElement("battle", "alex_run", 1.5, "Alex", "from_player", "Steve");
        store.modifySceneElement("battle", "001-alex_run", "transformations",
                new String[]{"offset", "2.5", "0", "-3"});
        store.modifySceneElement("battle", "001-alex_run", "time",
                new String[]{"start_delay", "0.5"});
        store.modifySceneElement("battle", "001-alex_run", "player_name", new String[]{"set", "UltraOP"});

        var scene = store.readScene(".battle");
        var elements = scene.getAsJsonArray("subscenes");
        assertEquals(1, elements.size());
        var element = elements.get(0).getAsJsonObject();
        assertEquals("alex_run", element.get("name").getAsString());
        assertEquals("UltraOP", element.get("player_name").getAsString());
        assertEquals("from_player", element.getAsJsonObject("player_skin").get("skin_source").getAsString());
        assertEquals("Steve", element.getAsJsonObject("player_skin").get("skin_path").getAsString());
        assertEquals(1.5, element.getAsJsonObject("time").get("wait_on_start").getAsDouble(), 0.0001);
        assertEquals(0.5, element.getAsJsonObject("time").get("start_delay").getAsDouble(), 0.0001);
        var offset = element.getAsJsonObject("transformations").getAsJsonArray("offset");
        assertEquals(2.5, offset.get(0).getAsDouble(), 0.0001);
        assertEquals(0, offset.get(1).getAsDouble(), 0.0001);
        assertEquals(-3, offset.get(2).getAsDouble(), 0.0001);
        assertEquals(List.of("001-alex_run"), store.sceneElementSuggestions("battle"));
    }

    @Test
    void sceneCanBeRenamedAndRemovedWithoutOverwriting() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        store.createScene("first");
        store.renameScene("first", "second");
        assertTrue(Files.isRegularFile(tempDir.resolve("mocap_files").resolve("scenes").resolve("second.mcmocap_scene")));
        assertFalse(Files.exists(tempDir.resolve("mocap_files").resolve("scenes").resolve("first.mcmocap_scene")));
        assertThrows(IOException.class, () -> store.createScene("second"));
        store.removeScene("second");
        assertFalse(Files.exists(tempDir.resolve("mocap_files").resolve("scenes").resolve("second.mcmocap_scene")));
    }

    @Test
    void removesSceneElementByIndexAndValidatesName() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        Files.write(recordingDir.resolve("first.mcmocap_rec"), new byte[]{1});
        store.createScene("test");
        store.addSceneElement("test", "first", 0, null, "default", null);
        assertThrows(IOException.class, () -> store.removeSceneElement("test", "001-other"));
        store.removeSceneElement("test", "001-first");
        assertTrue(store.readScene("test").getAsJsonArray("subscenes").isEmpty());
    }

    @Test
    void nestedSceneExpandsAndParentOverrideIsAppliedWithoutMutatingChild() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordings = tempDir.resolve("mocap_files").resolve("recordings");
        Files.write(recordings.resolve("walk.mcmocap_rec"), new byte[]{1, 2, 3});

        store.createScene("child");
        store.addSceneElement("child", "walk", 0, "ChildActor", "default", null);
        store.createScene("parent");
        store.addSceneElement("parent", ".child", 0, "ParentOverride", "from_player", "Steve");

        var actors = store.expand(".parent");
        assertEquals(1, actors.size());
        // The parent stores overrides on the nested-scene element. Expansion keeps the child data unchanged.
        assertEquals("ParentOverride", store.readScene("parent").getAsJsonArray("subscenes")
                .get(0).getAsJsonObject().get("player_name").getAsString());
        assertEquals("Steve", store.readScene("parent").getAsJsonArray("subscenes")
                .get(0).getAsJsonObject().getAsJsonObject("player_skin").get("skin_path").getAsString());

        // A parent-level override changes the expansion result only. The stored child remains unchanged.
        var child = store.readScene("child").getAsJsonArray("subscenes").get(0).getAsJsonObject();
        assertEquals("ChildActor", child.get("player_name").getAsString());
        assertFalse(child.has("player_skin"));
    }

    @Test
    void nestedSceneDelayAndTransformCompositionAreExpanded() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordings = tempDir.resolve("mocap_files").resolve("recordings");
        Files.write(recordings.resolve("walk.mcmocap_rec"), new byte[]{1, 2, 3});

        store.createScene("child");
        store.addSceneElement("child", "walk", 1.0, null, "default", null);
        store.createScene("parent");
        store.addSceneElement("parent", ".child", 2.0, null, "default", null);
        store.modifySceneElement("parent", "001-.child", "transformations",
                new String[]{"offset", "10", "0", "5"});
        var actor = store.expand(".parent").get(0);
        assertEquals(60, actor.delayTicks()); // 3 seconds × 20 ticks/second
        assertEquals(1, actor.transforms().size());
        assertEquals(10, actor.transforms().get(0).x(), 0.0001);
        assertEquals(5, actor.transforms().get(0).z(), 0.0001);
    }

    @Test
    void rejectsRecursiveAndTooDeepNestedScenesWithoutReturningPartialActors() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordings = tempDir.resolve("mocap_files").resolve("recordings");
        Files.write(recordings.resolve("walk.mcmocap_rec"), new byte[]{1, 2, 3});

        store.createScene("self");
        store.addSceneElement("self", "walk", 0, null, "default", null);
        store.modifySceneElement("self", "001-walk", "subscene_name", new String[]{".self"});
        IOException cycle = assertThrows(IOException.class, () -> store.expand(".self"));
        assertTrue(cycle.getMessage().contains("Recursive scene"));

        store.createScene("leaf");
        store.addSceneElement("leaf", "walk", 0, null, "default", null);
        for (int i = 0; i < 34; i++) {
            String parent = "nested_" + i;
            store.createScene(parent);
            store.addSceneElement(parent, i == 0 ? ".leaf" : ".nested_" + (i - 1), 0, null, "default", null);
        }
        IOException depth = assertThrows(IOException.class, () -> store.expand(".nested_33"));
        assertTrue(depth.getMessage().contains("Nested scene depth"));
    }

    @Test
    void knownProfilesIncludeRecordedPlayersForRandomSkinAutocomplete() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordings = tempDir.resolve("mocap_files").resolve("recordings");
        var encoder = new net.mt1006.mocap.paper.recording.MocapRecordingEncoder(0, 64, 0, 0, 0, "OriginalPlayer");
        Files.write(recordings.resolve("original_take.mcmocap_rec"), encoder.toByteArray());

        assertTrue(store.knownProfiles().contains("OriginalPlayer"));
        // The result is cached until the underlying recording/scene signature changes.
        assertTrue(store.knownProfiles().contains("OriginalPlayer"));
    }

    @Test
    void formationKeepsSourceActorAtFirstCornerAndGrowsRightThenBehind() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        store.createScene("formation");
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        var encoder = new net.mt1006.mocap.paper.recording.MocapRecordingEncoder(100, 64, 200, 0, 0, "UltraOP");
        Files.write(recordingDir.resolve("warrior.mcmocap_rec"), encoder.toByteArray());

        store.addFormation("formation", "warrior", 2, 3, 2.0);
        var entries = store.readScene("formation").getAsJsonArray("subscenes");
        assertEquals(6, entries.size());

        var first = entries.get(0).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var secondColumn = entries.get(1).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var thirdColumn = entries.get(2).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var nextRow = entries.get(3).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        assertEquals(0.0, first.get(0).getAsDouble(), 0.0001);
        assertEquals(0.0, first.get(2).getAsDouble(), 0.0001);
        // Yaw 0 faces +Z; the actor's right is -X and behind is -Z.
        assertEquals(-2.0, secondColumn.get(0).getAsDouble(), 0.0001);
        assertEquals(0.0, secondColumn.get(2).getAsDouble(), 0.0001);
        assertEquals(-4.0, thirdColumn.get(0).getAsDouble(), 0.0001);
        assertEquals(0.0, thirdColumn.get(2).getAsDouble(), 0.0001);
        assertEquals(0.0, nextRow.get(0).getAsDouble(), 0.0001);
        assertEquals(-2.0, nextRow.get(2).getAsDouble(), 0.0001);
    }

    @Test
    void formationSupportsIndependentSideAndFrontBackGaps() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        store.createScene("wide_formation");
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        var encoder = new net.mt1006.mocap.paper.recording.MocapRecordingEncoder(10, 64, 10, 0, 0, "UltraOP");
        Files.write(recordingDir.resolve("line.mcmocap_rec"), encoder.toByteArray());

        store.addFormation("wide_formation", "line", 2, 3, 1.5, 4.0);
        var entries = store.readScene("wide_formation").getAsJsonArray("subscenes");
        assertEquals(6, entries.size());
        var first = entries.get(0).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var secondColumn = entries.get(1).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var thirdColumn = entries.get(2).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var nextRow = entries.get(3).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");
        var nextRowSecondColumn = entries.get(4).getAsJsonObject().getAsJsonObject("transformations").getAsJsonArray("offset");

        assertEquals(0.0, first.get(0).getAsDouble(), 0.0001);
        assertEquals(0.0, first.get(2).getAsDouble(), 0.0001);
        assertEquals(-1.5, secondColumn.get(0).getAsDouble(), 0.0001);
        assertEquals(0.0, secondColumn.get(2).getAsDouble(), 0.0001);
        assertEquals(-3.0, thirdColumn.get(0).getAsDouble(), 0.0001);
        assertEquals(-4.0, nextRow.get(2).getAsDouble(), 0.0001);
        assertEquals(-1.5, nextRowSecondColumn.get(0).getAsDouble(), 0.0001);
        assertEquals(-4.0, nextRowSecondColumn.get(2).getAsDouble(), 0.0001);
    }

    @Test
    void formationGroupCreatesSeparateGroupMetadataAndAnchoredScene() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        var encoder = new net.mt1006.mocap.paper.recording.MocapRecordingEncoder(10, 64, 20, 0, 0, "Warrior");
        Files.write(recordingDir.resolve("warrior_take.mcmocap_rec"), encoder.toByteArray());
        org.bukkit.Location anchor = new org.bukkit.Location(null, 100, 70, 200);

        store.createFormationGroup("battle_group", "warrior_take", 2, 3, 1.5, 4.0, anchor);

        assertEquals(List.of("battle_group"), store.listFormationGroups());
        assertEquals("battle_group", store.formationGroupScene("battle_group"));
        assertEquals(6, store.readScene("battle_group").getAsJsonArray("subscenes").size());
        assertEquals(1, store.expand(".battle_group").size() == 6 ? 1 : 0);
        var group = store.formationGroupInfo("battle_group");
        assertEquals("warrior_take", group.get("source_recording").getAsString());
        assertEquals(2, group.get("rows").getAsInt());
        assertEquals(3, group.get("columns").getAsInt());

        var actorSpecs = store.expand(".battle_group");
        var recording = new net.mt1006.mocap.paper.playback.MocapRecordingParser().parse(actorSpecs.get(0).recordingPath());
        var frame = recording.frames().get(0);
        org.bukkit.Location firstActor = new org.bukkit.Location(null, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (var transform : actorSpecs.get(0).transforms()) firstActor = transform.apply(firstActor, recording.startX(), recording.startY(), recording.startZ());
        assertEquals(100.0, firstActor.getX(), 0.001);
        assertEquals(70.0, firstActor.getY(), 0.001);
        assertEquals(200.0, firstActor.getZ(), 0.001);
        // With yaw 0, the second column is 1.5 blocks to the actor's right (negative X).
        var secondSpec = actorSpecs.get(1);
        org.bukkit.Location secondActor = new org.bukkit.Location(null, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (var transform : secondSpec.transforms()) secondActor = transform.apply(secondActor, recording.startX(), recording.startY(), recording.startZ());
        assertEquals(98.5, secondActor.getX(), 0.001);
        assertEquals(200.0, secondActor.getZ(), 0.001);
    }

    @Test
    void movingSceneChangesGlobalTranslationWithoutChangingFormationOffsets() throws Exception {
        MocapSceneStore store = new MocapSceneStore(tempDir);
        Path recordingDir = tempDir.resolve("mocap_files").resolve("recordings");
        var encoder = new net.mt1006.mocap.paper.recording.MocapRecordingEncoder(10, 64, 20, 0, 0, "Warrior");
        Files.write(recordingDir.resolve("warrior.mcmocap_rec"), encoder.toByteArray());
        store.createFormationGroup("move_test", "warrior", 2, 2, 2.0, 5.0,
                new org.bukkit.Location(null, 100, 70, 200));

        store.moveSceneTo("move_test", new org.bukkit.Location(null, -40, 80, 75));

        var actors = store.expand(".move_test");
        var recording = new net.mt1006.mocap.paper.playback.MocapRecordingParser().parse(actors.get(0).recordingPath());
        var frame = recording.frames().get(0);
        org.bukkit.Location moved = new org.bukkit.Location(null, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (var transform : actors.get(0).transforms()) moved = transform.apply(moved, recording.startX(), recording.startY(), recording.startZ());
        assertEquals(-40.0, moved.getX(), 0.001);
        assertEquals(80.0, moved.getY(), 0.001);
        assertEquals(75.0, moved.getZ(), 0.001);
        assertEquals(-42.0, sceneActorLocation(store, actors.get(1)).getX(), 0.001);
        var group = store.formationGroupInfo("move_test");
        var anchor = group.getAsJsonArray("anchor");
        assertEquals(-40.0, anchor.get(0).getAsDouble(), 0.001);
        assertEquals(80.0, anchor.get(1).getAsDouble(), 0.001);
        assertEquals(75.0, anchor.get(2).getAsDouble(), 0.001);
    }

    private org.bukkit.Location sceneActorLocation(MocapSceneStore store, MocapSceneStore.ActorSpec actor) throws Exception {
        var recording = new net.mt1006.mocap.paper.playback.MocapRecordingParser().parse(actor.recordingPath());
        var frame = recording.frames().get(0);
        org.bukkit.Location location = new org.bukkit.Location(null, frame.x(), frame.y(), frame.z(), frame.yaw(), frame.pitch());
        for (var transform : actor.transforms()) location = transform.apply(location, recording.startX(), recording.startY(), recording.startZ());
        return location;
    }

    @Test
    void sceneCopyNeverOverwritesExistingTarget() throws Exception {
        Path scenesDir = tempDir.resolve("mocap_files").resolve("scenes");
        Files.createDirectories(scenesDir);
        Files.writeString(scenesDir.resolve("source.mcmocap_scene"),
                "{\"version\":4,\"subscenes\":[]}", StandardCharsets.UTF_8);
        Path target = scenesDir.resolve("target.mcmocap_scene");
        String original = "{\"version\":4,\"subscenes\":[{\"name\":\"marker\"}]}";
        Files.writeString(target, original, StandardCharsets.UTF_8);

        MocapSceneStore store = new MocapSceneStore(tempDir);
        assertThrows(IOException.class, () -> store.copyScene("source", "target"));
        assertEquals(original, Files.readString(target, StandardCharsets.UTF_8));
    }
}
