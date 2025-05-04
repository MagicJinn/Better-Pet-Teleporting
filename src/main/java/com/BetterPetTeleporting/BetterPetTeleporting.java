package com.BetterPetTeleporting;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = BetterPetTeleporting.MODID, name = BetterPetTeleporting.NAME, version = BetterPetTeleporting.VERSION)
public class BetterPetTeleporting {
    public static final String MODID = "betterpetteleporting";
    public static final String NAME = "Better Pet Teleporting";
    public static final String VERSION = "1.0";

    public static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("Pre-initializing {}", NAME);

        // Register the entity chunk loader
        MinecraftForge.EVENT_BUS.register(new PetWarper());
    }
}
