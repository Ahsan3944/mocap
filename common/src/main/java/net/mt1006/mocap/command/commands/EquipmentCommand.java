package net.mt1006.mocap.command.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.command.CommandUtils;
import net.mt1006.mocap.command.io.FullCommandInfo;
import net.mt1006.mocap.mocap.files.EquipmentPresetManager;
import net.mt1006.mocap.mocap.playing.PlaybackManager;
import net.mt1006.mocap.mocap.playing.playback.PlaybackRoot;

import java.util.ArrayList;
import java.util.List;

public final class EquipmentCommand
{
	private EquipmentCommand() {}

	public static LiteralArgumentBuilder<CommandSourceStack> getArgumentBuilder()
	{
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("equipment");
		root.then(Commands.literal("save")
				.then(Commands.argument("group", StringArgumentType.word())
				.then(Commands.argument("preset", StringArgumentType.word())
				.executes(CommandUtils.command(EquipmentCommand::save)))));
		root.then(Commands.literal("list")
				.then(Commands.argument("group", StringArgumentType.word())
				.executes(CommandUtils.command(EquipmentCommand::list))));
		root.then(Commands.literal("delete")
				.then(Commands.argument("group", StringArgumentType.word())
				.then(Commands.argument("preset", StringArgumentType.word())
				.executes(CommandUtils.command(EquipmentCommand::delete)))));
		root.then(Commands.literal("apply")
				.then(Commands.argument("playback_id", StringArgumentType.word())
				.then(Commands.argument("group", StringArgumentType.word())
				.then(Commands.argument("preset", StringArgumentType.word())
				.executes(CommandUtils.command(EquipmentCommand::apply))))));
		root.then(Commands.literal("apply_element")
				.then(Commands.argument("playback_id", StringArgumentType.word())
				.then(Commands.argument("element_index", IntegerArgumentType.integer(1))
				.then(Commands.argument("group", StringArgumentType.word())
				.then(Commands.argument("preset", StringArgumentType.word())
				.executes(CommandUtils.command(EquipmentCommand::applyElement)))))));
		root.then(Commands.literal("randomize")
				.then(Commands.argument("playback_id", StringArgumentType.word())
				.then(Commands.argument("group", StringArgumentType.word())
				.then(Commands.argument("presets", StringArgumentType.greedyString())
				.executes(CommandUtils.command(EquipmentCommand::randomize))))));
		return root;
	}

	private static boolean save(FullCommandInfo info)
	{
		if (info.getSourcePlayer() == null) { return info.sendFailure("failure.resolve_player"); }
		return EquipmentPresetManager.save(info, info.getServer(), info.getSourcePlayer(),
				info.getString("group"), info.getString("preset"));
	}

	private static boolean list(FullCommandInfo info)
	{
		return EquipmentPresetManager.list(info, info.getString("group"));
	}

	private static boolean delete(FullCommandInfo info)
	{
		return EquipmentPresetManager.delete(info, info.getString("group"), info.getString("preset"));
	}

	private static boolean apply(FullCommandInfo info)
	{
		PlaybackRoot playback = findPlayback(info);
		if (playback == null) { return false; }
		EquipmentPresetManager.Preset preset = EquipmentPresetManager.load(info, info.getServer(),
				info.getString("group"), info.getString("preset"));
		if (preset == null) { return false; }
		playback.applyEquipmentPreset(preset);
		return info.sendSuccess("equipment_preset.applied");
	}

	private static boolean applyElement(FullCommandInfo info)
	{
		PlaybackRoot playback = findPlayback(info);
		if (playback == null) { return false; }
		EquipmentPresetManager.Preset preset = EquipmentPresetManager.load(info, info.getServer(),
				info.getString("group"), info.getString("preset"));
		if (preset == null) { return false; }
		int elementIndex = info.getInteger("element_index");
		if (!playback.applyEquipmentPresetToElement(elementIndex, preset))
			return info.sendFailure("equipment_preset.element_not_found");
		return info.sendSuccess("equipment_preset.element_applied");
	}

	private static boolean randomize(FullCommandInfo info)
	{
		PlaybackRoot playback = findPlayback(info);
		if (playback == null) { return false; }
		String group = info.getString("group");
		List<String> names = new ArrayList<>();
		for (String value : info.getString("presets").split(","))
		{
			String name = value.trim();
			if (!name.isEmpty() && !names.contains(name)) { names.add(name); }
		}
		if (names.isEmpty()) { return info.sendFailure("equipment_preset.random_empty"); }
		List<EquipmentPresetManager.Preset> presets = new ArrayList<>();
		for (String name : names)
		{
			EquipmentPresetManager.Preset preset = EquipmentPresetManager.load(info, info.getServer(), group, name);
			if (preset == null) { return false; }
			presets.add(preset);
		}
		playback.applyRandomEquipmentPresets(presets);
		return info.sendSuccess("equipment_preset.randomized");
	}

	private static PlaybackRoot findPlayback(CommandInfo info)
	{
		String id = info.getString("playback_id");
		PlaybackRoot playback = PlaybackManager.findPlayback(info, id, null);
		if (playback == null) { info.sendFailureWithTip("playback.stop.unable_to_find_playback"); }
		return playback;
	}
}
