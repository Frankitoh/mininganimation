package com.hytaleanim.hytaleanim.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hytaleanim.hytaleanim.HideState;

import net.minecraft.block.BlockBreakable;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.IBlockAccess;

@Mixin(BlockBreakable.class)
public class MixinBlockBreakable {

    /**
     * BlockBreakable (vidrio, hielo, slime...) tambien sobrescribe shouldSideBeRendered.
     * Misma logica que MixinBlock: la cara que mira al bloque oculto debe dibujarse.
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
}
