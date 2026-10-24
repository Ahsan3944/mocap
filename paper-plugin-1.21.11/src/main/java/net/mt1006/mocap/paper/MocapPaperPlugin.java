package net.mt1006.mocap.paper;

import net.mt1006.mocap.paper.command.PaperMocapCommand;
import net.mt1006.mocap.paper.equipment.EquipmentPresetStore;
import net.mt1006.mocap.paper.playback.PaperPlaybackManager;
import net.mt1006.mocap.paper.recording.PaperRecordingManager;
import net.mt1006.mocap.paper.scene.MocapSceneStore;
import net.mt1006.mocap.paper.settings.PaperSettingsStore;
import net.mt1006.mocap.paper.profile.ProfileLibraryStore;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;

public final class MocapPaperPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Location> placementAnchors = new HashMap<>();
    private MocapSceneStore scenes;
    private EquipmentPresetStore equipment;
    private PaperPlaybackManager playbacks;
    private PaperRecordingManager recordings;
    private PaperSettingsStore settings;
    private ProfileLibraryStore profileLibraries;

    @Override
    public void onEnable() {
        try {
            World rootWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (rootWorld == null) throw new IOException("Paper has no loaded world; cannot locate mocap_files.");
            scenes = new MocapSceneStore(rootWorld.getWorldFolder().toPath());
            equipment = new EquipmentPresetStore(getDataFolder().toPath());
            recordings = new PaperRecordingManager(this, rootWorld.getWorldFolder().toPath());
            settings = new PaperSettingsStore(getDataFolder().toPath());
            profileLibraries = new ProfileLibraryStore(getDataFolder().toPath());
            playbacks = new PaperPlaybackManager(this, scenes, equipment, settings);

            PaperMocapCommand command = new PaperMocapCommand(this, scenes, equipment, playbacks, recordings, settings, profileLibraries);
            if (getCommand("mocap") == null) throw new IOException("plugin.yml is missing the mocap command declaration.");
            getCommand("mocap").setExecutor(command);
            getCommand("mocap").setTabCompleter(command);
            getServer().getPluginManager().registerEvents(this, this);
            getLogger().info("Mocap Paper 1.21.11 visual playback enabled. Uses Paper Mannequin actors; see PORT_REPORT.md for feature limits.");
        } catch (Exception e) {
            getLogger().severe("Mocap Paper failed to initialize: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler
    public void onPlacementToolUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BLAZE_ROD) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("mocap.admin")) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null
                || !ChatColor.stripColor(meta.getDisplayName()).equalsIgnoreCase("MoCap Formation Placement Tool")) return;

        // Anchor at the center of the clicked block's top face.
        Location anchor = event.getClickedBlock().getLocation().add(0.5, 1.0, 0.5);
        placementAnchors.put(player.getUniqueId(), anchor.clone());
        event.setCancelled(true);
        player.sendMessage(ChatColor.GREEN + "MoCap anchor set at "
                + ChatColor.AQUA + anchor.getBlockX() + " " + anchor.getBlockY() + " " + anchor.getBlockZ()
                + ChatColor.GREEN + ". Use /mocap paste <recording|.scene> [loop].");
    }

    public void givePlacementTool(Player player) {
        ItemStack tool = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = tool.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "MoCap Formation Placement Tool");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Right-click a block to set the placement anchor.",
                    ChatColor.GRAY + "Then use /mocap paste <recording|.scene> [loop]."));
            tool.setItemMeta(meta);
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(tool);
        overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        player.sendMessage(ChatColor.GREEN + "Placement tool given. Right-click the target block's top face with this rod.");
    }

    public Location placementAnchor(Player player) {
        Location anchor = placementAnchors.get(player.getUniqueId());
        return anchor == null ? null : anchor.clone();
    }

    @Override
    public void onDisable() {
        if (recordings != null) recordings.shutdown();
        if (playbacks != null) playbacks.shutdown();
    }

    public MocapSceneStore scenes() { return scenes; }
    public EquipmentPresetStore equipment() { return equipment; }
    public PaperPlaybackManager playbacks() { return playbacks; }
    public PaperRecordingManager recordings() { return recordings; }
    public PaperSettingsStore settings() { return settings; }
    public ProfileLibraryStore profileLibraries() { return profileLibraries; }
}