package net.mt1006.mocap.mocap.playing;

import net.mt1006.mocap.api.v1.controller.playable.MocapRecordingFile;
import net.mt1006.mocap.api.v1.controller.playable.MocapSceneElement;
import net.mt1006.mocap.api.v1.controller.playable.MocapSceneFile;
import net.mt1006.mocap.api.v1.io.CommandOutput;
import net.mt1006.mocap.mocap.files.Files;
import net.mt1006.mocap.mocap.files.RecordingData;
import net.mt1006.mocap.mocap.files.SceneData;
import net.mt1006.mocap.mocap.files.SkinList;
import net.mt1006.mocap.mocap.playing.playable.Playable;
import net.mt1006.mocap.mocap.playing.playable.RecordingFile;
import net.mt1006.mocap.mocap.playing.playable.SceneFile;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Random;
import java.util.Stack;

public class PlaybackDataManager
{
	public static final int MAX_EXPANDED_ACTORS = 5000;
	public final Random random = new Random();
	private final Map<MocapRecordingFile, RecordingData> recordingMap = new HashMap<>();
	private final Map<MocapSceneFile, SceneData> sceneMap = new HashMap<>();
	private final Stack<Playable> resourceStack = new Stack<>();
	private final Map<String, @Nullable SkinList> skinListMap = new HashMap<>();

	public boolean loadRecording(CommandOutput out, RecordingFile file)
	{
		if (recordingMap.containsKey(file)) { return true; }
		resourceStack.push(file);
		try
		{
			RecordingData recording = new RecordingData();
			if (!recording.load(out, file)) { return sendFailureWithPath(out); }
			recordingMap.put(file, recording);
			return true;
		}
		finally
		{
			resourceStack.pop();
		}
	}

	public boolean loadScene(CommandOutput out, SceneFile file)
	{
		if (resourceStack.contains(file))
		{
			resourceStack.push(file);
			try { return sendFailureWithPath(out, "playback.start.error.loop"); }
			finally { resourceStack.pop(); }
		}
		if (sceneMap.containsKey(file)) { return true; }

		resourceStack.push(file);
		try
		{
			SceneData sceneData = new SceneData();
			if (!sceneData.load(out, file)) { return sendFailureWithPath(out); }

			for (MocapSceneElement element : sceneData.elements)
			{
				if (!loadFromName(out, element.getName())) { return false; }
			}

			// Cache only fully loaded scenes, so a failed or recursive load cannot
			// leave a partially initialized scene in the cache.
			sceneMap.put(file, sceneData);
			int expandedActors = countExpandedActors(out, file, new HashMap<>(), new HashSet<>());
			if (expandedActors < 0 || expandedActors > MAX_EXPANDED_ACTORS)
			{
				sceneMap.remove(file);
				if (expandedActors > MAX_EXPANDED_ACTORS) { return sendFailureWithPath(out, "playback.start.error.actor_limit"); }
				return sendFailureWithPath(out);
			}
			return true;
		}
		finally
		{
			resourceStack.pop();
		}
	}

	public int getExpandedActorCount(CommandOutput out, SceneFile file)
	{
		return countExpandedActors(out, file, new HashMap<>(), new HashSet<>());
	}

	private int countExpandedActors(CommandOutput out, MocapSceneFile file, Map<MocapSceneFile, Integer> cache, Set<MocapSceneFile> active)
	{
		Integer cached = cache.get(file);
		if (cached != null) { return cached; }
		if (!active.add(file)) { return MAX_EXPANDED_ACTORS + 1; }
		try
		{
			SceneData data = sceneMap.get(file);
			if (data == null) { return -1; }
			int count = 0;
			for (MocapSceneElement element : data.elements)
			{
				String name = element.getName();
				int addition = 1;
				if (name != null && name.startsWith("."))
				{
					SceneFile nestedFile = SceneFile.get(out, name);
					if (nestedFile == null) { return -1; }
					addition = countExpandedActors(out, nestedFile, cache, active);
					if (addition < 0) { return -1; }
				}
				count += addition;
				if (count > MAX_EXPANDED_ACTORS) { return count; }
			}
			cache.put(file, count);
			return count;
		}
		finally { active.remove(file); }
	}

	private boolean loadFromName(CommandOutput out, String name)
	{
		//TODO: add support for active recordings/virtual
		if (name == null || name.isEmpty())
		{
			out.sendFailure("playback.start.error.load");
			return false;
		}
		if (name.charAt(0) == '.')
		{
			SceneFile file = SceneFile.get(out, name);
			return file != null && loadScene(out, file);
		}
		else
		{
			RecordingFile file = RecordingFile.get(out, name);
			return file != null && loadRecording(out, file);
		}
	}

	private boolean sendFailureWithPath(CommandOutput out)
	{
		return sendFailureWithPath(out, "playback.start.error.load");
	}

	private boolean sendFailureWithPath(CommandOutput out, String component)
	{
		StringBuilder retStr = new StringBuilder();
		for (Playable playable : resourceStack)
		{
			retStr.append("/");
			retStr.append(playable.getName());
		}
		String path = retStr.toString();

		out.sendFailure(component);
		out.sendFailure("playback.start.error.load.path", path);
		return false;
	}

	public @Nullable SkinList loadOrGetSkinList(String name)
	{
		if (skinListMap.containsKey(name)) { return skinListMap.get(name); }

		SkinList skinList = loadSkinList(name);
		skinListMap.put(name, skinList);
		return skinList;
	}

	private @Nullable SkinList loadSkinList(String name)
	{
		if (!name.startsWith(Files.SKIN_LIST_PREFIX)) { return null; }
		name = name.substring(Files.SKIN_LIST_PREFIX.length());

		return Files.check(CommandOutput.DUMMY, name)
				? SkinList.load(new File(Files.skinListDirectory, name + Files.SKIN_LIST_EXTENSION))
				: null;
	}

	// getRecording and getScene are not marked as nullable, as they should've been preloaded by "load" methods
	public RecordingData getRecording(MocapRecordingFile file)
	{
		return recordingMap.get(file);
	}

	public SceneData getScene(MocapSceneFile file)
	{
		return sceneMap.get(file);
	}
}
