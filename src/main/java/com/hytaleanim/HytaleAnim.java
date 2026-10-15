package com.hytaleanim;

import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod(modid = HytaleAnim.MODID, name = HytaleAnim.NAME, version = HytaleAnim.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]",
        guiFactory = "com.hytaleanim.GuiFactory")
@Mod.EventBusSubscriber(modid = HytaleAnim.MODID, value = Side.CLIENT)
public class HytaleAnim {
    public static final String MODID = "hytaleanim";
    public static final String NAME = "Hytale Block Animations";
    public static final String VERSION = "1.0.0";

    public static Logger LOGGER;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOGGER = e.getModLog();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new ClientHandler());
    }

    // Guarda la config cuando se cambia desde el menú in-game
    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (MODID.equals(e.getModID())) {
            ConfigManager.sync(MODID, Config.Type.INSTANCE);
        }
    }
}
