package net.mt1006.mocap.mocap.playing;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.MocapMod;
import net.mt1006.mocap.api.v1.events.MocapEvents;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.command.CommandsContext;
import net.mt1006.mocap.command.io.FullCommandInfo;
import net.mt1006.mocap.mocap.files.SceneData;
import net.mt1006.mocap.mocap.files.SceneFiles;
import net.mt1006.mocap.mocap.playing.modifiers.PlaybackModifiers;
import net.mt1006.mocap.mocap.playing.playable.Playable;
import net.mt1006.mocap.mocap.playing.playback.Playback;
import net.mt1006.mocap.mocap.playing.playback.PlaybackRoot;
import net.mt1006.mocap.mocap.settings.Settings;
import net.mt1006.mocap.network.NameTagVisibilityManager;
import net.mt1006.mocap.utils.Utils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PlaybackManager
{
	public static final String MOCAP_ENTITY_TAG = "mocap_entity";
	public static final Multimap<String, PlaybackRoot> playbacksByOwner = HashMultimap.create();
	public static final Collection<PlaybackRoot> playbacks = playbacksByOwner.values();
	private static double playbackAccumulator = 0.0;
	private static final int MAX_PLAYBACK_STEPS_PER_SERVER_TICK = 20;
	public static final int MAX_ACTIVE_ACTORS = 5000;
	private static int nextPlaybackId = 0;

	public static @Nullable PlaybackRoot onStart(Playable playable, Playback playback, boolean isHidden)
	{
		if (!canStartActorCount(CommandOutput.DUMMY, playback.getActorCount()))
		{
			Utils.sendMessage(playback.owner, "playback.start.error.actor_limit", MAX_ACTIVE_ACTORS);
			try { playback.stop(); }
			catch (RuntimeException e) { MocapMod.LOGGER.error("Failed to clean up rejected playback", e); }
			return null;
		}
		PlaybackRoot playbackRoot = new PlaybackRoot(playback, getNextId(), playable.getName(), playback.config, isHidden);
		playbacksByOwner.put(playback.owner != null ? playback.owner.getName().getString() : "", playbackRoot);

		MocapEvents.PLAYBACK_START.invoker.onPlaybackStart(playbackRoot);
		return playbackRoot;
	}

	public static boolean canStartActorCount(CommandOutput out, int requestedActors)
	{
		int activeActors = 0;
		for (PlaybackRoot playback : playbacks)
		{
			activeActors += playback.getActorCount();
			if (activeActors + requestedActors > MAX_ACTIVE_ACTORS)
			{
				return out.sendFailure("playback.start.error.actor_limit", MAX_ACTIVE_ACTORS);
			}
		}
		if (requestedActors > MAX_ACTIVE_ACTORS)
			return out.sendFailure("playback.start.error.actor_limit", MAX_ACTIVE_ACTORS);
		return true;
	}

	public static boolean stop(CommandOutput out, String id, @Nullable String expectedName)
	{
		PlaybackRoot playback = findPlayback(out, id, expectedName);
		if (playback == null) { return out.sendFailureWithTip("playback.stop.unable_to_find_playback"); }

		try { playback.stop(); }
		catch (RuntimeException e)
		{
			MocapMod.LOGGER.error("Playback cleanup reported an error for {}", playback.getSuggestedId(), e);
			return out.sendFailure("playback.stop.cleanup_error");
		}
		out.sendSuccess("playback.stop.success");
		return true;
	}


	public static boolean setFrozen(FullCommandInfo info, boolean frozen)
	{
		String id = info.getString("id");
		PlaybackRoot playback = findPlayback(info, id, null);
		if (playback == null) { return info.sendFailureWithTip("playback.stop.unable_to_find_playback"); }
		playback.setFrozen(frozen);
		return info.sendSuccess(frozen ? "playback.freeze.success" : "playback.unfreeze.success");
	}

	public static boolean setEndFreeze(FullCommandInfo info, boolean endFreeze)
	{
		String id = info.getString("id");
		PlaybackRoot playback = findPlayback(info, id, null);
		if (playback == null) { return info.sendFailureWithTip("playback.stop.unable_to_find_playback"); }
		if (playback.isFinished())
		{
			return info.sendFailure("playback.end_freeze.too_late");
		}
		playback.setEndFreeze(endFreeze);
		return info.sendSuccess("playback.end_freeze.success");
	}

	public static @Nullable PlaybackRoot findPlayback(CommandOutput out, String id, @Nullable String expectedName)
	{
		for (PlaybackRoot playback : playbacks)
		{
			if (playback.getId().equals(id))
			{
				if (expectedName != null && !expectedName.equals(playback.getRootName()))
				{
					out.sendFailure("playback.stop.wrong_playback_name"); //TODO: FIX
					return null;
				}
				return playback;
			}
		}
		return null;
	}

	public static void stopAll(CommandOutput out, @Nullable ServerPlayer player)
	{
		if (player == null)
		{
			playbacks.forEach(PlaybackManager::stopSafely);
			out.sendSuccess(playbacks.isEmpty() ? "playback.stop_all.empty": "playback.stop_all.all");
		}
		else
		{
			Collection<PlaybackRoot> playerPlaybacks = playbacksByOwner.get(player.getName().getString());
			playerPlaybacks.forEach(PlaybackManager::stopSafely);

			if (playerPlaybacks.isEmpty())
			{
				out.sendSuccess(playbacks.isEmpty()
						? "playback.stop_all.empty"
						: "playback.stop_all.own.empty");
			}
			else
			{
				out.sendSuccess(playerPlaybacks.size() == playbacks.size()
						? "playback.stop_all.own.all"
						: "playback.stop_all.own.not_all");
			}

			if (playerPlaybacks.size() != playbacks.size() && Settings.SHOW_TIPS.val)
			{
				out.sendSuccess("playback.stop_all.own.tip");
			}
		}
	}

	public static void onServerStop()
	{
		PlaybackManager.stopAll(CommandOutput.DUMMY, null);

		// There will be no more server ticks to remove finished playbacks.
		removePlaybacks(new ArrayList<>(playbacks));
		playbacksByOwner.clear();
		NameTagVisibilityManager.clear();
	}

	public static boolean modifiersSet(FullCommandInfo rootInfo)
	{
		ServerPlayer source = rootInfo.getSourcePlayer();
		if (source == null) { return rootInfo.sendFailure("failure.resolve_player"); }
		CommandsContext ctx = CommandsContext.get(source);

		FullCommandInfo info = rootInfo.getFinalCommandInfo();
		if (info == null) { return rootInfo.sendFailure("error.unable_to_get_argument"); }

		String propertyName = info.getNode(4);
		if (propertyName == null) { return rootInfo.sendFailure("error.unable_to_get_argument"); }

		try
		{
			MocapModifiers newModifiers = ctx.modifiers.modify(info, propertyName, 4);
			if (newModifiers != null) { ctx.modifiers = newModifiers; }
			return newModifiers != null ? rootInfo.sendSuccess("playback.modifiers.set") : rootInfo.sendFailure("error.generic");
		}
		catch (Exception e) { return rootInfo.sendException(e, "error.unable_to_get_argument"); }
	}

	public static boolean modifiersList(CommandInfo info)
	{
		ServerPlayer source = info.getSourcePlayer();
		if (source == null) { return info.sendFailure("failure.resolve_player"); }

		CommandsContext ctx = CommandsContext.get(source);
		info.sendSuccess("playback.modifiers.list");
		ctx.modifiers.list(info);
		return true;
	}

	public static boolean modifiersReset(CommandInfo info)
	{
		ServerPlayer source = info.getSourcePlayer();
		if (source == null) { return info.sendFailure("failure.resolve_player"); }

		CommandsContext ctx = CommandsContext.get(source);
		ctx.modifiers = PlaybackModifiers.DEFAULT;
		return info.sendSuccess("playback.modifiers.reset");
	}

	public static boolean modifiersAddTo(CommandInfo info, String sceneName, String toAdd)
	{
		ServerPlayer source = info.getSourcePlayer();
		if (source == null) { return info.sendFailure("failure.resolve_player"); }

		SceneData.Element element = new SceneData.Element(toAdd, CommandsContext.get(source).modifiers);
		return SceneFiles.addElement(info, sceneName, element);
	}

	public static boolean list(CommandOutput out)
	{
		if (playbacks.isEmpty())
		{
			out.sendSuccess("playback.list.empty");
		}
		else
		{
			out.sendSuccess("playback.list");
			playbacks.forEach((p) -> out.sendSuccessLiteral("[%s] %s", p.getId(), p.getRootName()));
		}
		return true;
	}

	public static void onTick()
	{
		if (playbacks.isEmpty())
		{
			playbackAccumulator = 0.0;
			return;
		}

		double speed = Settings.PLAYBACK_SPEED.val;
		if (!Double.isFinite(speed) || speed < 0.05 || speed > 20.0)
		{
			MocapMod.LOGGER.warn("Invalid Mocap playback speed {}; using 1.0 until settings are corrected", speed);
			speed = 1.0;
		}
		playbackAccumulator = Math.min(MAX_PLAYBACK_STEPS_PER_SERVER_TICK, playbackAccumulator + speed);
		int steps = 0;
		while (playbackAccumulator >= 1.0 && steps < MAX_PLAYBACK_STEPS_PER_SERVER_TICK)
		{
			List<PlaybackRoot> toRemove = new ArrayList<>();
			for (PlaybackRoot playback : new ArrayList<>(playbacks))
			{
				if (playback.isFinished())
				{
					toRemove.add(playback);
					continue;
				}
				try { playback.tick(); }
				catch (RuntimeException e)
				{
					MocapMod.LOGGER.error("Stopping Mocap playback {} after an unexpected runtime error", playback.getSuggestedId(), e);
					try { playback.stop(); }
					catch (RuntimeException cleanupException) { e.addSuppressed(cleanupException); }
					toRemove.add(playback);
				}
			}
			removePlaybacks(toRemove);
			playbackAccumulator -= 1.0;
			steps++;
			if (playbacks.isEmpty())
			{
				playbackAccumulator = 0.0;
				break;
			}
		}
	}

	private static void removePlaybacks(Collection<PlaybackRoot> toRemove)
	{
		for (PlaybackRoot playback : toRemove)
		{
			try { MocapEvents.PLAYBACK_END.invoker.onPlaybackEnd(playback); }
			catch (RuntimeException e) { MocapMod.LOGGER.error("Playback end callback failed for {}", playback.getSuggestedId(), e); }
			finally
			{
				ServerPlayer owner = playback.getOwner();
				playbacksByOwner.remove(owner != null ? owner.getName().getString() : "", playback);
			}
		}
	}

	private static void stopSafely(PlaybackRoot playback)
	{
		try { playback.stop(); }
		catch (RuntimeException e) { MocapMod.LOGGER.error("Playback stop failed for {}", playback.getSuggestedId(), e); }
	}

	private static int getNextId()
	{
		return nextPlaybackId++;
	}
}
