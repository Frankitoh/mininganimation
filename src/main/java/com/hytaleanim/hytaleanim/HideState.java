package com.hytaleanim.hytaleanim;

/**
 * Estado compartido entre el handler de cliente y el mixin.
 * Sin imports de Minecraft a proposito (el mixin lo lee desde el hilo que construye chunks).
 */
public final class HideState {
    public static final long NONE = Long.MIN_VALUE;

    /** BlockPos (toLong) del bloque que NO debe dibujarse en el chunk porque lo dibujamos nosotros animado. */
    public static volatile long hiddenPos = NONE;

    /** true mientras dibujamos nuestras propias grietas (para no cancelarlas en el mixin). */
    public static volatile boolean drawingOwnCrack = false;

    private HideState() {}
}
