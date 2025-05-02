package com.example.PetLoad;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.ForgeChunkManager.Ticket;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EntityChunkLoader {
    // Store tickets for each entity using their UUID as the key
    private final Map<UUID, Ticket> entityTickets = new HashMap<>();
    // Store last known chunk positions to detect movement
    private final Map<UUID, ChunkPos> lastChunkPositions = new HashMap<>();

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        World world = event.world;
        if (world.isRemote)
            return; // Only run on server side

        // Track entities that still need chunk loading
        Map<UUID, Boolean> activeEntities = new HashMap<>();

        // Get all loaded entities
        List<Entity> entities = world.loadedEntityList;
        for (Entity entity : entities) {
            // Get the entity's registry key (e.g., "minecraft:wolf")
            ResourceLocation entityKey = EntityList.getKey(entity);
            if (entityKey == null)
                continue; // Skip if no registry key

            String entityId = entityKey.toString();
            // Skip non-pet mobs
            if (!ModConfig.getPetIds().contains(entityId)) {
                // If this entity has a ticket but is no longer in the config, release it
                UUID entityUUID = entity.getUniqueID();
                Ticket existingTicket = entityTickets.get(entityUUID);
                if (existingTicket != null) {
                    ForgeChunkManager.releaseTicket(existingTicket);
                    entityTickets.remove(entityUUID);
                    lastChunkPositions.remove(entityUUID);
                    PetLoad.logger.info("Released ticket for entity {} as it's no longer in the config", entityId);
                }
                continue;
            }
            PetLoad.logger.info(entity.getClass());

            // Check if entity is tamed, not leashed, and not sitting
            boolean isTamed = isTamed(entity);
            boolean isLeashed = isLeashed(entity);
            boolean isSitting = isSitting(entity);

            PetLoad.logger.info("Entity {} - Tamed: {}, Leashed: {}, Sitting: {}",
                    entityId, isTamed, isLeashed, isSitting);

            // If conditions are not met, skip the chunk loading
            if (!isTamed || isLeashed || isSitting) {
                continue;
            }

            UUID entityUUID = entity.getUniqueID();
            activeEntities.put(entityUUID, true);

            // Get current chunk position
            ChunkPos currentChunkPos = new ChunkPos(entity.chunkCoordX, entity.chunkCoordZ);

            // Check if we've already got a ticket for this entity
            Ticket ticket = entityTickets.get(entityUUID);
            ChunkPos lastPos = lastChunkPositions.get(entityUUID);

            // If entity moved to a different chunk or we don't have a ticket yet
            if (ticket == null || lastPos == null || !lastPos.equals(currentChunkPos)) {
                // Release the old chunk if we have one
                if (ticket != null) {
                    ForgeChunkManager.releaseTicket(ticket);
                }

                try {
                    // Check if we've reached the max number of chunks per player
                    int currentTicketCount = entityTickets.size();
                    if (currentTicketCount >= 5) {
                        PetLoad.logger.info("Reached maximum chunk ticket limit ({}/{})",
                                currentTicketCount, 5);
                        continue;
                    }

                    // Request a new ticket
                    ticket = ForgeChunkManager.requestTicket(PetLoad.Instance, world, ForgeChunkManager.Type.ENTITY);

                    if (ticket != null) {
                        // Store entity UUID in the ticket's data for reference
                        ticket.getModData().setUniqueId("EntityUUID", entityUUID);

                        // Force load the current chunk
                        ForgeChunkManager.forceChunk(ticket, currentChunkPos);

                        // Update our tracking maps
                        entityTickets.put(entityUUID, ticket);
                        lastChunkPositions.put(entityUUID, currentChunkPos);

                        PetLoad.logger.info("Loaded chunk at {} for entity {}",
                                currentChunkPos, entityId);
                    } else {
                        PetLoad.logger.warn("Failed to obtain chunk loading ticket for entity {}", entityId);
                    }
                } catch (Exception e) {
                    PetLoad.logger.error("Error requesting chunk ticket: {}", e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        // Clean up tickets for entities that no longer meet the conditions
        // or are no longer loaded
        entityTickets.entrySet().removeIf(entry -> {
            UUID entityUUID = entry.getKey();
            if (!activeEntities.containsKey(entityUUID)) {
                // Entity no longer meets conditions or is unloaded
                Ticket ticket = entry.getValue();
                if (ticket != null) {
                    ForgeChunkManager.releaseTicket(ticket);
                    PetLoad.logger.debug("Released chunk loading ticket for entity {}", entityUUID);
                }
                lastChunkPositions.remove(entityUUID);
                return true; // Remove from map
            }
            return false; // Keep in map
        });
    }

    // Helper methods to check if the entity is tamed, leashed, and sitting
    private boolean isTamed(Entity entity) {
        if (entity instanceof EntityTameable) {
            return ((EntityTameable) entity).isTamed();
        }
        return false;
    }

    private boolean isLeashed(Entity entity) {
        if (entity instanceof EntityTameable) {
            return ((EntityTameable) entity).getLeashed();
        }
        return false;
    }

    private boolean isSitting(Entity entity) {
        if (entity instanceof EntityTameable) {
            return ((EntityTameable) entity).isSitting();
        }
        return false;
    }

    /**
     * This method should be called during mod pre-initialization to register the
     * chunk loading callbacks
     */
    public static void registerChunkLoading() {
        // Register the mod as a chunk loading mod
        ForgeChunkManager.setForcedChunkLoadingCallback(PetLoad.Instance, new ChunkLoaderCallback());

        // Log that the chunk loader has been registered
        PetLoad.logger.info("Chunk loader registered successfully");
    }

    /**
     * Callback class for handling ticket validation when chunks are loaded
     */
    public static class ChunkLoaderCallback implements ForgeChunkManager.LoadingCallback {
        @Override
        public void ticketsLoaded(List<Ticket> tickets, World world) {
            // This is called when the world loads.
            // We don't need to reestablish the tickets here since we do it dynamically in
            // the tick handler.
            // Just release any tickets that might have been saved from a previous session.
            for (Ticket ticket : tickets) {
                ForgeChunkManager.releaseTicket(ticket);
            }
        }
    }
}