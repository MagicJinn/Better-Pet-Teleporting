package com.example.PetLoad;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PetWarper {
    private HashSet<UUID> loadedPets = new HashSet<>();
    private HashSet<UUID> unloadedPets = new HashSet<>();

    // Store pet information when they become unloaded
    private Map<UUID, PetInfo> petInfoMap = new HashMap<>();

    // Teleport threshold distance squared (32 blocks squared = 1024.0D)
    private static final double TELEPORT_THRESHOLD_SQ = 1024.0D;

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
            unloadedPets.remove(petId);

            // Store or update the pet info to be used if the pet is unloaded
            PetInfo petInfo = petInfoMap.get(petId);
            if (petInfo == null ||
                    petInfo.dimension != pet.dimension ||
                    petInfo.lastX != pet.posX ||
                    petInfo.lastZ != pet.posZ) {
                petInfoMap.put(
                        petId,
                        new PetInfo(
                                pet.getOwner().getUniqueID(),
                                pet.dimension,
                                pet.posX, pet.posZ));
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

            // Forceload the chunk where the pet was last seen
            int chunkX = MathHelper.floor(petInfo.lastX) >> 4;
            int chunkZ = MathHelper.floor(petInfo.lastZ) >> 4;
            boolean wasLoaded = petWorld.getChunkProvider().chunkExists(chunkX, chunkZ);
            Chunk chunk = petWorld.getChunkFromChunkCoords(chunkX, chunkZ);

            // Look for the pet in this chunk
            EntityTameable pet = findPetInLoadedChunk(petWorld, petId);

            if (pet != null) {
                // Cross-dimensional teleportation if needed
                if (pet.dimension != owner.dimension) {
                    if (pet.getLastPortalVec() == null) {
                        pet.setPortal(pet.getPosition());
                    }
                    changePetDimension(pet, owner.dimension);
                }

                // Check if we need to teleport the pet to its owner
                double distanceSq = pet.getDistanceSq(owner);
                if (distanceSq >= TELEPORT_THRESHOLD_SQ) {
                    // Teleport logic similar to EntityAIFollowOwner

                    boolean isTeleported = teleportPetToOwner(pet, owner);
                    if (isTeleported) {
                        teleportedPets.add(petId);
                    }
                }
            }

            // Only unload the chunk if we loaded it
            if (!wasLoaded) {
                // Allow the chunk to unload
                petWorld.getChunkProvider().queueUnload(chunk);
            }
        }

        // Remove teleported pets from unloaded pets list
        unloadedPets.removeAll(teleportedPets);
    }

    // Custom teleport function while preventing portal entry
    private void changePetDimension(EntityTameable pet, int dimension) {
        pet.getEntityData().setBoolean("PetLoad_AllowDimChange", true);

        MinecraftServer server = pet.getEntityWorld().getMinecraftServer();
        WorldServer targetWorld = server.getWorld(dimension);
        CustomPortalLessTeleporter teleporter = new CustomPortalLessTeleporter(targetWorld);

        pet.changeDimension(dimension, teleporter);
    }

    private EntityTameable findPetInLoadedChunk(WorldServer world, UUID petId) {
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityTameable && entity.getUniqueID().equals(petId)) {
                return (EntityTameable) entity;
            }
        }
        return null;
    }

    private boolean teleportPetToOwner(EntityTameable pet, EntityPlayer owner) {
        // Adapted from EntityAIFollowOwner vanilla behavior
        int i = MathHelper.floor(owner.posX) - 2, j = MathHelper.floor(owner.posZ) - 2,
                k = MathHelper.floor(owner.getEntityBoundingBox().minY);
        Random random = new Random(); // Added randomness
        double lastValidX = -1, lastValidY = -1, lastValidZ = -1;

        for (int l = 0; l <= 4; ++l) {
            for (int i1 = 0; i1 <= 4; ++i1) {
                if ((l < 1 || i1 < 1 || l > 3 || i1 > 3) && isTeleportFriendlyLocation(owner.world, i, j, k, l, i1)) {
                    // 10% chance to accept this teleport
                    if (random.nextFloat() <= 0.10) {
                        pet.setLocationAndAngles(i + l + 0.5F, k, j + i1 + 0.5F, pet.rotationYaw, pet.rotationPitch);
                        pet.getNavigator().clearPath();
                        return true;
                    }
                    // Save known good location if chance fails
                    lastValidX = i + l + 0.5F;
                    lastValidY = k;
                    lastValidZ = j + i1 + 0.5F;
                }
            }
        }
        // If we got unlucky with the 10%, teleport to a known good location
        if (lastValidX != -1 && lastValidY != -1 && lastValidZ != -1) {
            pet.setLocationAndAngles(lastValidX, lastValidY, lastValidZ, pet.rotationYaw, pet.rotationPitch);
            pet.getNavigator().clearPath();
            return true;
        }

        return false;
    }

    private boolean isTeleportFriendlyLocation(World world, int xBase, int zBase, int y, int xOffset, int zOffset) {
        BlockPos blockpos = new BlockPos(xBase + xOffset, y - 1, zBase + zOffset);

        // Check if there's solid ground to stand on
        if (!world.getBlockState(blockpos).isNormalCube()) {
            return false;
        }

        // Check if there's space for the pet (2 blocks of air)
        return world.isAirBlock(blockpos.up()) && world.isAirBlock(blockpos.up(2));
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

    // A class to store pet information
    private static class PetInfo {
        UUID ownerId;
        int dimension;
        double lastX, lastZ;

        PetInfo(UUID ownerId, int dimension, double x, double z) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.lastX = x;
            this.lastZ = z;
        }
    }

    @SubscribeEvent
    public void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();

        // Block dimension changes for pets
        if (entity instanceof EntityTameable) {
            if (!entity.getEntityData().getBoolean("PetLoad_AllowDimChange")) {
                event.setCanceled(true);
            }
            entity.getEntityData().setBoolean("PetLoad_AllowDimChange", false);
        }
    }

    // Forge hates teleporting mobs without a portal, so we have to do it "manually"
    public class CustomPortalLessTeleporter extends Teleporter {
        public CustomPortalLessTeleporter(WorldServer world) {
            super(world);
        }

        @Override
        public void placeInPortal(Entity entity, float rotationYaw) {
            BlockPos pos = new BlockPos(entity);
            entity.setPositionAndUpdate(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public boolean placeInExistingPortal(Entity entity, float rotationYaw) {
            placeInPortal(entity, rotationYaw);
            return true;
        }

        @Override
        public boolean makePortal(Entity entity) {
            return true;
        }
    }

}
