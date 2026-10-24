package net.mt1006.mocap.mocap.playing.playback;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.mt1006.mocap.api.v1.controller.config.MocapPlaybackConfig;
import net.mt1006.mocap.api.v1.controller.playable.*;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapTransformations;
import net.mt1006.mocap.command.CommandUtils;
import net.mt1006.mocap.mocap.files.SceneData;
import net.mt1006.mocap.mocap.files.EquipmentPresetManager;
import net.mt1006.mocap.mocap.playing.PlaybackDataManager;
import net.mt1006.mocap.mocap.playing.modifiers.TransformationsConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ScenePlayback extends Playback
{
	private final List<Playback> subscenes;

	private ScenePlayback(boolean isRoot, @Nullable ServerPlayer owner, MocapPlaybackConfig config,
						  MocapModifiers modifiers, List<Playback> subscenes)
	{
		super(isRoot, owner, config, modifiers);
		this.subscenes = subscenes;
	}

	public static @Nullable ScenePlayback start(CommandInfo info, boolean isRoot, PlaybackDataManager dataManager, @Nullable SceneData sceneData,
												MocapPlaybackConfig config, MocapModifiers modifiers, @Nullable PositionTransformer parentTransformer)
	{
		if (sceneData == null) { return null; }

		if (sceneData.elements.isEmpty() && isRoot)
		{
			info.sendFailureWithTip("playback.start.error.empty_scene");
			return null;
		}

		PositionTransformer transformer = createPosTransformer(info, modifiers.getTransformations(), parentTransformer, sceneData, dataManager);
		if (transformer == null) { return null; }

		List<Playback> subscenes = new ArrayList<>();
		for (MocapSceneElement element : sceneData.elements)
		{
			MocapPlayable playable = element.getPlayable(info);
			if (playable == null)
			{
				stopStartedSubscenes(subscenes);
				return null;
			}

			MocapModifiers subsceneModifiers = element.getModifiers().mergeWithParent(modifiers);
			Playback playback = playable.startAsSubscene(info, subsceneModifiers, config, dataManager, transformer);
			if (playback == null)
			{
				stopStartedSubscenes(subscenes);
				return null;
			}
			subscenes.add(playback);
		}

		return new ScenePlayback(isRoot, info.getSourcePlayer(), config, modifiers, subscenes);
	}

	private static void stopStartedSubscenes(List<Playback> subscenes)
	{
		for (int i = subscenes.size() - 1; i >= 0; i--)
		{
			try { subscenes.get(i).stop(); }
			catch (Exception e) { net.mt1006.mocap.MocapMod.LOGGER.error("Failed to clean up partially started scene playback", e); }
		}
	}

	private static @Nullable Vec3 getSceneStartPos(CommandInfo info, TransformationsConfig.SceneCenter centers,
												   SceneData sceneData, PlaybackDataManager dataManager)
	{
		TransformationsConfig.SceneCenterType centerType = centers.type;
		if (centerType == TransformationsConfig.SceneCenterType.COMMON_SPECIFIC && centers.specificStr == null)
		{
			centerType = TransformationsConfig.SceneCenterType.COMMON_FIRST;
		}

		if (centerType == TransformationsConfig.SceneCenterType.COMMON_LEFTMOST)
		{
			Vec3 leftmost = null;
			for (MocapSceneElement candidate : sceneData.elements)
			{
				Vec3 candidateCenter = getElementCenter(info, candidate, dataManager);
				if (candidateCenter != null && (leftmost == null || candidateCenter.x < leftmost.x)) { leftmost = candidateCenter; }
			}
			return leftmost;
		}

		MocapSceneElement element = switch (centerType)
		{
			case COMMON_FIRST -> sceneData.elements.get(0);
			case COMMON_LAST -> sceneData.elements.get(sceneData.elements.size() - 1);
			case COMMON_SPECIFIC -> SceneData.loadSubscene(info, sceneData, CommandUtils.splitPosStr(centers.specificStr));
			default -> null;
		};
		return element != null ? getElementCenter(info, element, dataManager) : null;
	}

	private static @Nullable Vec3 getElementCenter(CommandInfo info, MocapSceneElement element, PlaybackDataManager dataManager)
	{
		MocapPlayable playable = element.getPlayable(info);
		if (playable == null) { return null; }
		Vec3 subsceneStartPos;
		if (playable instanceof MocapSceneFile sceneFile)
		{
			SceneData nestedData = dataManager.getScene(sceneFile);
			if (nestedData == null || nestedData.elements.isEmpty()) { return null; }
			subsceneStartPos = getSceneStartPos(info,
					element.getModifiers().getTransformations().getConfig().getSceneCenter(), nestedData, dataManager);
		}
		else if (playable instanceof MocapRecordingFile recordingFile)
		{
			var recording = dataManager.getRecording(recordingFile);
			if (recording == null) { return null; }
			subsceneStartPos = recording.startPos;
		}
		else if (playable instanceof MocapActiveRecording activeRecording)
		{
			subsceneStartPos = activeRecording.getRecordingData().startPos;
		}
		else { return null; }
		return subsceneStartPos != null ? element.getModifiers().getTransformations().calculateCenter(subsceneStartPos) : null;
	}

	private static @Nullable PositionTransformer createPosTransformer(CommandInfo info, MocapTransformations transformations,
																	  @Nullable PositionTransformer parent, SceneData sceneData,
																	  PlaybackDataManager dataManager)
	{
		if (parent != null && transformations.areDefault()) { return parent; }
		TransformationsConfig.SceneCenter center = transformations.getConfig().getSceneCenter();

		if (center.type == TransformationsConfig.SceneCenterType.INDIVIDUAL || sceneData.elements.isEmpty())
		{
			return new PositionTransformer(transformations, parent, null);
		}

		Vec3 sceneStartPos = getSceneStartPos(info, center, sceneData, dataManager);
		if (sceneStartPos == null) { return null; }

		return new PositionTransformer(transformations, parent, sceneStartPos);
	}

	@Override public int getActorCount()
	{
		int count = 0;
		for (Playback scene : subscenes)
		{
			count += scene.getActorCount();
			if (count > PlaybackDataManager.MAX_EXPANDED_ACTORS) { return count; }
		}
		return count;
	}

	@Override public void executeTick()
	{
		boolean subscenesInactive = true, subscenesStopped = true;
		for (Playback scene : subscenes)
		{
			scene.tick();
			if (scene.isActive()) { subscenesInactive = false; }
			if (!scene.stopped) { subscenesStopped = false; }
		}

		if (subscenesInactive) { finishOrWaitOnEnd(); }
		if (subscenesStopped) { stop(); }
	}

	@Override public void setFrozen(boolean frozen)
	{
		super.setFrozen(frozen);
		for (Playback scene : subscenes) { scene.setFrozen(frozen); }
	}

	@Override public void setEndFreeze(boolean endFreeze)
	{
		super.setEndFreeze(endFreeze);
		for (Playback scene : subscenes) { scene.setEndFreeze(endFreeze); }
	}

	@Override public void applyEquipmentPreset(EquipmentPresetManager.Preset preset)
	{
		for (Playback scene : subscenes) { scene.applyEquipmentPreset(preset); }
	}

	@Override public void applyRandomEquipmentPresets(List<EquipmentPresetManager.Preset> presets)
	{
		for (Playback scene : subscenes) { scene.applyRandomEquipmentPresets(presets); }
	}

	@Override public boolean applyEquipmentPresetToElement(int elementIndex, EquipmentPresetManager.Preset preset)
	{
		if (elementIndex < 1 || elementIndex > subscenes.size()) { return false; }
		subscenes.get(elementIndex - 1).applyEquipmentPreset(preset);
		return true;
	}

	@Override public void setNameTagsHidden(boolean hidden)
	{
		for (Playback scene : subscenes) { scene.setNameTagsHidden(hidden); }
	}

	@Override public void setLowLagMode(boolean enabled)
	{
		for (Playback scene : subscenes) { scene.setLowLagMode(enabled); }
	}

	@Override public void stop()
	{
		if (stopped) { return; }
		net.mt1006.mocap.network.NameTagVisibilityManager.beginBatch();
		try
		{
			for (int i = subscenes.size() - 1; i >= 0; i--)
			{
				try { subscenes.get(i).stop(); }
				catch (RuntimeException e) { net.mt1006.mocap.MocapMod.LOGGER.error("Failed to stop a child playback", e); }
			}
		}
		finally
		{
			finished = true;
			stopped = true;
			net.mt1006.mocap.network.NameTagVisibilityManager.endBatch();
		}
	}

	@Override protected void loop()
	{
		subscenes.forEach(Playback::loop);
		tickCounter = 0;
		finished = false;
	}

	@Override protected boolean shouldSelfStop()
	{
		return finished && (isRoot || !modifiers.getTimeModifiers().getWaitForParentEnd());
	}
}
