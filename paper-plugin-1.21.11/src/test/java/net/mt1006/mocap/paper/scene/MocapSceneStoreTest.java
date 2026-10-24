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

        var scene = store.readScene(".battle");
        var elements = scene.getAsJsonArray("subscenes");
        assertEquals(1, elements.size());
        var element = elements.get(0).getAsJsonObject();
        assertEquals("alex_run", element.get("name").getAsString());
        assertEquals("Alex", element.get("player_name").getAsString());
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
