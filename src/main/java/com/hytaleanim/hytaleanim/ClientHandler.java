package com.hytaleanim.hytaleanim;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

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

public class ClientHandler {

    /** Escala final del bloque al "convertirse" en item (los items-bloque se dibujan a ~0.25). */
    private static final float SHRINK_END_SCALE = 0.25f;
    /** Frecuencia base del movimiento en Hz (con velocidad 1.0). */
    private static final double BASE_FREQ = 3.0;

    private final Minecraft mc = Minecraft.getMinecraft();
    private Field damageField; // PlayerControllerMP.curBlockDamageMP

    // Bloque al que apunta el jugador (foto tomada al inicio de cada tick)
    private BlockPos lookPos;
    private IBlockState lookState;

    // Animación de picado
    private BlockPos animPos;
    private float blend, prevBlend;
    private double clock;
    private long lastNano = -1;

    // Animaciones de rotura
    private final List<ShrinkAnim> shrinks = new LinkedList<>();

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
            lookPos = null; lookState = null; animPos = null; shrinks.clear();
            return;
        }
        if (e.phase == TickEvent.Phase.START) {
            RayTraceResult r = mc.objectMouseOver;
            if (r != null && r.typeOfHit == RayTraceResult.Type.BLOCK && r.getBlockPos() != null) {
                lookPos = r.getBlockPos();
                lookState = mc.world.getBlockState(lookPos);
            } else {
                lookPos = null;
                lookState = null;
            }
            return;
        }
        detectBreak();
        updateMining();
        Iterator<ShrinkAnim> it = shrinks.iterator();
        while (it.hasNext()) {
            ShrinkAnim a = it.next();
            if (++a.age >= a.duration) it.remove();
        }
    }

    private void detectBreak() {
        if (!ModConfig.shrinkOnBreak || lookPos == null || lookState == null) return;
        if (lookState.getMaterial() == Material.AIR) return;
        if (lookState.getRenderType() != EnumBlockRenderType.MODEL) return;

        IBlockState now = mc.world.getBlockState(lookPos);
        if (now.getBlock() == lookState.getBlock()) return;
        if (now.getMaterial() != Material.AIR && !now.getMaterial().isLiquid()) return;

        boolean mining = mc.gameSettings.keyBindAttack.isKeyDown() || mc.player.isSwingInProgress;
        if (!mining) return;

        shrinks.add(new ShrinkAnim(lookPos, lookState));
    }

    private void updateMining() {
        BlockPos target = null;
        if (ModConfig.miningAnimation && mc.currentScreen == null
                && mc.gameSettings.keyBindAttack.isKeyDown()
                && mc.playerController.getIsHittingBlock()
                && lookPos != null && isAnimatable(mc.world.getBlockState(lookPos))) {
            target = lookPos;
        }

        prevBlend = blend;
        if (target != null) {
            if (animPos == null || !animPos.equals(target)) {
                animPos = target;
                blend = 0f;
                prevBlend = 0f;
            }
            blend = Math.min(1f, blend + 0.34f);
        } else {
            blend = Math.max(0f, blend - 0.25f);
            if (blend <= 0f) animPos = null;
        }
        if (animPos != null && mc.world.isAirBlock(animPos)) animPos = null;
    }

    /**
     * Solo animamos cubos completos y opacos: la copia animada es un poco mas grande que
     * el bloque real, asi que lo tapa por completo (no hace falta ocultar el bloque original).
     */
    private boolean isAnimatable(IBlockState s) {
        return s.getRenderType() == EnumBlockRenderType.MODEL && s.isOpaqueCube() && s.isFullCube();
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

        long nowNs = System.nanoTime();
        if (lastNano < 0) lastNano = nowNs;
        double dt = Math.min((nowNs - lastNano) / 1.0e9, 0.1);
        lastNano = nowNs;
        clock += dt * ModConfig.animationSpeed; // <- aqui actua la velocidad configurable

        if (animPos != null) renderWiggle(cx, cy, cz, pt);
        for (ShrinkAnim a : shrinks) renderShrink(a, cx, cy, cz, pt);
    }

    private void renderWiggle(double cx, double cy, double cz, float pt) {
        BlockPos pos = animPos;
        IBlockState base = mc.world.getBlockState(pos);
        if (!isAnimatable(base)) return;

        float b = prevBlend + (blend - prevBlend) * pt;
        if (b <= 0.001f) return;

        float in = (float) ModConfig.animationIntensity;
        double w = clock * Math.PI * 2.0 * BASE_FREQ;
        float pulse = (float) Math.sin(w);

        // Squash & stretch. Siempre >= 1.0 para cubrir el bloque original.
        float sxz = 1f + in * (0.055f + 0.020f * pulse);
        float sy = 1f + in * (0.055f - 0.020f * pulse);
        sxz = 1.002f + (sxz - 1.002f) * b;
        sy = 1.002f + (sy - 1.002f) * b;

        float tiltZ = 1.0f * in * b * (float) Math.sin(w + 1.0);
        float tiltX = 0.8f * in * b * (float) Math.cos(w * 0.7);

        IBlockState actual = base.getActualState(mc.world, pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        IBlockState ext = actual.getBlock().getExtendedState(actual, mc.world, pos);

        GlStateManager.pushMatrix();
        GlStateManager.translate(pos.getX() + 0.5 - cx, pos.getY() + 0.5 - cy, pos.getZ() + 0.5 - cz);
        GlStateManager.rotate(tiltZ, 0, 0, 1);
        GlStateManager.rotate(tiltX, 1, 0, 0);
        GlStateManager.scale(sxz, sy, sxz);
        GlStateManager.translate(-0.5, -0.5, -0.5);
        drawModel(ext, model, pos);
        drawCrack(base, pos);
        GlStateManager.popMatrix();
        resetGl();
    }

    private void renderShrink(ShrinkAnim a, double cx, double cy, double cz, float pt) {
        float p = Math.min(1f, (a.age + pt) / a.duration);
        float eased = p * p * (3f - 2f * p);
        float s = 1f - (1f - SHRINK_END_SCALE) * eased;

        GlStateManager.pushMatrix();
        GlStateManager.translate(a.pos.getX() + 0.5 - cx, a.pos.getY() + 0.5 - cy, a.pos.getZ() + 0.5 - cz);
        GlStateManager.scale(s, s, s);
        GlStateManager.translate(-0.5, -0.5, -0.5);
        drawModel(a.state, a.model, a.pos);
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

    /** Como la copia animada tapa la textura de grietas de vanilla, la dibujamos encima. */
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
        mc.getBlockRendererDispatcher().renderBlockDamage(base, pos, sprite, mc.world);
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

    // ----------------------------------------------------------------- DATA

    private class ShrinkAnim {
        final BlockPos pos;
        final IBlockState state;
        final IBakedModel model;
        final int duration;
        int age;

        ShrinkAnim(BlockPos pos, IBlockState base) {
            this.pos = pos;
            IBlockState actual = base.getActualState(mc.world, pos);
            this.model = mc.getBlockRendererDispatcher().getModelForState(actual);
            this.state = actual.getBlock().getExtendedState(actual, mc.world, pos);
            this.duration = Math.max(1, ModConfig.shrinkDuration);
        }
    }
}
