package com.example.PetLoad;

import net.minecraft.entity.Entity;
// import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityTameable;
// import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

public class PetWarper {
    private HashSet<UUID> loadedPets = new HashSet<>();
    private HashSet<UUID> unloadedPets = new HashSet<>();

    // Every tick
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        World world = event.world;
        if (world.isRemote)
            return; // Only run on server side

        // Get all loaded entities
        List<Entity> entities = world.loadedEntityList;
        HashSet<UUID> currentLoadedPets = new HashSet<>();

        for (Entity entity : entities) {
            // Skip non tameable entities
            if (!(entity instanceof EntityTameable))
                continue;

            EntityTameable pet = (EntityTameable) entity;

            // Wild mobs should remain so
            boolean isWild = !isTamed(pet);
            // Leashed mobs should be patient like good boys
            boolean isLeashed = isLeashed(pet);
            // Sitting mobs should stay put like good girls
            boolean isSitting = isSitting(pet);
            if (isWild || isLeashed || isSitting) {
                continue;
            }

            UUID petId = pet.getUniqueID();

            currentLoadedPets.add(petId);

            // If this loop found the pet, this means the pet is loaded. Hence, remove it
            // from the unloaded pets set
            if (unloadedPets.contains(petId)) {
                unloadedPets.remove(petId);
            }
        }

        // Find pets that were previously loaded but are no longer loaded
        for (UUID previouslyLoadedPet : loadedPets) {
            if (!currentLoadedPets.contains(previouslyLoadedPet)) {
                unloadedPets.add(previouslyLoadedPet);
            }
        }
        // Update loaded pets set
        loadedPets = currentLoadedPets;

        // Handle teleportation for unloaded pets
        for (UUID petId : unloadedPets) {
            // TODO: Implement teleportation logic for unloaded pets
            // This would involve:
            // 1. Finding the pet's owner
            // 2. Getting the owner's current position
            // 3. Loading the pet at that position
        }
    }

    // Helper methods to check if the entity is tamed, leashed, and sitting
    // These should only be able to run if the entity is EntityTamable
    private boolean isTamed(EntityTameable entity) {
        return entity.isTamed();
    }

    private boolean isLeashed(EntityTameable entity) {
        return entity.getLeashed();
    }

    private boolean isSitting(EntityTameable entity) {
        return entity.isSitting();
    }
}