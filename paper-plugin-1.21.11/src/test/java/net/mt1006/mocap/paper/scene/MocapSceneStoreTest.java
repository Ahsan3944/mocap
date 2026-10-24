package net.mt1006.mocap.paper.scene;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
