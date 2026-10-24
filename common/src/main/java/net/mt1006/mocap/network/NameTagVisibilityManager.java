package net.mt1006.mocap.network;

import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.events.PlayerConnectionEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side registry for name-tag overrides belonging only to Mocap playback actors. */
public final class NameTagVisibilityManager
{
	private static final int MAX_BATCH = 10000;
	private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Boolean> PENDING = new HashMap<>();
	private static int batchDepth = 0;

	private NameTagVisibilityManager() {}

	public static void beginBatch()
	{
		batchDepth++;
	}

	public static void endBatch()
	{
		if (batchDepth == 0) { return; }
		batchDepth--;
		if (batchDepth != 0 || PENDING.isEmpty()) { return; }
		List<UUID> hide = new ArrayList<>();
		List<UUID> show = new ArrayList<>();
		PENDING.forEach((uuid, hidden) -> (hidden ? hide : show).add(uuid));
		PENDING.clear();
		for (ServerPlayer player : PlayerConnectionEvent.players)
		{
			sendBatches(player, hide, true);
			sendBatches(player, show, false);
		}
	}

	public static void setHidden(UUID uuid, boolean hidden)
	{
		if (hidden) { HIDDEN.add(uuid); }
		else { HIDDEN.remove(uuid); }
		if (batchDepth > 0)
		{
			PENDING.put(uuid, hidden);
			return;
		}
		for (ServerPlayer player : PlayerConnectionEvent.players)
		{ MocapPacketS2C.sendNameTagVisibility(player, uuid, hidden); }
	}

	public static void syncPlayer(ServerPlayer player)
	{
		List<UUID> hidden = new ArrayList<>(HIDDEN);
		sendBatches(player, hidden, true);
	}

	private static void sendBatches(ServerPlayer player, List<UUID> uuids, boolean hidden)
	{
		for (int start = 0; start < uuids.size(); start += MAX_BATCH)
		{
			MocapPacketS2C.sendNameTagVisibilityBatch(player,
					uuids.subList(start, Math.min(start + MAX_BATCH, uuids.size())), hidden);
		}
	}

	public static void clear()
	{
		HIDDEN.clear();
		PENDING.clear();
		batchDepth = 0;
	}
}
