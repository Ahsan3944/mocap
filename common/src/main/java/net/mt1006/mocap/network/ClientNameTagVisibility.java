package net.mt1006.mocap.network;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side UUID set used by the renderer mixin. */
public final class ClientNameTagVisibility
{
	private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();

	private ClientNameTagVisibility() {}

	public static void setHidden(UUID uuid, boolean hidden)
	{
		if (hidden) { HIDDEN.add(uuid); }
		else { HIDDEN.remove(uuid); }
	}

	public static boolean isHidden(UUID uuid)
	{
		return HIDDEN.contains(uuid);
	}

	public static void clear()
	{
		HIDDEN.clear();
	}
}
