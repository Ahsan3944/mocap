#!/usr/bin/env python3
import json, re, shutil, sys
from pathlib import Path

FILES = [
"common/src/main/java/net/mt1006/mocap/command/commands/ScenesCommand.java",
"common/src/main/java/net/mt1006/mocap/command/commands/PlaybackCommand.java",
"common/src/main/java/net/mt1006/mocap/command/commands/EquipmentCommand.java",
"common/src/main/java/net/mt1006/mocap/mocap/files/EquipmentPresetManager.java",
"common/src/main/java/net/mt1006/mocap/mocap/files/SceneData.java",
"common/src/main/java/net/mt1006/mocap/mocap/files/SceneFiles.java",
"common/src/main/java/net/mt1006/mocap/mocap/files/LegacySceneDataParser.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/PlaybackDataManager.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/PlaybackManager.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/Playback.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/PlaybackRoot.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/RecordingPlayback.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/ScenePlayback.java",
"common/src/main/java/net/mt1006/mocap/network/MocapPacketS2C.java",
"common/src/main/java/net/mt1006/mocap/network/NetworkUtils.java",
"common/src/main/java/net/mt1006/mocap/network/NameTagVisibilityManager.java",
"common/src/main/java/net/mt1006/mocap/network/ClientNameTagVisibility.java",
"common/src/main/java/net/mt1006/mocap/mixin/LivingEntityRendererMixin.java",
"common/src/main/java/net/mt1006/mocap/mocap/playing/modifiers/TransformationsConfig.java",
"common/src/main/java/net/mt1006/mocap/api/v1/modifiers/MocapTransformationsConfig.java",
]
LANG_MARKERS = ("equipment_preset.", "playback.freeze.", "playback.unfreeze.", "playback.end_freeze.",
"playback.nametags.", "playback.low_lag.", "playback.start.error.actor_limit", "playback.stop.cleanup_error",
"scenes.formation.", "scenes.random_names.", "scenes.random_skins.", "scenes.random_profiles.",
"scenes.limit", "transformations.scene_center.leftmost")

def rd(root, rel):
 p = root / rel
 if not p.is_file(): raise FileNotFoundError(p)
 return p.read_text(encoding="utf-8")

def wr(root, rel, value):
 p = root / rel
 p.parent.mkdir(parents=True, exist_ok=True)
 p.write_text(value, encoding="utf-8", newline="\n")

def rep(s, old, new, label, count=1):
 n = s.count(old)
 if n != count: raise RuntimeError(f"{label}: expected {count}, found {n}")
 return s.replace(old, new)

def method(s, signature):
 a=s.find(signature)
 if a < 0: raise RuntimeError("Method missing: "+signature)
 b=s.find("{", a)
 depth=0
 for i in range(b,len(s)):
  if s[i]=="{": depth+=1
  elif s[i]=="}":
   depth-=1
   if depth==0: return s[a:i+1]
 raise RuntimeError("Unbalanced method: "+signature)

def replace_method(s, sig, new):
 return rep(s, method(s,sig), new, sig)

def main():
 if len(sys.argv)!=3: raise SystemExit("Usage: port_fabric_1_21_11.py <target-source> <core-source>")
 target, core = (Path(v).resolve() for v in sys.argv[1:])
 if not (target/".git").is_dir() or not (core/".git").is_dir():
  raise RuntimeError("Expected checked-out upstream and 26.1 core directories")
 for rel in FILES:
  src=core/rel
  dst=target/rel
  if not src.is_file(): raise FileNotFoundError(src)
  dst.parent.mkdir(parents=True,exist_ok=True)
  shutil.copy2(src,dst)

 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/PlaybackManager.java"
 s=rd(target,rel).replace("import net.mt1006.mocap.api.v1.events.MocapEvents;\n","")
 s=rep(s,"public static @Nullable PlaybackRoot onStart(","public static @Nullable PlaybackRoot addPlayback(",rel)
 s=s.replace("\n\t\tMocapEvents.PLAYBACK_START.invoker.onPlaybackStart(playbackRoot);","")
 old=method(s,"private static void removePlaybacks(Collection<PlaybackRoot> toRemove)")
 new="""private static void removePlaybacks(Collection<PlaybackRoot> toRemove)
\t{
\t\tfor (PlaybackRoot playback : toRemove)
\t\t{
\t\t\tServerPlayer owner = playback.getOwner();
\t\t\tplaybacksByOwner.remove(owner != null ? owner.getName().getString() : "", playback);
\t\t}
\t}"""
 s=rep(s,old,new,"PlaybackManager.removePlaybacks")
 if "MocapEvents" in s: raise RuntimeError("Unported event API remains")
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/PlaybackDataManager.java"
 wr(target,rel,rep(rd(target,rel),"recording.load(out, file)","recording.load(out, file, false)",rel))
 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/playback/Playback.java"
 wr(target,rel,rd(target,rel).replace("ctx.getState() == RecordingContext.State.RECORDING","ctx.state == RecordingContext.State.RECORDING"))
 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/playback/RecordingPlayback.java"
 s=rd(target,rel)
 for old,new in [
  ("ctx.getMainEntity().noPhysics = enabled;","ctx.getMainEntity().setNoPhysics(enabled);"),
  ("ctx.getMainEntity().noPhysics = lowLagMode;","ctx.getMainEntity().setNoPhysics(lowLagMode);"),
  ("fakePlayer.noPhysics = enabled;","fakePlayer.setNoPhysics(enabled);"),
  ("fakePlayer.noPhysics = lowLagMode;","fakePlayer.setNoPhysics(lowLagMode);")]:
  s=s.replace(old,new)
 if ".noPhysics =" in s: raise RuntimeError("26.1 noPhysics field left in 1.21.11 code")
 wr(target,rel,s)
 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/playback/ScenePlayback.java"
 s=rd(target,rel).replace("recording.getStartPos()","recording.startPos")
 s=s.replace("activeRecording.getContext().getRecordingData().getStartPos()","activeRecording.getRecordingData().startPos")
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/mocap/playing/playback/ActionContext.java"
 s,core_s=rd(target,rel),rd(core,rel)
 reset=method(core_s,"public void resetMainEntityForLoop()")
 cleanup=method(core_s,"public void removeMainEntity()")
 if "import net.minecraft.world.entity.LivingEntity;" not in s:
  s=rep(s,"import net.minecraft.world.entity.Entity;\n","import net.minecraft.world.entity.Entity;\nimport net.minecraft.world.entity.LivingEntity;\n","ActionContext import")
 s=rep(s,"public void removeMainEntity()",reset+"\n\n\tpublic void removeMainEntity()","insert loop recovery")
 s=replace_method(s,"public void removeMainEntity()",cleanup)
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/utils/FakePlayer.java"
 s=rd(target,rel)
 s=rep(s,"private boolean killedByPlayback = false;","private boolean killedByPlayback = false;\n\tprivate boolean staticMode = false;",rel)
 setter="""public void setStaticMode(boolean enabled)
\t{
\t\tstaticMode = enabled;
\t}"""
 s=rep(s,"\n\t@Override public void tick()","\n\t"+setter+"\n\n\t@Override public void tick()",rel)
 s=rep(s,"if (dyingTicks == 0) { playback.stop(); }","if (dyingTicks == 0 && playback != null) { playback.stop(); }",rel)
 s=rep(s,"\t\tattackStrengthTicker++;","\t\tif (!staticMode) { attackStrengthTicker++; }",rel)
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/utils/ProfileUtils.java"
 s=rd(target,rel)
 s=rep(s,"import java.util.UUID;\n","import java.util.UUID;\nimport java.util.Set;\nimport java.util.concurrent.ConcurrentHashMap;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\n",rel)
 fields="""\tprivate static final Set<String> prefetchInFlight = ConcurrentHashMap.newKeySet();
\tprivate static final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(2, runnable ->
\t{
\t\tThread thread = new Thread(runnable, "Mocap profile prefetch");
\t\tthread.setDaemon(true);
\t\treturn thread;
\t});
"""
 s=rep(s,"public class ProfileUtils\n{\n","public class ProfileUtils\n{\n"+fields,rel)
 prefetch="""public static void prefetchProfileAsync(MinecraftServer server, String name)
\t{
\t\tif (name == null || !StringUtil.isValidPlayerName(name)) { return; }
\t\tif (getFromCache(MocapPlayerNameHandling.IGNORE_AND_REPLACE_CASING, name, true) != null) { return; }
\t\tString key = name.toLowerCase(Locale.ROOT);
\t\tif (!prefetchInFlight.add(key)) { return; }
\t\tprefetchExecutor.execute(() ->
\t\t{
\t\t\ttry { getProfile(server, MocapPlayerNameHandling.IGNORE_AND_REPLACE_CASING, name, true); }
\t\t\tcatch (RuntimeException e) { net.mt1006.mocap.MocapMod.LOGGER.debug("Background profile prefetch failed for {}", name, e); }
\t\t\tfinally { prefetchInFlight.remove(key); }
\t\t});
\t}"""
 s=rep(s,"public static GameProfile createGameProfile(",prefetch+"\n\n\tpublic static GameProfile createGameProfile(",rel)
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/events/PlayerConnectionEvent.java"
 s=rd(target,rel)
 s=rep(s,"import net.mt1006.mocap.network.MocapPacketS2C;\n","import net.mt1006.mocap.network.MocapPacketS2C;\nimport net.mt1006.mocap.network.NameTagVisibilityManager;\n",rel)
 s=rep(s,"players.add(player);\n\t\tplayers.removeIf(Entity::isRemoved);",
       "players.add(player);\n\t\tNameTagVisibilityManager.syncPlayer(player);\n\t\tplayers.removeIf(Entity::isRemoved);",rel)
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/command/commands/MocapCommand.java"
 s=rd(target,rel)
 s=rep(s,"commandBuilder.then(ScenesCommand.getArgumentBuilder(buildContext));",
       "commandBuilder.then(ScenesCommand.getArgumentBuilder(buildContext));\n\t\tcommandBuilder.then(EquipmentCommand.getArgumentBuilder());",rel)
 wr(target,rel,s)

 rel="common/src/main/java/net/mt1006/mocap/MocapMod.java"
 s=rd(target,rel)
 matches=list(re.finditer(r"(NETWORK_PACKETS_VERSION\s*=\s*)(\d+)",s))
 if len(matches)!=1: raise RuntimeError("NETWORK_PACKETS_VERSION not found uniquely")
 m=matches[0]
 s=s[:m.start()]+m.group(1)+str(int(m.group(2))+1)+s[m.end():]
 wr(target,rel,s)

 rel="common/src/main/resources/assets/mocap/lang/en_us.json"
 target_lang=json.loads(rd(target,rel))
 core_lang=json.loads(rd(core,rel))
 target_lang.update({k:v for k,v in core_lang.items() if any(marker in k for marker in LANG_MARKERS)})
 wr(target,rel,json.dumps(target_lang,indent=2,ensure_ascii=False)+"\n")

 rel="gradle.properties"
 s=rd(target,rel)
 s=rep(s,"version=1.4-alpha-10","version=1.4-alpha-10-uop1",rel)
 s=rep(s,"source_url=https://github.com/mt1006/mc-mocap-mod","source_url=https://github.com/Ahsan3944/mocap",rel)
 wr(target,rel,s)

 rel="README.md"
 s=rd(target,rel)
 s+="""\n\n# UltraOP feature backport (1.21.11)\n\nThis branch targets Minecraft 1.21.11 on Fabric. It backports saved random scene names and skin profiles, formation building, scene placement anchors, freeze/unfreeze/end-freeze controls, grouped equipment presets, name-tag controls, collision-light playback, scene safety limits, and loop recovery.\n\nRandomized names and skin profile names are saved to the scene and do not reroll on replay. Individual actor names can still be changed using the existing scene modifier command. Random name/skin assignment rejects scenes containing nested elements. Low-lag mode only reduces collision and fake-player bookkeeping costs; it does not guarantee smooth playback with hundreds or thousands of visible actors.\n\nInstall this JAR only on Fabric 1.21.11. It is not a Paper plugin. Build success does not replace in-game regression tests, including the 30-minute loop test and multi-actor performance benchmark.\n"""
 wr(target,rel,s)
 report="""# Fabric 1.21.11 Feature Port — Verification Report\n\n## Source and target\n- Base: official Mocap tag v1.4-alpha-10+mc1.21.11.\n- Pinned upstream commit: 192f6f426bd4c02fbe1946a86480fcf6b6405a41.\n- Target: Minecraft 1.21.11, Java 21, Fabric; separate from the Minecraft 26.1 core branch.\n\n## Backported feature groups\n- Playback safety: bounded scheduler, speed validation, exception cleanup, resource-stack cleanup, recursive-scene detection, transactional scene parsing, actor/scene caps, and loop actor recovery.\n- Scene authoring: persisted random names/skins, grid formations, scene copy placement anchor, and bulk scene updates.\n- Playback controls: freeze/unfreeze, end-freeze, name-tag visibility sync, and collision-light playback.\n- Equipment presets: persistent groups, apply to playback tree or scene element, and randomize per actor.\n- Network safety: UTF-8 byte lengths, bounded packet reads, and versioned name-tag visibility packets.\n\n## Verification policy\nThe GitHub Actions workflow compiles Fabric on Java 21, checks ZIP integrity, verifies Fabric metadata and entrypoint, uploads the artifact, and publishes a prerelease only after successful checks. Build success does not prove live behavior is bug-free. Before production use, test 10s/30s/60s recordings for 30 minutes, confirm persistence across restart, test nested cleanup/presets/name tags, and benchmark 50/100/250/500/1000 actors with server MSPT, memory, traffic, and client FPS.\n\nPaper is a separate plugin port; this Fabric mod cannot be installed as a Paper plugin.\n"""
 wr(target,"FABRIC_1.21.11_PORT_REPORT.md",report)

 checks={
  "freeze command":'literal("freeze")' in rd(target,"common/src/main/java/net/mt1006/mocap/command/commands/PlaybackCommand.java"),
  "random names":'literal("random_names")' in rd(target,"common/src/main/java/net/mt1006/mocap/command/commands/ScenesCommand.java"),
  "equipment registration":"EquipmentCommand.getArgumentBuilder()" in rd(target,"common/src/main/java/net/mt1006/mocap/command/commands/MocapCommand.java"),
  "name-tag packet":"NAME_TAG_VISIBILITY_BATCH" in rd(target,"common/src/main/java/net/mt1006/mocap/network/MocapPacketS2C.java"),
  "1.21.11 physics API":"setNoPhysics(enabled)" in rd(target,"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/RecordingPlayback.java"),
  "loop recovery":"resetMainEntityForLoop" in rd(target,"common/src/main/java/net/mt1006/mocap/mocap/playing/playback/RecordingPlayback.java"),
 }
 missing=[k for k,v in checks.items() if not v]
 if missing: raise RuntimeError("Port assertions failed: "+", ".join(missing))
 print("Source-port assertions passed:")
 for k in checks: print("PASS:",k)

if __name__=="__main__": main()
