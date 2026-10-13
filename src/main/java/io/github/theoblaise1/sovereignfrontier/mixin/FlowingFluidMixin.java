package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un fluide (lave, eau) ne s'écoule pas dans le territoire d'une autre nation. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$stopAtBorder(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
                                               FluidState fluid, CallbackInfo ci) {
        if (TerritoryRules.crossesBorder(level, pos.relative(direction.getOpposite()), pos)) {
            ci.cancel();
        }
    }
}
