package com.hytaleanim.hytaleanim;

import java.lang.reflect.Field;
import java.util.Random;

import org.lwjgl.opengl.GL11;

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
 * Animacion estilo Hytale: cada golpe aplasta el bloque y este vuelve a su tamano.
 * El bloque real se oculta del chunk (mixin) mientras se pica y lo dibujamos nosotros.
 */
public class ClientHandler {

    /** Ticks minimos entre dos golpes animados. */
    private static final int MIN_HIT_INTERVAL = 4;
    /** Ticks tras dejar de picar antes de volver a mostrar el bloque real. */
    private static final int SETTLE_TICKS = 7;
    /** Ticks tras dejar de picar en los que aun dibujamos la copia (para que el chunk se reconstruya). */
    private static final int END_TICKS = 10;
    /** Segundos (a velocidad 1.0) que tarda el bloque en llegar al punto mas bajo del golpe. */
    private static final double HIT_RISE = 0.04;
    /** Rapidez con la que vuelve a su posicion despues (mayor = mas seco). */
    private static final double HIT_DECAY = 14.0;
    /** Fuerza del golpe segun herramienta: con la herramienta correcta / sin ella. */
    private static final float STRENGTH_RIGHT_TOOL = 1.0f;
    private static final float STRENGTH_WRONG_TOOL = 0.4f;

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
    private float jitterX, jitterZ;
    private float hitStrength = 1f;

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
        jitterX = (rand.nextFloat() - 0.5f) * 2f; // +-1 grado
        jitterZ = (rand.nextFloat() - 0.5f) * 2f;

        // Sin la herramienta correcta la animacion es mas sutil (como en Hytale)
        boolean rightTool = false;
        if (animPos != null) {
            IBlockState st = mc.world.getBlockState(animPos);
            rightTool = mc.player.getHeldItemMainhand().getDestroySpeed(st) > 1.0f;
        }
        hitStrength = rightTool ? STRENGTH_RIGHT_TOOL : STRENGTH_WRONG_TOOL;
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

        // Golpe sutil: el bloque se hunde un poquito (~3% de su altura) y vuelve en ~0.15 s.
        // f: 0 -> 1 en HIT_RISE segundos, luego decae exponencialmente a 0.
        float f = 0f;
        if (hitNano > 0 && stopTicks < SETTLE_TICKS) {
            double t = (System.nanoTime() - hitNano) / 1.0e9 * ModConfig.animationSpeed;
            f = (float) (t < HIT_RISE ? t / HIT_RISE : Math.exp(-HIT_DECAY * (t - HIT_RISE)));
        }
        float k = (float) ModConfig.animationIntensity * hitStrength * f;

        float dip = 0.035f * k;                  // baja (en bloques)
        float sy = 1f - 0.015f * k;              // y se aplasta apenas
        float sxz = 1f - 0.004f * k;
        float tiltX = jitterX * 0.6f * k;
        float tiltZ = jitterZ * 0.6f * k;

        IBlockState actual = base.getActualState(mc.world, pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        IBlockState ext = actual.getBlock().getExtendedState(actual, mc.world, pos);

        GlStateManager.pushMatrix();
        // pivote en el centro de la base: el bloque se aplasta contra el suelo
        GlStateManager.translate(pos.getX() + 0.5 - cx, pos.getY() - dip - cy, pos.getZ() + 0.5 - cz);
        GlStateManager.rotate(tiltZ, 0, 0, 1);
        GlStateManager.rotate(tiltX, 1, 0, 0);
        GlStateManager.scale(sxz, sy, sxz);
        GlStateManager.translate(-0.5, 0, -0.5);
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
