# Better Pet Teleporting

## Vastly improve pet teleportation logic so you don't lose them. Supports all pets across all dimensions

Features:

- Pets are always able to teleport to their owner, even in unloaded chunks.
- Pets are able to follow you across dimensions (portals are disabled for pets).
- Automatic support for mods (assuming they inherit the `EntityTameable` class).
- Pet teleportation is randomized, meaning loads of pets will usually not cause entity cramming.

Limitations:

- Portals are disabled for pets (in favour of custom teleporting logic)
- Restarting your world/server will cause pets to remain stuck in unloaded chunks until loaded.

Repository is private - All rights reserved.
This might change in the future
