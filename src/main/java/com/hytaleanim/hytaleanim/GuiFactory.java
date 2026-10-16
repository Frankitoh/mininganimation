package com.hytaleanim.hytaleanim;

import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;

public class GuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft mc) {}

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parent) {
        return new ConfigGui(parent);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return null;
    }

    public static class ConfigGui extends GuiConfig {
        public ConfigGui(GuiScreen parent) {
            super(parent,
                    ConfigElement.from(ModConfig.class).getChildElements(),
                    HytaleAnim.MODID, false, false,
                    I18n.format("hytaleanim.config.title"));
        }
    }
}
