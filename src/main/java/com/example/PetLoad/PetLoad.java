package com.example.PetLoad;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(modid = PetLoad.MODID, name = PetLoad.NAME, version = PetLoad.VERSION, guiFactory = "com.example.PetLoad.ModGuiFactory")
public class PetLoad
{
    public static final String MODID = "petload";
    public static final String NAME = "PetLoad";
    public static final String VERSION = "1.0";

    public static Logger logger;

    @Mod.Instance
    public static PetLoad Instance; // The mod instance

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        logger.info("Pre-initializing {}", NAME);
        ModConfig.loadConfig(event);

        // Register the entity chunk loader
        EntityChunkLoader.registerChunkLoading();
        MinecraftForge.EVENT_BUS.register(new EntityChunkLoader());

        // Register configuration event handler
        MinecraftForge.EVENT_BUS.register(ModConfig.class);
    }
}
