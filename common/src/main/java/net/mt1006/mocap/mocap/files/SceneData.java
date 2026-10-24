package net.mt1006.mocap.mocap.files;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import net.mt1006.mocap.MocapMod;
import net.mt1006.mocap.api.v1.controller.playable.MocapPlayable;
import net.mt1006.mocap.api.v1.controller.playable.MocapSceneElement;
import net.mt1006.mocap.api.v1.io.CommandInfo;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.api.v1.modifiers.MocapModifiers;
import net.mt1006.mocap.command.CommandSuggestions;
import net.mt1006.mocap.mocap.playing.modifiers.PlaybackModifiers;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import net.mt1006.mocap.mocap.settings.Settings;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SceneData
{
	public final List<MocapSceneElement> elements = new ArrayList<>();
	public int version = 0;
	public boolean experimentalVersion = false;
	public long fileSize = 0;

	public static SceneData empty()
	{
		SceneData sceneData = new SceneData();
		sceneData.version = SceneFiles.VERSION;
		sceneData.experimentalVersion = MocapMod.EXPERIMENTAL;
		return sceneData;
	}

	public boolean save(CommandOutput out, SceneFile file, String sceneName, String onSuccess, String onError)
	{
		if (elements.size() > SceneFiles.MAX_ELEMENTS)
		{
			out.sendFailure("scenes.limit", SceneFiles.MAX_ELEMENTS);
			return false;
		}
		JsonObject json = new JsonObject();
		json.add("version", new JsonPrimitive(MocapMod.EXPERIMENTAL ? -SceneFiles.VERSION : SceneFiles.VERSION));

		JsonArray subscenesArray = new JsonArray();
		elements.forEach((s) -> subscenesArray.add(s.toJson()));
		json.add("subscenes", subscenesArray);

		java.io.File target = file.getFile();
		java.io.File temporary = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
		try
		{
			GsonBuilder gsonBuilder = Settings.PRETTY_SCENE_FILES.val ? new GsonBuilder().setPrettyPrinting() : new GsonBuilder();
			try (FileWriter writer = new FileWriter(temporary))
			{
				gsonBuilder.create().toJson(json, writer);
				writer.flush();
			}

			try
			{
				java.nio.file.Files.move(temporary.toPath(), target.toPath(),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException ignored)
			{
				java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}

			saveToSceneElementCache(sceneName);
			out.sendSuccess(onSuccess);
			return true;
		}
		catch (IOException e)
		{
			try { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
			catch (IOException cleanupException) { e.addSuppressed(cleanupException); }
			out.sendException(e, onError);
			return false;
		}
	}

	public boolean load(CommandOutput out, @Nullable SceneFile file)
	{
		if (file == null) { return false; }
		byte[] data = Files.loadFile(file.getFile());
		return data != null && load(out, data);
	}

	private boolean load(CommandOutput out, byte[] scene)
	{
		SceneData parsedData = new SceneData();
		parsedData.fileSize = scene.length;

		LegacySceneDataParser legacyParser = new LegacySceneDataParser().tryParse(parsedData, out, scene);
		if (legacyParser.isLegacy())
		{
			if (!legacyParser.wasParsed()) { return false; }
			copyFrom(parsedData);
			return true;
		}

		try
		{
			JsonElement jsonElement = new JsonParser().parse(new InputStreamReader(new ByteArrayInputStream(scene)));
			JsonObject json = jsonElement.getAsJsonObject();
			if (json == null) { throw new Exception("Scene file isn't a JSON object!"); }

			JsonElement versionElement = json.get("version");
			if (versionElement == null) { throw new Exception("Scene version not specified!"); }
			if (!parsedData.setAndVerifyVersion(out, versionElement.getAsInt())) { return false; }

			JsonElement subsceneArrayElement = json.get("subscenes");
			if (subsceneArrayElement == null) { throw new Exception("Scene subscenes list not found!"); }

			for (JsonElement subsceneElement : subsceneArrayElement.getAsJsonArray())
			{
				if (parsedData.elements.size() >= SceneFiles.MAX_ELEMENTS)
				{
					out.sendFailure("scenes.limit", SceneFiles.MAX_ELEMENTS);
					return false;
				}
				JsonObject subsceneObject = subsceneElement.getAsJsonObject();
				if (subsceneObject == null) { throw new Exception("Scene subscene isn't a JSON object!"); }
				parsedData.elements.add(new Element(subsceneObject));
			}
			copyFrom(parsedData);
			return true;
		}
		catch (Exception e) { return out.sendException(e, "error.failed_to_load_scene"); }
	}

	private void copyFrom(SceneData source)
	{
		elements.clear();
		elements.addAll(source.elements);
		version = source.version;
		experimentalVersion = source.experimentalVersion;
		fileSize = source.fileSize;
	}

	public boolean setAndVerifyVersion(CommandOutput out, int versionNumber)
	{
		version = Math.abs(versionNumber);
		experimentalVersion = (versionNumber < 0);

		if (version > SceneFiles.VERSION)
		{
			out.sendFailure("error.failed_to_load_scene");
			out.sendFailure("error.failed_to_load_scene.not_supported");
			return false;
		}
		return true;
	}

	public @Nullable List<String> saveToSceneElementCache(String sceneName)
	{
		List<String> elements = new ArrayList<>(this.elements.size());
		int id = 1;
		for (MocapSceneElement element : this.elements)
		{
			elements.add(String.format(Locale.ROOT, "%03d-%s", id, element.getName()));
			id++;
		}

		CommandSuggestions.sceneElementCache.put(sceneName, elements);
		return elements;
	}

	public static @Nullable MocapSceneElement loadSubscene(CommandOutput out, @Nullable SceneData sceneData,
														   Pair<Integer, @Nullable String> pair)
	{
		return loadSubscene(out, sceneData, pair.getFirst(), pair.getSecond());
	}

	public static @Nullable MocapSceneElement loadSubscene(CommandOutput out, @Nullable SceneData sceneData,
														   int pos, @Nullable String expectedName)
	{
		if (sceneData == null) { return null; }

		if (sceneData.elements.size() < pos || pos < 1)
		{
			out.sendFailureWithTip("scenes.failure.wrong_element_pos");
			return null;
		}

		MocapSceneElement element = sceneData.elements.get(pos - 1);
		if (expectedName != null && !expectedName.equals(element.getName()))
		{
			out.sendFailure("scenes.failure.wrong_subscene_name");
			return null;
		}
		return element;
	}

	public static class Element implements MocapSceneElement
	{
		public final String name;
		public final MocapModifiers modifiers;

		public Element(String name, MocapModifiers modifiers)
		{
			this.name = name;
			this.modifiers = modifiers;
		}

		public Element(JsonObject json) throws Exception
		{
			JsonElement nameElement = json.get("name");
			if (nameElement == null) { throw new Exception("JSON \"name\" element not found!"); }

			name = nameElement.getAsString();
			modifiers = new PlaybackModifiers(new SceneFiles.Reader(json));
		}

		@Override public String getName()
		{
			return name;
		}

		@Override public MocapSceneElement withName(String name)
		{
			return new Element(name, modifiers);
		}

		@Override public MocapModifiers getModifiers()
		{
			return modifiers;
		}

		@Override public MocapSceneElement withModifiers(MocapModifiers modifiers)
		{
			return new Element(name, modifiers);
		}

		@Override public @Nullable MocapPlayable getPlayable(CommandInfo info)
		{
			return MocapPlayable.get(info, name);
		}

		@Override public JsonObject toJson()
		{
			JsonObject json = new JsonObject();
			json.add("name", new JsonPrimitive(name));
			modifiers.save(new SceneFiles.Writer(json));
			return json;
		}
	}
}
