# Mocap Version Branches

Distinct Minecraft versions and loaders use separate artifacts; there is no single universal binary across incompatible runtimes.

| Branch | Target | Output |
|---|---|---|
| main / core-26.1 | Minecraft 26.1 | Fabric Mod and NeoForge Mod |
| fabric-1.21.11 | Minecraft 1.21.11 | Fabric Mod |
| paper-1.21.11 | Paper 1.21.11 | Java plugin using the Paper Mannequin API |

Keep the 26.1 feature core in main/core-26.1. Version ports adapt APIs and have their own Java toolchain, Actions workflow, artifact validation and prerelease. Fabric/NeoForge mods cannot be installed as Paper plugins. Build tests prove compilation, selected parser cases and packaging, not live behavior.

Existing releases:
- https://github.com/Ahsan3944/mocap/releases/tag/mocap-26.1-upgrade-2ebcae0
- https://github.com/Ahsan3944/mocap/releases/tag/mocap-fabric-1.21.11-uop1-8619bb9
- Paper release is generated only after its build and validation succeed.
