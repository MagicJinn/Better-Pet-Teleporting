# Better Pet Teleporting

## Vastly improve pet teleportation logic so you don't lose them. Supports all pets across all dimensions.

[![Modrinth: Better Pet Teleporting](https://img.shields.io/badge/Modrinth-Better_Pet_Teleporting-00ae5d?logo=modrinth)](https://modrinth.com/mod/better-pet-teleporting)
[![CurseForge: Better Pet Teleporting](https://img.shields.io/badge/CurseForge-Better_Pet_Teleporting-f16437?logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/better-pet-teleporting)

### Features:

* Pets are always able to teleport to their owner, even in unloaded chunks.
* Pets are able to follow you across dimensions. (portals are disabled for pets.)
* Automatic support for mods (assuming they inherit the `EntityTameable` class).
* Pet teleportation is randomized, meaning loads of pets will usually not cause entity cramming.

### Limitations:

* Portals are disabled for pets (in favour of custom teleporting logic).
* Restarting your world/server will cause pets to remain stuck in unloaded chunks until loaded.
