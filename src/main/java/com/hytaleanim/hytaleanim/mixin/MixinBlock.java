package com.hytaleanim.hytaleanim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hytaleanim.hytaleanim.HideState;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.IBlockAccess;

@Mixin(Block.class)
public class MixinBlock {

    /**
     * Mientras un bloque esta oculto (lo dibujamos animado aparte), los vecinos deben dibujar la cara que
     * mira hacia el, o al moverse la copia quedan huecos por donde se ve el vacio.
     */
    @Inject(method = "shouldSideBeRendered", at = @At("HEAD"), cancellable = true)
    private void hytaleanim$renderFaceTowardHidden(IBlockState state, IBlockAccess world, BlockPos pos,
                                                   EnumFacing side, CallbackInfoReturnable<Boolean> cir) {
        long hidden = HideState.hiddenPos;
        if (hidden == HideState.NONE) return;
        Vec3i d = side.getDirectionVec();
        BlockPos h = BlockPos.fromLong(hidden);
        if (pos.getX() + d.getX() == h.getX() && pos.getY() + d.getY() == h.getY()
                && pos.getZ() + d.getZ() == h.getZ()) {
            cir.setReturnValue(true);
        }
    }

    /**
     * La luz de un bloque opaco es 0, asi que las caras vecinas que miran hacia el bloque oculto saldrian
     * negras. Mientras esta oculto, su posicion toma la luz maxima (cielo y bloque) de sus 6 vecinos.
     */
    @Inject(method = "getPackedLightmapCoords", at = @At("HEAD"), cancellable = true)
    private void hytaleanim$lightForHidden(IBlockState state, IBlockAccess world, BlockPos pos,
                                           CallbackInfoReturnable<Integer> cir) {
        long hidden = HideState.hiddenPos;
        if (hidden == HideState.NONE || pos.toLong() != hidden) return;

        int sky = 0, block = 0;
        for (EnumFacing f : EnumFacing.values()) {
            int l = world.getCombinedLight(pos.offset(f), 0);
            sky = Math.max(sky, (l >> 20) & 15);
            block = Math.max(block, (l >> 4) & 15);
        }
        cir.setReturnValue((sky << 20) | (block << 4));
    }
}
