package com.hytaleanim.hytaleanim;

import java.lang.reflect.Field;
import java.util.Random;

import org.lwjgl.opengl.GL11;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Animacion estilo Hytale: cada golpe sacude el bloque un poco en todas las direcciones.
 * El bloque real se oculta del chunk (mixin) mientras se pica y lo dibujamos nosotros.
 */
public class ClientHandler {

    /** Ticks minimos entre dos golpes animados. */
    private static final int MIN_HIT_INTERVAL = 4;
    /** Ticks tras dejar de picar antes de volver a mostrar el bloque real. */
    private static final int SETTLE_TICKS = 7;
    /** Ticks tras dejar de picar en los que aun dibujamos la copia (para que el chunk se reconstruya). */
    private static final int END_TICKS = 10;
    /** Cuanto tarda en apagarse la sacudida (mayor = mas corta). */
    private static final double SHAKE_DECAY = 9.0;
    /** Rango de frecuencias de la sacudida por eje (Hz, a velocidad 1.0). */
    private static final double SHAKE_FREQ_MIN = 9.0, SHAKE_FREQ_MAX = 16.0;
    /** Amplitud maxima por eje: traslacion (bloques) y giro (grados), con fuerza 1.0. */
    private static final float SHAKE_MOVE = 0.03f, SHAKE_ROT = 1.6f;
    /** Fuerza del golpe segun herramienta. 1.0 = lo medido en Hytale sin herramienta; con la correcta es el doble. */
    private static final float STRENGTH_RIGHT_TOOL = 2.0f;
    private static final float STRENGTH_WRONG_TOOL = 1.0f;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rand = new Random();
    private Field damageField; // PlayerControllerMP.curBlockDamageMP

    // Bloque al que apunta el jugador (foto tomada al inicio de cada tick)
    private BlockPos lookPos;

    // Animacion de picado
    private BlockPos animPos;
    private int stopTicks;
    private int tickCounter;
    private int lastHitTick = -100;
    private int lastSwing = -1;
    private long hitNano = -1;
    private float hitStrength = 1f;
    // Sacudida del ultimo golpe: por cada eje (x,y,z) una amplitud, frecuencia y fase aleatorias
    private final float[] moveAmp = new float[3], rotAmp = new float[3];
    private final double[] freq = new double[3], phase = new double[3];

    public ClientHandler() {
        try {
            damageField = ReflectionHelper.findField(PlayerControllerMP.class, "field_78770_f", "curBlockDamageMP");
        } catch (Exception ex) {
            HytaleAnim.LOGGER.error("No se pudo acceder a curBlockDamageMP, las grietas no se dibujaran en la animacion", ex);
        }
    }

    // ------------------------------------------------------------------ TICK

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (mc.world == null || mc.player == null) {
            HideState.hiddenPos = HideState.NONE;
            lookPos = null; animPos = null;
            return;
        }
        if (e.phase == TickEvent.Phase.START) {
            RayTraceResult r = mc.objectMouseOver;
            if (r != null && r.typeOfHit == RayTraceResult.Type.BLOCK && r.getBlockPos() != null) {
                lookPos = r.getBlockPos();
            } else {
                lookPos = null;
            }
            return;
        }
        tickCounter++;
        updateMining();
    }

    private void updateMining() {
        BlockPos target = null;
        if (ModConfig.miningAnimation && mc.currentScreen == null
                && mc.gameSettings.keyBindAttack.isKeyDown()
                && mc.playerController.getIsHittingBlock()
                && lookPos != null && isAnimatable(mc.world.getBlockState(lookPos))) {
            target = lookPos;
        }

        // Cada vez que arranca un nuevo swing = un golpe
        int swing = mc.player.isSwingInProgress ? mc.player.swingProgressInt : -1;
        boolean newSwing = swing >= 0 && (lastSwing < 0 || swing <= lastSwing);
        lastSwing = swing;

        if (target != null) {
            if (animPos == null || !animPos.equals(target)) {
                if (animPos != null) showRealBlock();
                animPos = target;
                hide(animPos);
                hit();
            } else if (HideState.hiddenPos != animPos.toLong()) {
                hide(animPos); // volvio a picar justo despues de soltar
            }
            stopTicks = 0;
            if (newSwing && tickCounter - lastHitTick >= MIN_HIT_INTERVAL) hit();
        } else if (animPos != null) {
            stopTicks++;
            if (stopTicks == SETTLE_TICKS) showRealBlock();
            if (stopTicks >= END_TICKS) animPos = null;
        }

        if (animPos != null && mc.world.isAirBlock(animPos)) {
            showRealBlock();
            animPos = null;
        }
    }

    private void hit() {
        lastHitTick = tickCounter;
        hitNano = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            float sign = rand.nextBoolean() ? 1f : -1f;
            moveAmp[i] = sign * (0.5f + 0.5f * rand.nextFloat());
            rotAmp[i] = (rand.nextBoolean() ? 1f : -1f) * (0.5f + 0.5f * rand.nextFloat());
            freq[i] = SHAKE_FREQ_MIN + rand.nextDouble() * (SHAKE_FREQ_MAX - SHAKE_FREQ_MIN);
            phase[i] = rand.nextDouble() * Math.PI * 2.0;
        }

        // Sin la herramienta correcta la animacion es mas sutil (como en Hytale)
        boolean rightTool = false;
        IBlockState st = null;
        if (animPos != null) {
            st = mc.world.getBlockState(animPos);
            rightTool = mc.player.getHeldItemMainhand().getDestroySpeed(st) > 1.0f;
        }
        hitStrength = rightTool ? STRENGTH_RIGHT_TOOL : STRENGTH_WRONG_TOOL;

        if (ModConfig.hitParticles && st != null) spawnChips(st);
    }

    /** Rafaga de trocitos del bloque en el punto de impacto. */
    private void spawnChips(IBlockState st) {
        RayTraceResult r = mc.objectMouseOver;
        if (r == null || r.typeOfHit != RayTraceResult.Type.BLOCK || r.hitVec == null || r.sideHit == null) return;
        int id = Block.getStateId(st);
        int n = hitStrength >= 1.5f ? 8 : 5;
        net.minecraft.util.math.Vec3i face = r.sideHit.getDirectionVec();
        for (int i = 0; i < n; i++) {
            double vx = face.getX() * 0.15 + (rand.nextDouble() - 0.5) * 0.2;
            double vy = 0.08 + rand.nextDouble() * 0.15 + face.getY() * 0.1;
            double vz = face.getZ() * 0.15 + (rand.nextDouble() - 0.5) * 0.2;
            mc.effectRenderer.spawnEffectParticle(EnumParticleTypes.BLOCK_CRACK.getParticleID(),
                    r.hitVec.x, r.hitVec.y, r.hitVec.z, vx, vy, vz, id);
        }
    }

    private void hide(BlockPos pos) {
        HideState.hiddenPos = pos.toLong();
        refreshChunk(pos);
    }

    private void showRealBlock() {
        if (animPos == null) return;
        HideState.hiddenPos = HideState.NONE;
        refreshChunk(animPos);
    }

    /** Fuerza la reconstruccion inmediata del chunk (flag 8 = main thread) para aplicar/quitar el ocultado. */
    private void refreshChunk(BlockPos pos) {
        IBlockState s = mc.world.getBlockState(pos);
        mc.renderGlobal.notifyBlockUpdate(mc.world, pos, s, s, 8);
    }

    private boolean isAnimatable(IBlockState s) {
        return s.getRenderType() == EnumBlockRenderType.MODEL && s.getMaterial() != Material.AIR;
    }

    // ---------------------------------------------------------------- RENDER

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (mc.world == null) return;
        Entity cam = mc.getRenderViewEntity();
        if (cam == null) return;

        float pt = e.getPartialTicks();
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * pt;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * pt;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * pt;

        if (animPos != null) renderMining(cx, cy, cz);
    }

    private void renderMining(double cx, double cy, double cz) {
        BlockPos pos = animPos;
        IBlockState base = mc.world.getBlockState(pos);
        if (!isAnimatable(base)) return;

        // Sacudida sutil en todos los ejes: cada eje oscila a su propia frecuencia y se apaga rapido.
        double t = 0;
        float env = 0f;
        if (hitNano > 0 && stopTicks < SETTLE_TICKS) {
            t = (System.nanoTime() - hitNano) / 1.0e9 * ModConfig.animationSpeed;
            // entrada suave (sin salto) y caida exponencial
            env = (float) ((1.0 - Math.exp(-t * 150.0)) * Math.exp(-SHAKE_DECAY * t));
        }
        float k = (float) ModConfig.animationIntensity * hitStrength * env;
        float[] mv = new float[3], rt = new float[3];
        for (int i = 0; i < 3; i++) {
            float w = (float) Math.sin(2.0 * Math.PI * freq[i] * t + phase[i]);
            mv[i] = SHAKE_MOVE * k * moveAmp[i] * w;
            // el giro usa otro desfase para que no vaya "pegado" a la traslacion
            float w2 = (float) Math.cos(2.0 * Math.PI * freq[i] * 0.8 * t + phase[(i + 1) % 3]);
            rt[i] = SHAKE_ROT * k * rotAmp[i] * w2;
        }

        IBlockState actual = base.getActualState(mc.world, pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        IBlockState ext = actual.getBlock().getExtendedState(actual, mc.world, pos);

        GlStateManager.pushMatrix();
        GlStateManager.translate(pos.getX() + 0.5 + mv[0] - cx, pos.getY() + 0.5 + mv[1] - cy, pos.getZ() + 0.5 + mv[2] - cz);
        GlStateManager.rotate(rt[0], 1, 0, 0);
        GlStateManager.rotate(rt[1], 0, 1, 0);
        GlStateManager.rotate(rt[2], 0, 0, 1);
        GlStateManager.translate(-0.5, -0.5, -0.5);
        drawModel(ext, model, pos);
        drawCrack(base, pos);
        GlStateManager.popMatrix();
        resetGl();
    }

    /** Dibuja el modelo del bloque en coordenadas locales (0..1) con la matriz GL actual. */
    private void drawModel(IBlockState state, IBakedModel model, BlockPos pos) {
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        mc.entityRenderer.enableLightmap();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA,
                SourceFactor.ONE, DestFactor.ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.color(1f, 1f, 1f, 1f);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            if (!state.getBlock().canRenderInLayer(state, layer)) continue;
            ForgeHooksClient.setRenderLayer(layer);
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            buf.setTranslation(-pos.getX(), -pos.getY(), -pos.getZ());
            mc.getBlockRendererDispatcher().getBlockModelRenderer()
                    .renderModel(mc.world, model, state, pos, buf, false);
            buf.setTranslation(0, 0, 0);
            tess.draw();
        }
        ForgeHooksClient.setRenderLayer(null);
        mc.entityRenderer.disableLightmap();
    }

    /** Dibuja la grieta de destruccion sobre la copia animada (la de vanilla la cancela el mixin). */
    private void drawCrack(IBlockState base, BlockPos pos) {
        if (damageField == null) return;
        float progress;
        try {
            progress = damageField.getFloat(mc.playerController);
        } catch (Exception ex) {
            return;
        }
        int stage = (int) (progress * 10f) - 1;
        if (stage < 0 || stage > 9) return;

        TextureAtlasSprite sprite = mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/destroy_stage_" + stage);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.tryBlendFuncSeparate(SourceFactor.DST_COLOR, DestFactor.SRC_COLOR,
                SourceFactor.ONE, DestFactor.ZERO);
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, 0.5f);
        GlStateManager.doPolygonOffset(-3f, -3f);
        GlStateManager.enablePolygonOffset();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        GlStateManager.enableAlpha();

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        buf.setTranslation(-pos.getX(), -pos.getY(), -pos.getZ());
        buf.noColor();
        HideState.drawingOwnCrack = true;
        try {
            mc.getBlockRendererDispatcher().renderBlockDamage(base, pos, sprite, mc.world);
        } finally {
            HideState.drawingOwnCrack = false;
        }
        tess.draw();
        buf.setTranslation(0, 0, 0);
        GlStateManager.disablePolygonOffset();
    }

    private void resetGl() {
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.tryBlendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA,
                SourceFactor.ONE, DestFactor.ZERO);
        GlStateManager.disableBlend();
    }
}
