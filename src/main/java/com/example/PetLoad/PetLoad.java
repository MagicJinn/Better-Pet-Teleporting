package com.example.PetLoad;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = PetLoad.MODID, name = PetLoad.NAME, version = PetLoad.VERSION)
public class PetLoad
{
    public static final String MODID = "petload";
    public static final String NAME = "PetLoad";
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
