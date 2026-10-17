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
 * Animacion estilo Hytale: cada golpe da un tiron corto al bloque, en una direccion distinta cada vez.
 * El bloque real se oculta del chunk (mixin) mientras se pica y lo dibujamos nosotros.
 */
public class ClientHandler {

    /** Ticks minimos entre dos golpes animados. */
    private static final int MIN_HIT_INTERVAL = 4;
    /** Ticks tras dejar de picar antes de volver a mostrar el bloque real. */
    private static final int SETTLE_TICKS = 7;
    /** Ticks tras dejar de picar en los que aun dibujamos la copia (para que el chunk se reconstruya). */
    private static final int END_TICKS = 10;
    /** Resorte que recibe el empujon de cada golpe: frecuencia (Hz, a velocidad 1.0) y amortiguacion (1.0 = sin rebote). */
    private static final double SPRING_FREQ = 3.0;
    private static final double SPRING_DAMPING = 0.85;
    /** Desplazamiento maximo aprox. de un golpe (bloques) y giro (grados), con intensidad 1.0. */
    private static final float JOLT_MOVE = 0.035f, JOLT_ROT = 1.2f;
    /** Trocitos que salen por golpe. */
    private static final int CHIPS_PER_HIT = 6;

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
    // Estado de los resortes (traslacion y giro en x,y,z) y direccion del ultimo golpe
    private final double[] pos3 = new double[3], vel3 = new double[3];
    private final double[] rot3 = new double[3], rotVel3 = new double[3];
    private final float[] dir = new float[3];
    private float twist;
    private long lastRenderNano = -1;

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
                resetSprings();
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
        pickDirection();

        // Empujon al resorte: desplaza el bloque y lo inclina de forma coherente con el empuje
        double w = 2.0 * Math.PI * SPRING_FREQ;
        double v0 = JOLT_MOVE * w * Math.E;
        double r0 = JOLT_ROT * w * Math.E;
        for (int i = 0; i < 3; i++) vel3[i] += dir[i] * v0;
        rotVel3[0] += -dir[2] * r0;
        rotVel3[2] += dir[0] * r0;
        rotVel3[1] += 0.5 * twist * r0;

        if (ModConfig.hitParticles && animPos != null) spawnChips(mc.world.getBlockState(animPos));
    }

    /** Avanza los resortes h segundos (con subpasos para que sea estable). */
    private void stepSprings(double h) {
        if (h <= 0) return;
        double w = 2.0 * Math.PI * SPRING_FREQ;
        int n = Math.max(1, (int) Math.ceil(h / 0.004));
        double dt = h / n;
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < 3; i++) {
                vel3[i] += (-w * w * pos3[i] - 2.0 * SPRING_DAMPING * w * vel3[i]) * dt;
                pos3[i] += vel3[i] * dt;
                rotVel3[i] += (-w * w * rot3[i] - 2.0 * SPRING_DAMPING * w * rotVel3[i]) * dt;
                rot3[i] += rotVel3[i] * dt;
            }
        }
    }

    private void resetSprings() {
        for (int i = 0; i < 3; i++) { pos3[i] = vel3[i] = rot3[i] = rotVel3[i] = 0; }
        lastRenderNano = -1;
    }

    /** Direccion 3D aleatoria para el tiron, distinta de la del golpe anterior (para que no se vea repetido). */
    private void pickDirection() {
        float px = dir[0], py = dir[1], pz = dir[2];
        for (int tries = 0; tries < 8; tries++) {
            float x = rand.nextFloat() * 2f - 1f, y = rand.nextFloat() * 2f - 1f, z = rand.nextFloat() * 2f - 1f;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 0.2f) continue;
            x /= len; y /= len; z /= len;
            dir[0] = x; dir[1] = y; dir[2] = z;
            if (x * px + y * py + z * pz < 0.3f) break; // evita repetir casi la misma direccion
        }
        twist = (rand.nextFloat() * 2f - 1f);
    }

    /** Rafaga de trocitos del bloque en el punto de impacto. */
    private void spawnChips(IBlockState st) {
        RayTraceResult r = mc.objectMouseOver;
        if (r == null || r.typeOfHit != RayTraceResult.Type.BLOCK || r.hitVec == null || r.sideHit == null) return;
        int id = Block.getStateId(st);
        int n = CHIPS_PER_HIT;
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
        else if (lastRenderNano >= 0) resetSprings();
    }

    private void renderMining(double cx, double cy, double cz) {
        BlockPos pos = animPos;
        IBlockState base = mc.world.getBlockState(pos);
        if (!isAnimatable(base)) return;

        // Avanzar los resortes con el tiempo real del frame (la velocidad de la config acelera o frena todo)
        long now = System.nanoTime();
        double dt = lastRenderNano < 0 ? 0 : Math.min((now - lastRenderNano) / 1.0e9, 0.05);
        lastRenderNano = now;
        stepSprings(dt * ModConfig.animationSpeed);

        float in = (float) ModConfig.animationIntensity;
        float mx = (float) pos3[0] * in, my = (float) pos3[1] * in, mz = (float) pos3[2] * in;
        float rx = (float) rot3[0] * in, ry = (float) rot3[1] * in, rz = (float) rot3[2] * in;

        IBlockState actual = base.getActualState(mc.world, pos);
        IBakedModel model = mc.getBlockRendererDispatcher().getModelForState(actual);
        IBlockState ext = actual.getBlock().getExtendedState(actual, mc.world, pos);

        GlStateManager.pushMatrix();
        GlStateManager.translate(pos.getX() + 0.5 + mx - cx, pos.getY() + 0.5 + my - cy, pos.getZ() + 0.5 + mz - cz);
        GlStateManager.rotate(rx, 1, 0, 0);
        GlStateManager.rotate(ry, 0, 1, 0);
        GlStateManager.rotate(rz, 0, 0, 1);
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
        GlStateManager.color(1f, 1f, 1f, 1f);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            if (!state.getBlock().canRenderInLayer(state, layer)) continue;
            ForgeHooksClient.setRenderLayer(layer);
            setupLayerState(layer);
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

    /**
     * Estado GL por capa, igual que vanilla: SOLID sin blend ni alpha test (en graficos rapidos las hojas
     * son solidas y su parte "negra" tiene alpha 0: con blend se veria transparente), CUTOUT con alpha test
     * y TRANSLUCENT con blend.
     */
    private void setupLayerState(BlockRenderLayer layer) {
        switch (layer) {
            case SOLID:
                GlStateManager.disableBlend();
                GlStateManager.disableAlpha();
                break;
            case CUTOUT_MIPPED:
            case CUTOUT:
                GlStateManager.disableBlend();
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
                break;
            default: // TRANSLUCENT
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA,
                        SourceFactor.ONE, DestFactor.ZERO);
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
                break;
        }
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
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
    }
}
