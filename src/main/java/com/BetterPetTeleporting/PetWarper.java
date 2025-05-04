package com.BetterPetTeleporting;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
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
    // Store pet information when they become unloaded
    private Map<UUID, PetInfo> petInfoMap = new HashMap<>();

    // Teleport threshold distance squared (32 blocks squared = 1024.0D)
    private static final double TELEPORT_THRESHOLD_SQ = 1024.0D;

    // Every tick
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote)
            return;

        World world = event.world;
        List<Entity> entities = world.loadedEntityList;
        HashSet<UUID> currentLoadedPets = new HashSet<>();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        PlayerList playerList = server.getPlayerList();

        // Update pet info and loaded status
        for (Entity entity : entities) {
            // Skip non tameable pets
            if (!(entity instanceof EntityTameable))
                continue;

            // When a pet's owner changes dimensions, the pet may be reset to an ownerless,
            // sitting state, preventing teleportation. To avoid this, pets are registered
            // for teleportation before a teleport checks occur. This ensures that the
            // pet's teleportation eligibility is properly evaluated before being
            // unregistered.

            EntityTameable pet = (EntityTameable) entity;
            UUID petId = pet.getUniqueID();

            // Check pet eligability
            if (!petTeleportEligable(pet)) {
                EntityLivingBase owner = pet.getOwner();
                if (owner != null) {
                    int ownerDimension = playerList.getPlayerByUUID(owner.getUniqueID()).dimension;
                    // If teleport check fails, check if the pet is still in the same dimension
                    // If not, this is 100% what caused it to fail, so we ignore it
                    // I recognize the Council has made a decision.
                    // But given that it's a stupid-ass decision, I have elected to ignore it.
                    if (ownerDimension == pet.dimension) {
                        petInfoMap.remove(petId);
                    }
                }
                continue;
            }

            currentLoadedPets.add(petId);

            PetInfo petInfo = petInfoMap.get(petId);
            if (petInfo == null || petInfo.dimension != pet.dimension ||
                    petInfo.lastX != pet.posX || petInfo.lastZ != pet.posZ) {
                Entity ownerEntity = pet.getOwner();
                if (ownerEntity != null) {
                    UUID owner = ownerEntity.getUniqueID();

                    if (owner != null) {
                        petInfoMap.put(petId, new PetInfo(
                                owner, pet.dimension,
                                pet.posX,
                                pet.posZ,
                                true));
                    }
                }
            } else {
                petInfo.isLoaded = true; // Update existing entry
            }
        }

        // Mark pets not currently loaded as unloaded
        for (Map.Entry<UUID, PetInfo> entry : petInfoMap.entrySet()) {
            UUID petId = entry.getKey();
            PetInfo petInfo = entry.getValue();
            if (!currentLoadedPets.contains(petId)) {
                petInfo.isLoaded = false;
            }
        }

        // Handle teleportation for unloaded pets
        handleUnloadedPets(world, server);
    }

    private void handleUnloadedPets(World world, MinecraftServer server) {
        if (world == null || server == null)
            return;

        HashSet<UUID> teleportedPets = new HashSet<>();

        for (Map.Entry<UUID, PetInfo> entry : petInfoMap.entrySet()) {
            UUID petId = entry.getKey();
            PetInfo petInfo = entry.getValue();

            if (petInfo.isLoaded)
                continue;

            EntityPlayerMP owner = server.getPlayerList().getPlayerByUUID(petInfo.ownerId);
            if (owner == null) {
                continue;
            }

            WorldServer petWorld = server.getWorld(petInfo.dimension);
            if (petWorld == null) {
                continue;
            }

            int chunkX = MathHelper.floor(petInfo.lastX) >> 4;
            int chunkZ = MathHelper.floor(petInfo.lastZ) >> 4;
            boolean wasLoaded = petWorld.getChunkProvider().chunkExists(chunkX, chunkZ);
            Chunk chunk = petWorld.getChunkFromChunkCoords(chunkX, chunkZ);

            EntityTameable pet = findPetInLoadedChunk(petWorld, petId);
            if (pet != null) {
                // if (!petTeleportEligable(pet))
                // continue;

                boolean needTeleport = pet.dimension != owner.dimension ||
                        pet.getDistanceSq(owner) >= TELEPORT_THRESHOLD_SQ;
                if (needTeleport) {
                    if (pet.dimension != owner.dimension) {
                        changePetDimension(pet, owner.dimension, server);
                    }
                    if (teleportPetToOwner(pet, owner)) {
                        teleportedPets.add(petId);
                    }
                }
            }

            if (!wasLoaded) {
                petWorld.getChunkProvider().queueUnload(chunk);
            }
        }
    }

    // Custom teleport function while preventing portal entry
    private void changePetDimension(EntityTameable pet, int dimension, MinecraftServer server) {
        pet.getEntityData().setBoolean("BPT_AllowDimChange", true);

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
        double teleportYOffset = +0.1f; // Prevent wolf from falling through the floor

        for (int l = 0; l <= 4; ++l) {
            for (int i1 = 0; i1 <= 4; ++i1) {
                if ((l < 1 || i1 < 1 || l > 3 || i1 > 3)
                        && isTeleportFriendlyLocation(owner.world, i, j, k, l, i1, pet)) {
                    // 10% chance to accept this teleport
                    if (random.nextFloat() <= 0.10) {
                        pet.setLocationAndAngles(i + l + 0.5F, k + teleportYOffset, j + i1 + 0.5F, pet.rotationYaw,
                                pet.rotationPitch);
                        pet.getNavigator().clearPath();
                        return true;
                    }
                    // Save known good location if chance fails
                    lastValidX = i + l + 0.5F;
                    lastValidY = k + teleportYOffset;
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

    private boolean isTeleportFriendlyLocation(World world, int xBase, int zBase, int y, int xOffset, int zOffset,
            Entity pet) {
        BlockPos basePos = new BlockPos(xBase + xOffset, y - 1, zBase + zOffset);

        if (!world.getBlockState(basePos).isOpaqueCube()) {
            return false;
        }

        BlockPos checkPos = basePos.up(); // Start 1 block above ground
        double height = pet.height; // Use the height of the pet to determine how many blocks above ground to check
        int requiredAirBlocks = (int) Math.ceil(height) + 1; // +1 ensures one full block above the pet

        for (int i = 0; i < requiredAirBlocks; i++) {
            if (!world.isAirBlock(checkPos.up(i))) {
                return false;
            }
        }

        return true;
    }


    // Helper methods to check if the entity is tamed, leashed, and sitting
    // These should only be able to run if the entity is EntityTamable
    private boolean isTamed(EntityTameable entity) {
        return entity.isTamed() && entity.getOwner() != null;
    }

    private boolean isLeashed(EntityTameable entity) {
        return entity.getLeashed();
    }

    private boolean isSitting(EntityTameable entity) {
        return entity.isSitting();
    }

    private boolean petTeleportEligable(EntityTameable entity) {
        boolean isTamed = isTamed(entity);
        boolean isLeashed = isLeashed(entity);
        boolean isSitting = isSitting(entity);
        return !(!isTamed || isLeashed || isSitting);
    }

    // A class to store pet information
    private static class PetInfo {
        UUID ownerId;
        int dimension;
        double lastX, lastZ;
        boolean isLoaded; // New field

        PetInfo(UUID ownerId, int dimension, double x, double z, boolean isLoaded) {
            this.ownerId = ownerId;
            this.dimension = dimension;
            this.lastX = x;
            this.lastZ = z;
            this.isLoaded = isLoaded;
        }
    }

    @SubscribeEvent
    public void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();

        // Block dimension changes for pets
        if (entity instanceof EntityTameable) {
            if (!entity.getEntityData().getBoolean("BPT_AllowDimChange")) {
                event.setCanceled(true);
            }
            entity.getEntityData().setBoolean("BPT_AllowDimChange", false);
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
