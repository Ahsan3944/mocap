package net.mt1006.mocap.mocap.playing.playback;

import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.api.v1.controller.MocapPlaybackRoot;
import net.mt1006.mocap.api.v1.controller.config.MocapPlaybackConfig;
import net.mt1006.mocap.mocap.files.EquipmentPresetManager;
import net.mt1006.mocap.network.NameTagVisibilityManager;

import java.util.List;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class PlaybackRoot implements MocapPlaybackRoot
{
	private final Playback instance;
	private final String id;
	private final String name;
	private final String suggestedId;
	private final MocapPlaybackConfig config;
	private final boolean isHidden;

	public PlaybackRoot(Playback instance, int id, String name, MocapPlaybackConfig config, boolean isHidden)
	{
		this.instance = instance;
		this.id = String.valueOf(id);
		this.name = name;
		this.suggestedId = String.format(Locale.ROOT, "%03d-%s", id, name);
		this.config = config;
		this.isHidden = isHidden;
	}

	@Override public String getId()
	{
		return id;
	}

	@Override public String getSuggestedId()
	{
		return suggestedId;
	}

	@Override public String getRootName()
	{
		return name;
	}

	@Override public @Nullable ServerPlayer getOwner()
	{
		return instance.owner;
	}

	@Override public MocapPlaybackConfig getConfig()
	{
		return config;
	}

	@Override public boolean isHidden()
	{
		return isHidden;
	}

	public int getActorCount() { return instance.getActorCount(); }

	@Override public boolean isFinished()
	{
		return instance.finished && !instance.endFreeze;
	}

	@Override public void stop()
	{
		if (instance.isStopped()) { return; }
		NameTagVisibilityManager.beginBatch();
		try { instance.stop(); }
		finally { NameTagVisibilityManager.endBatch(); }
	}

	public void tick()
	{
		instance.tick();
	}

	public void setFrozen(boolean frozen)
	{
		instance.setFrozen(frozen);
	}

	public void setEndFreeze(boolean endFreeze)
	{
		instance.setEndFreeze(endFreeze);
		if (!endFreeze && instance.finished && !instance.isStopped()) { instance.stop(); }
	}

	public void applyEquipmentPreset(EquipmentPresetManager.Preset preset) { instance.applyEquipmentPreset(preset); }
	public void applyRandomEquipmentPresets(List<EquipmentPresetManager.Preset> presets) { instance.applyRandomEquipmentPresets(presets); }
	public boolean applyEquipmentPresetToElement(int elementIndex, EquipmentPresetManager.Preset preset) { return instance.applyEquipmentPresetToElement(elementIndex, preset); }
	public void setNameTagsHidden(boolean hidden)
	{
		NameTagVisibilityManager.beginBatch();
		try { instance.setNameTagsHidden(hidden); }
		finally { NameTagVisibilityManager.endBatch(); }
	}
	public void setLowLagMode(boolean enabled) { instance.setLowLagMode(enabled); }
}
