package net.mt1006.mocap.network;

import com.mojang.datafixers.util.Pair;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.mt1006.mocap.MocapMod;
import net.mt1006.mocap.events.PlayerConnectionEvent;
import net.mt1006.mocap.mocap.playing.skins.CustomClientSkinManager;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public class MocapPacketS2C implements CustomPacketPayload
{
	private static final String TYPE_ID = MocapMod.loaderInterface.getLoaderName().toLowerCase(Locale.ROOT) + "_s2c";
	public static final Type<MocapPacketS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MocapMod.MOD_ID, TYPE_ID));
	public static final StreamCodec<FriendlyByteBuf, MocapPacketS2C> CODEC = StreamCodec.of((b, p) -> p.encode(b), MocapPacketS2C::new);

	public static final int ON_LOGIN = 0;
	public static final int NOCOL_PLAYER_ADD = 1;
	public static final int NOCOL_PLAYER_REMOVE = 2;
	public static final int CUSTOM_SKIN_DATA = 5;
	private static final int UNUSED = 6;
	public static final int NAME_TAG_VISIBILITY = 7;
	public static final int NAME_TAG_VISIBILITY_BATCH = 8;
	private static final int MAX_NAME_TAG_BATCH = 10000;

	private final int version;
	private final int op;
	private final Object object;

	public MocapPacketS2C(int op, Object object)
	{
		this.version = MocapMod.NETWORK_PACKETS_VERSION;
		this.op = op;
		this.object = object;
	}

	public MocapPacketS2C(FriendlyByteBuf buf)
	{
		version = buf.readInt();
		op = buf.readInt();

		switch (op)
		{
			case NOCOL_PLAYER_ADD:
			case NOCOL_PLAYER_REMOVE:
				object = buf.readUUID();
				break;

			case NAME_TAG_VISIBILITY:
				object = Pair.of(buf.readUUID(), buf.readBoolean());
				break;

			case NAME_TAG_VISIBILITY_BATCH:
				int count = buf.readInt();
				if (count < 0 || count > MAX_NAME_TAG_BATCH || buf.readableBytes() < count * 16L + 1L)
				{ throw new IllegalArgumentException("Invalid name-tag batch size: " + count); }
				List<UUID> uuids = new ArrayList<>(count);
				for (int i = 0; i < count; i++) { uuids.add(buf.readUUID()); }
				object = Pair.of(uuids, buf.readBoolean());
				break;

			case CUSTOM_SKIN_DATA:
				String customSkinName = NetworkUtils.readString(buf);
				byte[] customSkinArray = NetworkUtils.readByteArray(buf);
				object = Pair.of(customSkinName, customSkinArray);
				break;

			default:
				object = null;
		}
	}

	@Override public @NotNull Type<? extends CustomPacketPayload> type()
	{
		return TYPE;
	}

	public void encode(FriendlyByteBuf buf)
	{
		buf.writeInt(version);
		buf.writeInt(op);

		switch (op)
		{
			case NOCOL_PLAYER_ADD:
			case NOCOL_PLAYER_REMOVE:
				if (object instanceof UUID) { buf.writeUUID((UUID)object); }
				break;

			case NAME_TAG_VISIBILITY:
				if (object instanceof Pair<?, ?> pair && pair.getFirst() instanceof UUID uuid && pair.getSecond() instanceof Boolean hidden)
				{ buf.writeUUID(uuid); buf.writeBoolean(hidden); }
				break;

			case NAME_TAG_VISIBILITY_BATCH:
				if (object instanceof Pair<?, ?> pair && pair.getFirst() instanceof List<?> list && pair.getSecond() instanceof Boolean hidden
						&& list.size() <= MAX_NAME_TAG_BATCH && list.stream().allMatch(UUID.class::isInstance))
				{
					buf.writeInt(list.size());
					for (Object item : list) { buf.writeUUID((UUID)item); }
					buf.writeBoolean(hidden);
				}
				break;

			case CUSTOM_SKIN_DATA:
				if (!(object instanceof Pair<?,?>)) { break; }
				Pair<String, byte[]> customSkinData = (Pair<String, byte[]>)object;
				NetworkUtils.writeString(buf, customSkinData.getFirst());
				NetworkUtils.writeByteArray(buf, customSkinData.getSecond());
				break;
		}
	}

	public void handle(Server server)
	{
		if (version != MocapMod.NETWORK_PACKETS_VERSION) { return; }

 		switch (op)
		{
			case ON_LOGIN:
				ClientNameTagVisibility.clear();
				MocapPacketC2S.sendAcceptServer(server);
				break;
			case NOCOL_PLAYER_ADD: PlayerConnectionEvent.addNocolPlayer((UUID)object); break;
			case NOCOL_PLAYER_REMOVE: PlayerConnectionEvent.removeNocolPlayer((UUID)object); break;
			case CUSTOM_SKIN_DATA: CustomClientSkinManager.register((Pair<String, byte[]>)object); break;
			case NAME_TAG_VISIBILITY:
				if (object instanceof Pair<?, ?> pair && pair.getFirst() instanceof UUID uuid && pair.getSecond() instanceof Boolean hidden)
				{ ClientNameTagVisibility.setHidden(uuid, hidden); }
				break;
			case NAME_TAG_VISIBILITY_BATCH:
				if (object instanceof Pair<?, ?> pair && pair.getFirst() instanceof List<?> list && pair.getSecond() instanceof Boolean hidden)
				{
					for (Object item : list) { if (item instanceof UUID uuid) { ClientNameTagVisibility.setHidden(uuid, hidden); } }
				}
				break;
		}
	}

	public static void sendOnLogin(MocapPacketC2S.Client client)
	{
		client.respond(new MocapPacketS2C(ON_LOGIN, null));
	}

	public static void sendNocolPlayerAdd(ServerPlayer serverPlayer, UUID playerToAdd)
	{
		send(serverPlayer, NOCOL_PLAYER_ADD, playerToAdd);
	}

	public static void sendNocolPlayerRemove(ServerPlayer serverPlayer, UUID playerToAdd)
	{
		send(serverPlayer, NOCOL_PLAYER_REMOVE, playerToAdd);
	}

	public static void sendCustomSkinData(ServerPlayer player, String name, byte[] byteArray)
	{
		send(player, CUSTOM_SKIN_DATA, Pair.of(name, byteArray));
	}

	public static void sendNameTagVisibility(ServerPlayer player, UUID uuid, boolean hidden)
	{
		send(player, NAME_TAG_VISIBILITY, Pair.of(uuid, hidden));
	}

	public static void sendNameTagVisibilityBatch(ServerPlayer player, List<UUID> uuids, boolean hidden)
	{
		if (!uuids.isEmpty()) { send(player, NAME_TAG_VISIBILITY_BATCH, Pair.of(List.copyOf(uuids), hidden)); }
	}

	private static void send(ServerPlayer player, int op, Object object)
	{
		MocapMod.loaderInterface.sendPacketToClient(player, new MocapPacketS2C(op, object));
	}

	public interface Server
	{
		void respond(MocapPacketC2S packet);
	}
}
