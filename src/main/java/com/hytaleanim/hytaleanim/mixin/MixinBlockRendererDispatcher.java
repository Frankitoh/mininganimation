package com.hytaleanim.hytaleanim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hytaleanim.hytaleanim.HideState;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

@Mixin(BlockRendererDispatcher.class)
public class MixinBlockRendererDispatcher {

    /** Evita que el bloque se meta en la malla del chunk mientras lo animamos. */
    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void hytaleanim$hideBlock(IBlockState state, BlockPos pos, IBlockAccess world,
                                      BufferBuilder buffer, CallbackInfoReturnable<Boolean> cir) {
        if (pos.toLong() == HideState.hiddenPos) {
            cir.setReturnValue(false);
        }
    }

    /** Quita la grieta de vanilla de ese bloque (dibujamos la nuestra sobre la copia animada). */
    @Inject(method = "renderBlockDamage", at = @At("HEAD"), cancellable = true)
    private void hytaleanim$hideVanillaCrack(IBlockState state, BlockPos pos, TextureAtlasSprite sprite,
                                             IBlockAccess world, CallbackInfo ci) {
        if (!HideState.drawingOwnCrack && pos.toLong() == HideState.hiddenPos) {
            ci.cancel();
        }
    }
}
