package net.mt1006.mocap.mocap.files;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Persistent, registry-aware equipment presets. Only armor and both hands are stored. */
public final class EquipmentPresetManager
{
	private static final String DIRECTORY = "equipment_presets";
	private static final String EXTENSION = ".json";
	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private EquipmentPresetManager() {}

	public static final class Preset
	{
		private final EnumMap<EquipmentSlot, ItemStack> items = new EnumMap<>(EquipmentSlot.class);

		public Preset()
		{
			for (EquipmentSlot slot : SLOTS) { items.put(slot, ItemStack.EMPTY); }
		}

		public void apply(LivingEntity entity)
		{
			for (EquipmentSlot slot : SLOTS)
			{
				ItemStack stack = items.getOrDefault(slot, ItemStack.EMPTY);
				entity.setItemSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
			}
		}
	}

	public static boolean save(CommandOutput out, MinecraftServer server, ServerPlayer player, String group, String name)
	{
		File file = getFile(out, group, name, true);
		if (file == null) { return false; }
		if (file.exists()) { return out.sendFailure("equipment_preset.exists"); }

		Preset preset = new Preset();
		for (EquipmentSlot slot : SLOTS)
		{
			ItemStack stack = player.getItemBySlot(slot);
			preset.items.put(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
		}
		return write(out, server, file, preset);
	}

	public static @Nullable Preset load(CommandOutput out, MinecraftServer server, String group, String name)
	{
		File file = getFile(out, group, name, false);
		if (file == null) { return null; }
		if (!file.isFile())
		{
			out.sendFailure("equipment_preset.not_found");
			return null;
		}

		try (FileReader reader = new FileReader(file))
		{
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			Preset preset = new Preset();
			DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
			for (EquipmentSlot slot : SLOTS)
			{
				String key = slot.name().toLowerCase(java.util.Locale.ROOT);
				if (!json.has(key) || json.get(key).isJsonNull())
				{
					preset.items.put(slot, ItemStack.EMPTY);
					continue;
				}
				String snbt = json.get(key).getAsString();
				Tag tag = TagParser.parseCompoundFully(snbt);
				ItemStack stack = ItemStack.CODEC.parse(ops, tag).getOrThrow();
				preset.items.put(slot, stack);
			}
			return preset;
		}
		catch (Exception e)
		{
			out.sendException(e, "equipment_preset.load_error");
			return null;
		}
	}

	public static boolean delete(CommandOutput out, String group, String name)
	{
		File file = getFile(out, group, name, false);
		if (file == null) { return false; }
		if (!file.isFile()) { return out.sendFailure("equipment_preset.not_found"); }
		try
		{
			if (!file.delete()) { return out.sendFailure("equipment_preset.delete_error"); }
			return out.sendSuccess("equipment_preset.deleted");
		}
		catch (SecurityException e) { return out.sendException(e, "equipment_preset.delete_error"); }
	}

	public static boolean list(CommandOutput out, String group)
	{
		File directory = getGroupDirectory(out, group, false);
		if (directory == null) { return false; }
		if (!directory.isDirectory()) { return out.sendSuccess("equipment_preset.list_empty"); }
		File[] files = directory.listFiles((dir, fileName) -> fileName.endsWith(EXTENSION) && new File(dir, fileName).isFile());
		if (files == null || files.length == 0) { return out.sendSuccess("equipment_preset.list_empty"); }
		List<String> names = new ArrayList<>();
		for (File file : files) { names.add(file.getName().substring(0, file.getName().length() - EXTENSION.length())); }
		names.sort(String.CASE_INSENSITIVE_ORDER);
		out.sendSuccess("equipment_preset.list", group);
		for (String name : names) { out.sendSuccessLiteral("- %s", name); }
		return true;
	}

	private static boolean write(CommandOutput out, MinecraftServer server, File file, Preset preset)
	{
		JsonObject json = new JsonObject();
		DynamicOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
		try
		{
			for (EquipmentSlot slot : SLOTS)
			{
				ItemStack stack = preset.items.getOrDefault(slot, ItemStack.EMPTY);
				String key = slot.name().toLowerCase(java.util.Locale.ROOT);
				Tag tag = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
				json.addProperty(key, tag.toString());
			}
			try (FileWriter writer = new FileWriter(temporary))
			{
				new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
			}
			try
			{
				java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException e)
			{
				java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return out.sendSuccess("equipment_preset.saved");
		}
		catch (Exception e)
		{
			try { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
			catch (IOException cleanupException) { e.addSuppressed(cleanupException); }
			out.sendException(e, "equipment_preset.save_error");
			return false;
		}
	}

	private static @Nullable File getFile(CommandOutput out, String group, String name, boolean createGroup)
	{
		if (!Files.check(out, group) || !Files.check(out, name)) { return null; }
		File directory = getGroupDirectory(out, group, createGroup);
		if (directory == null) { return null; }
		return new File(directory, name + EXTENSION);
	}

	private static @Nullable File getGroupDirectory(CommandOutput out, String group, boolean create)
	{
		if (!Files.check(out, group)) { return null; }
		if (!Files.checkIfInitialized(out)) { return null; }
		File base = new File(Files.mocapDirectory, DIRECTORY);
		if (create && !base.isDirectory() && !base.mkdirs())
		{
			out.sendFailure("equipment_preset.directory_error");
			return null;
		}
		File directory = new File(base, group);
		if (create && !directory.isDirectory() && !directory.mkdirs())
		{
			out.sendFailure("equipment_preset.directory_error");
			return null;
		}
		return directory;
	}
}
