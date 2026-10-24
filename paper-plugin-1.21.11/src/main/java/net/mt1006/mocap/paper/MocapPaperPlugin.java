package net.mt1006.mocap.paper;

import net.mt1006.mocap.paper.command.PaperMocapCommand;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.playback.PaperPlaybackManager;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;

public final class MocapPaperPlugin extends JavaPlugin {
    private MocapSceneStore scenes;
    private EquipmentPresetStore equipment;
    private PaperPlaybackManager playbacks;

    @Override
    public void onEnable() {
        try {
            World rootWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (rootWorld == null) throw new IOException("Paper has no loaded world; cannot locate mocap_files.");
            scenes = new MocapSceneStore(rootWorld.getWorldFolder().toPath());
            equipment = new EquipmentPresetStore(getDataFolder().toPath());
            playbacks = new PaperPlaybackManager(this, scenes, equipment);

            PaperMocapCommand command = new PaperMocapCommand(this, scenes, equipment, playbacks);
            if (getCommand("mocap") == null) throw new IOException("plugin.yml is missing the mocap command declaration.");
            getCommand("mocap").setExecutor(command);
            getCommand("mocap").setTabCompleter(command);
            getLogger().info("Mocap Paper 1.21.11 visual playback enabled. Uses Paper Mannequin actors; see PORT_REPORT.md for feature limits.");
        } catch (Exception e) {
            getLogger().severe("Mocap Paper failed to initialize: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (playbacks != null) playbacks.shutdown();
    }

    public MocapSceneStore scenes() { return scenes; }
    public EquipmentPresetStore equipment() { return equipment; }
    public PaperPlaybackManager playbacks() { return playbacks; }
}