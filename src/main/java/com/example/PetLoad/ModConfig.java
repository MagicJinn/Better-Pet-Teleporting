package com.example.PetLoad;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration handler for PetLoad mod.
 * Manages pet IDs and their associated states for chunk loading.
 */
public class ModConfig {
    private static Configuration config;
    private static List<String> petIds;

    // ===== Default Configuration Values =====
    /** Default list of mob IDs that can be loaded */
    private static final String[] DEFAULT_MOB_IDS = {
            "minecraft:wolf",
            "minecraft:cat"
    };

    public static Configuration getConfig() {
        return config;
    }

    public static ConfigCategory getCategory(String category) {
        return config.getCategory(category);
    }

    public static void loadConfig(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();

        loadMobIds(config);

        config.save();
    }

    // ===== Private Loading Methods =====
    private static void loadMobIds(Configuration config) {
        petIds = Arrays.asList(config.getStringList(
                "Pet Ids",
                "", // No category
                DEFAULT_MOB_IDS,
                "Ids of mobs that should be considered pets."));

        logConfigurationStatus("pet Ids", petIds);
    }

    private static void logConfigurationStatus(String configType, List<String> items) {
        if (items.isEmpty()) {
            PetLoad.logger.info("No " + configType + ".");
        } else {
            PetLoad.logger.info("Registering these " + configType + ": " + items);
        }
    }

    /**
     * @return List of configured mob IDs that can be loaded
     */
    public static List<String> getPetIds() {
        return petIds;
    }
}
