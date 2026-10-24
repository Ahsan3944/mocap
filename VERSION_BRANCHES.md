# Mocap Version Branches

The repository keeps Minecraft-version-specific source trees separate so newer API changes do not silently replace older loader builds.

| Branch | Target | Purpose |
|---|---|---|
| main | Minecraft 26.1 | Primary feature core; builds Fabric and NeoForge variants |
| core-26.1 | Minecraft 26.1 | Explicit preservation branch for the 26.1 core |
| fabric-1.21.11 | Minecraft 1.21.11 | Fabric compatibility port; Java 21 build/release workflow |
| paper-1.21.11 | Minecraft 1.21.11 | Planned separate Paper plugin port; not yet implemented or released |

## Source-of-truth rules

- Keep the 26.1 source and its loader builds in main and core-26.1.
- The Fabric 1.21.11 branch starts from the pinned official 1.21.11 source and backports compatible feature changes from the 26.1 core. It is a version-specific port, not a byte-for-byte copy of 26.1 Java sources.
- The Fabric branch workflow imports/builds source in one controlled run, then validates the JAR, Fabric descriptor, entrypoint, and Minecraft version before publishing.
- Paper is not a Fabric loader. A Fabric mod JAR cannot be installed as a Paper plugin. Paper needs a separate plugin implementation using Bukkit/Paper APIs and independent feature verification.
- Build success proves compilation and packaging, not all live gameplay behavior. The 30-minute loop regression and multi-actor benchmarks remain required.

## Existing release

Minecraft 26.1 Fabric + NeoForge release:
https://github.com/Ahsan3944/mocap/releases/tag/mocap-26.1-upgrade-e1ff639
