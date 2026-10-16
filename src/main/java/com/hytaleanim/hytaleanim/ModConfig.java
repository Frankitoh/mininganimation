package com.hytaleanim.hytaleanim;

import net.minecraftforge.common.config.Config;

@Config(modid = HytaleAnim.MODID)
@Config.LangKey("hytaleanim.config.title")
public class ModConfig {

    @Config.Comment("Activa la animación de movimiento al picar un bloque")
    @Config.LangKey("hytaleanim.config.miningAnimation")
    public static boolean miningAnimation = true;

    @Config.Comment("Velocidad con la que el bloque se recupera tras cada golpe (1.0 = normal)")
    @Config.LangKey("hytaleanim.config.animationSpeed")
    @Config.RangeDouble(min = 0.1, max = 5.0)
    @Config.SlidingOption
    public static double animationSpeed = 1.0;

    @Config.Comment("Intensidad del aplastamiento por golpe (0 = ninguno, 1 = normal)")
    @Config.LangKey("hytaleanim.config.animationIntensity")
    @Config.RangeDouble(min = 0.0, max = 1.0)
    @Config.SlidingOption
    public static double animationIntensity = 1.0;
}
