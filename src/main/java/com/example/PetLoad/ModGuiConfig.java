package com.example.PetLoad;

import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.List;

public class ModGuiConfig extends GuiConfig {
    public ModGuiConfig(GuiScreen parentScreen) {
        super(parentScreen, getConfigElements(), PetLoad.MODID, false, false, "PetLoad Configuration");
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> elements = new ArrayList<>();

        // Add all config elements directly to the root
        elements.addAll(new ConfigElement(ModConfig.getConfig().getCategory("")).getChildElements());

        return elements;
    }
}