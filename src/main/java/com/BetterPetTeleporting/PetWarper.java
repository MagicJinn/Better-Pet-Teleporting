package com.BetterPetTeleporting;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import javax.annotation.Nonnull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PetWarper {
    private final Map<UUID, PetInfo> petInfoMap = new HashMap<>();

    private static final double TELEPORT_THRESHOLD_SQ = Math.pow(12, 2);

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world.isRemote)
            return;

        World world = event.world;

        if (world.getTotalWorldTime() % 5L != 0L)
            return;

        List<Entity> entities = world.loadedEntityList;
        HashSet<UUID> currentLoadedPets = new HashSet<>();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        PlayerList playerList = server.getPlayerList();

        for (Entity entity : entities) {
            if (!(entity instanceof EntityTameable))
                continue;

            // When a pet's owner changes dimensions, the pet may be reset to an ownerless,
            // sitting state, preventing teleportation. Pets are registered before teleport
            // checks so eligibility is evaluated before being unregistered.
            EntityTameable pet = (EntityTameable) entity;
            UUID petId = pet.getUniqueID();

            if (!petTeleportEligible(pet)) {
                EntityLivingBase owner = pet.getOwner();
                if (owner != null) {
                    EntityPlayerMP ownerPlayer = playerList.getPlayerByUUID(owner.getUniqueID());
                    if (ownerPlayer != null && ownerPlayer.dimension == pet.dimension) {
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
                petInfo.isLoaded = true;
            }
        }

        for (Map.Entry<UUID, PetInfo> entry : petInfoMap.entrySet()) {
            if (!currentLoadedPets.contains(entry.getKey())) {
                entry.getValue().isLoaded = false;
            }
        }

        handleUnloadedPets(world, server);

        if (world.getTotalWorldTime() % 1200 == 0) {
            petInfoMap.entrySet()
                    .removeIf(entry -> server.getPlayerList().getPlayerByUUID(entry.getValue().ownerId) == null);
        }
    }

    private void handleUnloadedPets(World world, MinecraftServer server) {
        if (world == null || server == null)
            return;

        for (Map.Entry<UUID, PetInfo> entry : petInfoMap.entrySet()) {
            UUID petId = entry.getKey();
            PetInfo petInfo = entry.getValue();

            if (petInfo.isLoaded)
                continue;

            EntityPlayerMP owner = server.getPlayerList().getPlayerByUUID(petInfo.ownerId);
            if (owner == null)
                continue;

            WorldServer petWorld = server.getWorld(petInfo.dimension);
            if (petWorld == null)
                continue;

            int chunkX = MathHelper.floor(petInfo.lastX) >> 4;
            int chunkZ = MathHelper.floor(petInfo.lastZ) >> 4;
            boolean wasLoaded = petWorld.getChunkProvider().chunkExists(chunkX, chunkZ);
            Chunk chunk = petWorld.getChunkFromChunkCoords(chunkX, chunkZ);

            EntityTameable pet = findPetInLoadedChunk(petWorld, petId);
            if (pet != null) {
                boolean crossDim = pet.dimension != owner.dimension;
                boolean needTeleport = crossDim || pet.getDistanceSq(owner) >= TELEPORT_THRESHOLD_SQ;

                if (needTeleport) {
                    // changeDimension removes the old entity and returns the new one.
                    // Follow-up positioning must use that returned entity.
                    EntityTameable petToMove = pet;
                    if (crossDim) {
                        // Owner missing from this world makes AISit treat the pet as ownerless
                        // and sit. Clear before NBT is copied into the new-dimension entity.
                        forceStanding(petToMove);
                        petToMove = changePetDimension(pet, owner.dimension, server);
                        if (petToMove == null) {
                            if (!wasLoaded) {
                                petWorld.getChunkProvider().queueUnload(chunk);
                            }
                            continue;
                        }
                        if (!petToMove.getUniqueID().equals(petId)) {
                            petInfoMap.remove(petId);
                        }
                    }

                    teleportPetToOwner(petToMove, owner);
                    forceStanding(petToMove);
                    resyncPetToClients(petToMove);
                    petInfoMap.put(petToMove.getUniqueID(), new PetInfo(
                            petInfo.ownerId, petToMove.dimension,
                            petToMove.posX, petToMove.posZ, true));
                }
            }

            if (!wasLoaded) {
                petWorld.getChunkProvider().queueUnload(chunk);
            }
        }
    }

    private EntityTameable changePetDimension(EntityTameable pet, int dimension, MinecraftServer server) {
        pet.getEntityData().setBoolean("BPT_AllowDimChange", true);

        WorldServer targetWorld = server.getWorld(dimension);
        CustomPortalLessTeleporter teleporter = new CustomPortalLessTeleporter(targetWorld);

        Entity result = pet.changeDimension(dimension, teleporter);
        if (result instanceof EntityTameable) {
            return (EntityTameable) result;
        }
        return null;
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
        int i = MathHelper.floor(owner.posX) - 2, j = MathHelper.floor(owner.posZ) - 2,
                k = MathHelper.floor(owner.getEntityBoundingBox().minY);
        Random random = new Random();
        double teleportYOffset = 0.1f;

        List<double[]> validLocations = new java.util.ArrayList<>();

        for (int l = 0; l <= 4; ++l) {
            for (int i1 = 0; i1 <= 4; ++i1) {
                if ((l < 1 || i1 < 1 || l > 3 || i1 > 3)
                        && isTeleportFriendlyLocation(owner.world, i, j, k, l, i1, pet)) {
                    validLocations.add(new double[] {
                            i + l + 0.5F,
                            k + teleportYOffset,
                            j + i1 + 0.5F
                    });
                }
            }
        }

        if (!validLocations.isEmpty()) {
            double[] selectedLocation = validLocations.get(random.nextInt(validLocations.size()));
            // Using setPositionAndUpdate ensures the entity tracker is notified. Relying
            // only on setLocationAndAngles may lead to situations where clients temporarily
            // can't see the entity after it crosses chunk boundaries.
            pet.setPositionAndUpdate(selectedLocation[0], selectedLocation[1], selectedLocation[2]);
            pet.getNavigator().clearPath();
            return true;
        }

        return false;
    }

    /**
     * Forces the pet to stand. This is necessary to prevent the pet from sitting
     * after cross-dimension teleporting.
     * 
     * @param pet The pet to force to stand.
     */
    private void forceStanding(EntityTameable pet) {
        pet.setSitting(false);
        if (pet.getAISit() != null)
            pet.getAISit().setSitting(false);
    }

    /**
     * Resyncs the pet to clients.
     * 
     * @param pet The pet to resync.
     */
    private void resyncPetToClients(EntityTameable pet) {
        if (!(pet.world instanceof WorldServer))
            return;
        EntityTracker tracker = ((WorldServer) pet.world).getEntityTracker();
        tracker.untrack(pet);
        tracker.track(pet);
    }

    private boolean isTeleportFriendlyLocation(World world, int xBase, int zBase, int y, int xOffset, int zOffset,
            Entity pet) {
        BlockPos basePos = new BlockPos(xBase + xOffset, y - 1, zBase + zOffset);

        if (!world.getBlockState(basePos).isOpaqueCube()) {
            return false;
        }

        BlockPos checkPos = basePos.up();
        double height = pet.height;
        int requiredAirBlocks = (int) Math.ceil(height) + 1;

        for (int i = 0; i < requiredAirBlocks; i++) {
            if (!world.isAirBlock(checkPos.up(i))) {
                return false;
            }
        }

        return true;
    }

    private boolean isTamed(EntityTameable entity) {
        return entity.isTamed() && entity.getOwner() != null;
    }

    private boolean isLeashed(EntityTameable entity) {
        return entity.getLeashed();
    }

    private boolean isSitting(EntityTameable entity) {
        return entity.isSitting();
    }

    private boolean petTeleportEligible(EntityTameable entity) {
        return isTamed(entity) && !isLeashed(entity) && !isSitting(entity);
    }

    private static class PetInfo {
        UUID ownerId;
        int dimension;
        double lastX, lastZ;
        boolean isLoaded;

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

        if (entity instanceof EntityTameable) {
            if (!entity.getEntityData().getBoolean("BPT_AllowDimChange")) {
                event.setCanceled(true);
            }
            entity.getEntityData().setBoolean("BPT_AllowDimChange", false);
        }
    }

    public class CustomPortalLessTeleporter extends Teleporter {
        public CustomPortalLessTeleporter(WorldServer world) {
            super(world);
        }

        @Override
        public void placeInPortal(@Nonnull Entity entity, float rotationYaw) {
            BlockPos pos = new BlockPos(entity);
            entity.setPositionAndUpdate(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public boolean placeInExistingPortal(@Nonnull Entity entity, float rotationYaw) {
            placeInPortal(entity, rotationYaw);
            return true;
        }

        @Override
        public boolean makePortal(@Nonnull Entity entity) {
            return true;
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        Entity pet = event.getEntity();
        if (pet instanceof EntityTameable) {
            DamageSource source = event.getSource();
            if (source == DamageSource.CRAMMING ||
                    source == DamageSource.IN_WALL ||
                    source == DamageSource.FALL ||
                    source == DamageSource.OUT_OF_WORLD) {
                event.setCanceled(true);
            }
        }
    }
}
