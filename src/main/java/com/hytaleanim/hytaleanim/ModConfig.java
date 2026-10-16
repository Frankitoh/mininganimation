package com.hytaleanim.hytaleanim;

import net.minecraftforge.common.config.Config;

@Config(modid = HytaleAnim.MODID)
@Config.LangKey("hytaleanim.config.title")
public class ModConfig {

    @Config.Comment("Activa la animación de movimiento al picar un bloque")
    @Config.LangKey("hytaleanim.config.miningAnimation")
    public static boolean miningAnimation = true;

    @Config.Comment("Velocidad de la animación al picar (1.0 = normal)")
    @Config.LangKey("hytaleanim.config.animationSpeed")
    @Config.RangeDouble(min = 0.1, max = 5.0)
    @Config.SlidingOption
    public static double animationSpeed = 1.0;

    @Config.Comment("Intensidad del movimiento (0 = ninguno, 1 = máximo)")
    @Config.LangKey("hytaleanim.config.animationIntensity")
    @Config.RangeDouble(min = 0.0, max = 1.0)
    @Config.SlidingOption
    public static double animationIntensity = 1.0;

    @Config.Comment("Al romper el bloque, este se achica hasta convertirse en el item")
    @Config.LangKey("hytaleanim.config.shrinkOnBreak")
    public static boolean shrinkOnBreak = true;

    @Config.Comment("Duración del achicamiento en ticks (20 ticks = 1 segundo)")
    @Config.LangKey("hytaleanim.config.shrinkDuration")
    @Config.RangeInt(min = 2, max = 30)
    @Config.SlidingOption
    public static int shrinkDuration = 7;
}
