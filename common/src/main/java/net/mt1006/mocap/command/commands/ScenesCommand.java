package net.mt1006.mocap.command.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapTime;
import net.mt1006.mocap.api.v1.modifiers.MocapTimeModifiers;
import net.mt1006.mocap.api.v1.modifiers.MocapOffset;
import net.mt1006.mocap.api.v1.modifiers.MocapPlayerSkin;
import net.mt1006.mocap.command.CommandSuggestions;
import net.mt1006.mocap.command.CommandUtils;
import net.mt1006.mocap.command.io.FullCommandInfo;
import net.mt1006.mocap.mocap.files.RecordingFiles;
import net.mt1006.mocap.mocap.files.SceneData;
import net.mt1006.mocap.mocap.files.SceneFiles;
import net.mt1006.mocap.mocap.playing.modifiers.PlaybackModifiers;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import net.mt1006.mocap.mocap.recording.RecordingManager;
import net.mt1006.mocap.utils.Utils;
import net.mt1006.mocap.utils.ProfileUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public class ScenesCommand
{
	private static final Command<CommandSourceStack> COMMAND_ADD_TO = CommandUtils.command(ScenesCommand::addTo);

	public static LiteralArgumentBuilder<CommandSourceStack> getArgumentBuilder(CommandBuildContext buildContext)
	{
		LiteralArgumentBuilder<CommandSourceStack> commandBuilder = Commands.literal("scenes");

		commandBuilder.then(Commands.literal("add").then(CommandUtils.withStringArgument(SceneFiles::add, "name")));
		commandBuilder.then(Commands.literal("copy").then(CommandUtils.withInputAndStringArgument(ScenesCommand::copy, CommandSuggestions::scene, "src_name", "dest_name")));
		commandBuilder.then(Commands.literal("rename").then(CommandUtils.withInputAndStringArgument(ScenesCommand::rename, CommandSuggestions::scene, "old_name", "new_name")));
		commandBuilder.then(Commands.literal("remove").then(CommandUtils.withInputArgument(ScenesCommand::remove, CommandSuggestions::scene, "name")));
		commandBuilder.then(Commands.literal("add_to").
			then(Commands.argument("scene_name", StringArgumentType.string()).suggests(CommandSuggestions::scene).
			then(Commands.argument("to_add", StringArgumentType.string()).suggests(CommandSuggestions::playable).executes(CommandUtils.command(ScenesCommand::addToWithDefaultModifiers)).
			then(Commands.argument("wait_on_start", DoubleArgumentType.doubleArg(0.0)).executes(COMMAND_ADD_TO).
			then(CommandUtils.playerArguments(buildContext, COMMAND_ADD_TO))))));
		commandBuilder.then(Commands.literal("remove_from").
			then(CommandUtils.withTwoInputArguments(SceneFiles::removeElement, CommandSuggestions::scene, CommandSuggestions::sceneElement, "scene_name", "to_remove")));
		commandBuilder.then(Commands.literal("modify").
			then(Commands.argument("scene_name", StringArgumentType.string()).suggests(CommandSuggestions::scene).
			then(CommandUtils.withModifiers(buildContext, Commands.argument("to_modify", StringArgumentType.string()).
				suggests(CommandSuggestions::sceneElement), CommandUtils.command(ScenesCommand::modify), true))));
		commandBuilder.then(Commands.literal("info").then(CommandUtils.withStringArgument(SceneFiles::info, "scene_name").suggests(CommandSuggestions::scene)).
			then(CommandUtils.withTwoInputArguments(SceneFiles::elementInfo, CommandSuggestions::scene, CommandSuggestions::sceneElement, "scene_name", "element_pos")));
		commandBuilder.then(Commands.literal("formation").
			then(Commands.argument("scene_name", StringArgumentType.string()).suggests(CommandSuggestions::scene).
			then(Commands.argument("playable", StringArgumentType.string()).suggests(CommandSuggestions::playable).
			then(Commands.argument("rows", IntegerArgumentType.integer(1, 100)).
			then(Commands.argument("columns", IntegerArgumentType.integer(1, 100)).
			then(Commands.argument("spacing", DoubleArgumentType.doubleArg(0.5, 128.0)).executes(CommandUtils.command(ScenesCommand::formation))))))));
		commandBuilder.then(Commands.literal("random_names").
			then(Commands.argument("scene_name", StringArgumentType.string()).suggests(CommandSuggestions::scene).
			then(Commands.argument("names", StringArgumentType.greedyString()).executes(CommandUtils.command(ScenesCommand::randomNames)))));
		commandBuilder.then(Commands.literal("random_skins").
			then(Commands.argument("scene_name", StringArgumentType.string()).suggests(CommandSuggestions::scene).
			then(Commands.argument("profile_names", StringArgumentType.greedyString()).executes(CommandUtils.command(ScenesCommand::randomSkins)))));
		commandBuilder.then(Commands.literal("list").executes(CommandUtils.command(ScenesCommand::list)).
			then(CommandUtils.withInputArgument(SceneFiles::listElements, CommandSuggestions::scene, "scene_name")));

		return commandBuilder;
	}

	public static boolean copy(CommandOutput out, String srcName, String destName)
	{
		SceneFile srcFile = SceneFile.get(out, srcName);
		SceneFile destFile = SceneFile.get(out, destName);
		if (srcFile == null || destFile == null) { return false; }

		return srcFile.copy(out, destFile) != null;
	}

	public static boolean rename(CommandOutput out, String srcName, String destName)
	{
		SceneFile srcFile = SceneFile.get(out, srcName);
		SceneFile destFile = SceneFile.get(out, destName);
		if (srcFile == null || destFile == null) { return false; }

		return srcFile.rename(out, destFile) != null;
	}

	public static boolean remove(CommandOutput out, String name)
	{
		SceneFile file = SceneFile.get(out, name);
		return file != null && file.remove(out);
	}

	private static boolean addToWithDefaultModifiers(FullCommandInfo info)
	{
		// separated from addTo because it supports name pattern (adding multiple elements with single command)
		try
		{
			String name = info.getString("scene_name");
			String toAdd = info.getString("to_add");

			if (!toAdd.contains("*"))
			{
				SceneData.Element element = new SceneData.Element(toAdd, PlaybackModifiers.DEFAULT);
				return SceneFiles.addElement(info, name, element);
			}
			else
			{
				String[] parts = toAdd.split("\\*", -1);
				if (parts.length != 2)
				{
					info.sendFailure("scenes.add_to.multiple.invalid_pattern");
					return false;
				}

				//TODO: rely on file list
				List<String> playableList;
				if (toAdd.startsWith("."))
				{
					playableList = SceneFiles.list();
				}
				else if (toAdd.startsWith("-"))
				{
					playableList = new ArrayList<>();
					RecordingManager.allContexts().forEach((ctx) -> playableList.add(ctx.id.str));
				}
				else
				{
					playableList = RecordingFiles.list();
				}

				if (playableList == null) { return false; }
				List<String> matched = new ArrayList<>();
				for (String str : playableList)
				{
					if (str.startsWith(parts[0]) && str.endsWith(parts[1])) { matched.add(str); }
				}
				if (matched.isEmpty()) { return info.sendFailure("scenes.add_to.multiple.not_found"); }

				SceneFile sceneFile = SceneFile.get(info, name);
				if (sceneFile == null) { return false; }
				SceneData sceneData = sceneFile.loadSceneData(info);
				if (sceneData == null) { return false; }
				if (sceneData.elements.size() + matched.size() > SceneFiles.MAX_ELEMENTS)
				{ return info.sendFailure("scenes.limit", SceneFiles.MAX_ELEMENTS); }
				for (String str : matched) { sceneData.elements.add(new SceneData.Element(str, PlaybackModifiers.DEFAULT)); }
				return sceneData.save(info, sceneFile, name, "scenes.add_to.multiple.success", "scenes.add_to.multiple.error");
			}
		}
		catch (IllegalArgumentException e) { return info.sendException(e, "error.unable_to_get_argument"); }
	}

	private static boolean addTo(FullCommandInfo info)
	{
		try
		{
			String name = info.getString("scene_name");
			String toAdd = info.getString("to_add");

			if (toAdd.contains("*"))
			{
				info.sendFailure("scenes.add_to.multiple.pattern_with_arguments");
				return false;
			}

			double waitOnStart = 0.0;
			try
			{
				waitOnStart = info.getDouble("wait_on_start");
			}
			catch (IllegalArgumentException ignore) {}

			MocapModifiers modifiers = info.getSimpleModifiers(info);
			if (modifiers == null) { return false; }

			MocapTimeModifiers timeModifiers = modifiers.getTimeModifiers().withWaitOnStart(MocapTime.fromSeconds(waitOnStart));
			SceneData.Element element = new SceneData.Element(toAdd, modifiers.withTimeModifiers(timeModifiers));
			return SceneFiles.addElement(info, name, element);
		}
		catch (IllegalArgumentException e) { return info.sendException(e, "error.unable_to_get_argument"); }
	}

	private static boolean modify(FullCommandInfo info)
	{
		try
		{
			String name = info.getString("scene_name");
			Pair<Integer, String> posPair = CommandUtils.splitPosStr(info.getString("to_modify"));
			return SceneFiles.modify(info, name, posPair.getFirst(), posPair.getSecond());
		}
		catch (IllegalArgumentException e)
		{
			info.sendException(e, "error.unable_to_get_argument");
			return false;
		}
	}

	/** Adds a centered grid of the same playable to an existing scene. */
	private static boolean formation(FullCommandInfo info)
	{
		try
		{
			String sceneName = info.getString("scene_name");
			String playableName = info.getString("playable");
			int rows = info.getInteger("rows");
			int columns = info.getInteger("columns");
			double spacing = info.getDouble("spacing");
			long count = (long) rows * columns;
			if (count > 1000L)
				return info.sendFailure("scenes.formation.limit");

			SceneFile file = SceneFile.get(info, sceneName);
			if (file == null) { return false; }
			SceneData data = file.loadSceneData(info);
			if (data == null) { return false; }
			if (data.elements.size() + count > 5000L)
				return info.sendFailure("scenes.formation.scene_limit");

			// Validate the playable once before changing the scene file.
			if (net.mt1006.mocap.api.v1.controller.playable.MocapPlayable.get(info, playableName) == null) { return false; }
			var baseTransformations = PlaybackModifiers.DEFAULT.getTransformations();
			List<net.mt1006.mocap.api.v1.controller.playable.MocapSceneElement> additions = new ArrayList<>((int) count);
			for (int row = 0; row < rows; row++)
			{
				for (int col = 0; col < columns; col++)
				{
					double x = (col - (columns - 1) / 2.0) * spacing;
					double z = (row - (rows - 1) / 2.0) * spacing;
					MocapModifiers modifiers = PlaybackModifiers.DEFAULT.withTransformations(
						baseTransformations.withOffset(new MocapOffset(x, 0.0, z)));
					additions.add(new SceneData.Element(playableName, modifiers));
				}
			}
			data.elements.addAll(additions);
			return data.save(info, file, sceneName, "scenes.formation.success", "scenes.formation.error");
		}
		catch (IllegalArgumentException e) { return info.sendException(e, "error.unable_to_get_argument"); }
	}

	private static boolean randomNames(FullCommandInfo info)
	{
		return assignRandomProfiles(info, info.getString("names"), false);
	}

	private static boolean randomSkins(FullCommandInfo info)
	{
		return assignRandomProfiles(info, info.getString("profile_names"), true);
	}

	private static boolean assignRandomProfiles(FullCommandInfo info, String rawNames, boolean skins)
	{
		String sceneName = info.getString("scene_name");
		List<String> names = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (String part : rawNames.split(","))
		{
			String name = part.trim();
			if (name.isEmpty()) { continue; }
			if (!name.matches("[A-Za-z0-9_]{1,16}"))
				return info.sendFailure(skins ? "scenes.random_skins.invalid_profile" : "scenes.random_names.invalid_name", name);
			if (seen.add(name.toLowerCase(Locale.ROOT)))
			{
				names.add(name);
				int limit = skins ? 256 : 1000;
				if (names.size() > limit)
					return info.sendFailure(skins ? "scenes.random_skins.limit" : "scenes.random_names.limit", limit);
			}
		}
		if (names.isEmpty()) { return info.sendFailure("scenes.random_profiles.empty"); }

		SceneFile file = SceneFile.get(info, sceneName);
		if (file == null) { return false; }
		SceneData data = file.loadSceneData(info);
		if (data == null) { return false; }
		// A nested scene can contain multiple actors, so assigning one profile to its parent
		// would not guarantee uniqueness. Require direct recording elements for this operation.
		for (var element : data.elements)
		{
			if (element.getName().startsWith("."))
				return info.sendFailure("scenes.random_profiles.nested_scene");
		}

		Collections.shuffle(names, new Random());
		int assigned = Math.min(names.size(), data.elements.size());
		for (int i = 0; i < assigned; i++)
		{
			var element = data.elements.get(i);
			MocapModifiers modifiers = skins
					? element.getModifiers().withPlayerSkin(MocapPlayerSkin.fromPlayer(names.get(i)))
					: element.getModifiers().withPlayerName(names.get(i));
			data.elements.set(i, new SceneData.Element(element.getName(), modifiers));
		}
		if (!data.save(info, file, sceneName,
				skins ? "scenes.random_skins.success" : "scenes.random_names.success",
				skins ? "scenes.random_skins.error" : "scenes.random_names.error")) { return false; }
		if (skins)
		{
			for (int i = 0; i < assigned; i++) { ProfileUtils.prefetchProfileAsync(info.getServer(), names.get(i)); }
		}
		if (assigned < data.elements.size())
			info.sendSuccess(skins ? "scenes.random_skins.partial" : "scenes.random_names.partial", assigned, data.elements.size());
		return true;
	}

	public static boolean list(CommandOutput out)
	{
		StringBuilder scenesListStr = new StringBuilder();
		List<String> scenesList = SceneFiles.list();

		if (scenesList == null)
		{
			scenesListStr.append(" ").append(Utils.stringFromComponent("list.error"));
		}
		else if (!scenesList.isEmpty())
		{
			scenesList.forEach((name) -> scenesListStr.append(" ").append(name));
		}
		else
		{
			scenesListStr.append(" ").append(Utils.stringFromComponent("list.empty"));
		}

		return out.sendSuccess("scenes.list", new String(scenesListStr));
	}
}
