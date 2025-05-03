package com.example.PetLoad;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PetWarper {
    private HashSet<UUID> loadedPets = new HashSet<>();
    private HashSet<UUID> unloadedPets = new HashSet<>();

    // Store pet information when they become unloaded
    private Map<UUID, PetInfo> petInfoMap = new HashMap<>();

    // Teleport threshold distance squared (12 blocks squared = 144.0D)
    private static final double TELEPORT_THRESHOLD_SQ = 144.0D;

    // A class to store pet information
    private static class PetInfo {
        UUID ownerId;
        int dimension;
        double lastX, lastY, lastZ;

        PetInfo(UUID ownerId, int dimension, double x, double y, double z) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }

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

            // Store pet info for potential future teleportation
            if (pet.getOwner() != null) {
                petInfoMap.put(petId, new PetInfo(
                        pet.getOwner().getUniqueID(),
                        pet.dimension,
                        pet.posX, pet.posY, pet.posZ));
            }

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
        handleUnloadedPets(world);
    }

    private void handleUnloadedPets(World world) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null)
            return;

        HashSet<UUID> teleportedPets = new HashSet<>();

        for (UUID petId : unloadedPets) {
            PetInfo petInfo = petInfoMap.get(petId);
            if (petInfo == null)
                continue;

            // Find the owner player
            EntityPlayerMP owner = null;
            for (EntityPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getUniqueID().equals(petInfo.ownerId)) {
                    owner = (EntityPlayerMP) player;
                    break;
                }
            }

            if (owner == null)
                continue;

            // Get the world server for the pet's dimension
            WorldServer petWorld = server.getWorld(petInfo.dimension);
            if (petWorld == null)
                continue;

            // Load the chunk where the pet was last seen
            int chunkX = MathHelper.floor(petInfo.lastX) >> 4;
            int chunkZ = MathHelper.floor(petInfo.lastZ) >> 4;

            // Force load the chunk if not already loaded
            boolean wasLoaded = petWorld.getChunkProvider().chunkExists(chunkX, chunkZ);
            Chunk chunk = petWorld.getChunkFromChunkCoords(chunkX, chunkZ);

            // Look for the pet in this chunk
            EntityTameable pet = findPetInLoadedChunk(petWorld, petId);

            if (pet != null) {
                // Cross-dimensional teleportation if needed
                if (pet.dimension != owner.dimension) {
                    pet.changeDimension(owner.dimension);
                }

                // Check if we need to teleport the pet to its owner
                double distanceSq = pet.getDistanceSq(owner);
                if (distanceSq >= TELEPORT_THRESHOLD_SQ) {
                    // Teleport logic similar to EntityAIFollowOwner
                    teleportPetToOwner(pet, owner);
                    teleportedPets.add(petId);
                }
            }

            // Only unload the chunk if we loaded it and it's not in the spawn area
            if (!wasLoaded && !isChunkInSpawnArea(petWorld, chunkX, chunkZ)) {
                // Allow the chunk to unload
                petWorld.getChunkProvider().queueUnload(chunk);
            }
        }

        // Remove teleported pets from unloaded pets list
        unloadedPets.removeAll(teleportedPets);
    }

    private EntityTameable findPetInLoadedChunk(WorldServer world, UUID petId) {
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityTameable && entity.getUniqueID().equals(petId)) {
                return (EntityTameable) entity;
            }
        }
        return null;
    }

    private boolean isChunkInSpawnArea(WorldServer world, int chunkX, int chunkZ) {
        BlockPos spawn = world.getSpawnPoint();
        int spawnChunkX = spawn.getX() >> 4;
        int spawnChunkZ = spawn.getZ() >> 4;
        int spawnRadius = world.getMinecraftServer().getSpawnProtectionSize();

        return Math.abs(chunkX - spawnChunkX) <= spawnRadius &&
                Math.abs(chunkZ - spawnChunkZ) <= spawnRadius;
    }

    private void teleportPetToOwner(EntityTameable pet, EntityPlayer owner) {
        // Logic adapted from EntityAIFollowOwner
        int i = MathHelper.floor(owner.posX) - 2;
        int j = MathHelper.floor(owner.posZ) - 2;
        int k = MathHelper.floor(owner.getEntityBoundingBox().minY);

        // Try to find a suitable position around the owner
        for (int l = 0; l <= 4; ++l) {
            for (int i1 = 0; i1 <= 4; ++i1) {
                if ((l < 1 || i1 < 1 || l > 3 || i1 > 3) &&
                        isTeleportFriendlyLocation(owner.world, i, j, k, l, i1)) {
                    // Teleport the pet
                    pet.setLocationAndAngles(
                            (double) ((float) (i + l) + 0.5F),
                            (double) k,
                            (double) ((float) (j + i1) + 0.5F),
                            pet.rotationYaw,
                            pet.rotationPitch);

                    // Clear any active pathfinding
                    pet.getNavigator().clearPath();
                    return;
                }
            }
        }
    }

    private boolean isTeleportFriendlyLocation(World world, int xBase, int zBase, int y, int xOffset, int zOffset) {
        BlockPos blockpos = new BlockPos(xBase + xOffset, y - 1, zBase + zOffset);

        // Check if there's solid ground to stand on
        if (!world.getBlockState(blockpos).isOpaqueCube()) {
            return false;
        }

        // Check if there's space for the pet (2 blocks of air)
        BlockPos blockpos1 = blockpos.up();
        if (!world.isAirBlock(blockpos1)) {
            return false;
        }

        BlockPos blockpos2 = blockpos1.up();
        return world.isAirBlock(blockpos2);
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