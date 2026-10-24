package net.mt1006.mocap.mocap.playing.playback;

import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.api.v1.controller.config.MocapPlaybackConfig;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.command.CommandsContext;
import net.mt1006.mocap.mocap.recording.RecordingContext;
import net.mt1006.mocap.mocap.recording.RecordingManager;
import net.mt1006.mocap.mocap.files.EquipmentPresetManager;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public abstract class Playback
{
	//TODO: "playback.start.error.loop" and "playback.start.error.load" as failures, not errors

	protected final boolean isRoot;
	public final @Nullable ServerPlayer owner;
	public final MocapPlaybackConfig config;
	protected boolean finished = false, stopped = false, frozen = false, endFreeze = false;
	protected final MocapModifiers modifiers;
	protected int tickCounter = 0; //TODO: StartContext?
	protected int waitOnEnd = 0;

	protected Playback(boolean isRoot, @Nullable ServerPlayer owner, MocapPlaybackConfig config, MocapModifiers modifiers)
	{
		this.isRoot = isRoot;
		this.owner = owner;
		this.config = config;
		this.modifiers = modifiers;
	}

	public void tick()
	{
		if (stopped || frozen || finished) { return; }

		if (shouldExecuteTick())
		{
			if (waitOnEnd != 0)
			{
				if (waitOnEnd == 1) { finished = true; }
				waitOnEnd--;
			}
			else
			{
				executeTick();
			}
			tickCounter++;
		}

		if (finished && modifiers.getTimeModifiers().getLoop() && !endFreeze) { loop(); }
		else if (finished && shouldSelfStop() && !endFreeze) { stop(); }
	}

	public void setFrozen(boolean frozen)
	{
		if (!stopped) { this.frozen = frozen; }
	}

	public void setEndFreeze(boolean endFreeze)
	{
		if (!stopped)
		{
			this.endFreeze = endFreeze;
			if (endFreeze) { setFrozen(false); }
		}
	}

	public boolean isStopped()
	{
		return stopped;
	}

	public int getActorCount() { return 0; }

	/** Applies a persistent equipment override to actor playbacks; scenes propagate it to children. */
	public void applyEquipmentPreset(EquipmentPresetManager.Preset preset) {}

	/** Chooses a preset independently for every recording actor in this playback tree. */
	public void applyRandomEquipmentPresets(List<EquipmentPresetManager.Preset> presets) {}

	/** Applies a preset to one direct scene element (1-based); false when this playback is not a scene or index is invalid. */
	public boolean applyEquipmentPresetToElement(int elementIndex, EquipmentPresetManager.Preset preset) { return false; }

	/** Hides or restores name tags for Mocap fake-player actors only. */
	public void setNameTagsHidden(boolean hidden) {}

	/** Enables collision-light playback for actors; it does not reduce client rendering or packet costs. */
	public void setLowLagMode(boolean enabled) {}

	protected abstract void executeTick();

	public abstract void stop();

	protected abstract void loop();

	protected abstract boolean shouldSelfStop();

	protected boolean isActive()
	{
		if (stopped) { return false; }

		// End-freeze intentionally makes a completed playback inactive while keeping its
		// entities alive. Otherwise a looping child would keep its parent scene alive forever.
		if (endFreeze) { return !finished; }
		MocapTimeModifiers timeModifiers = modifiers.getTimeModifiers();
		if (timeModifiers.getLoop()) { return !timeModifiers.getWaitForParentEnd(); }
		return !finished;
	}

	protected boolean shouldExecuteTick()
	{
		if (CommandsContext.haveSyncEnabled == 0 || owner == null) { return true; }

		CommandsContext commandsContext = CommandsContext.get(owner);
		if (!commandsContext.getSync()) { return true; }

		for (RecordingContext ctx : RecordingManager.bySourcePlayer(owner))
		{
			if (ctx.state == RecordingContext.State.RECORDING) { return true; }
		}
		return false;
	}

	protected void finishOrWaitOnEnd()
	{
		int timeWaitOnEnd = modifiers.getTimeModifiers().getWaitOnEnd().ticks;
		if (timeWaitOnEnd == 0) { finished = true; }
		else { waitOnEnd = timeWaitOnEnd; }
	}
}
