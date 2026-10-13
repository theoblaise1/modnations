package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Un distributeur ne vide rien (lave, feu, TNT…) dans le territoire d'une autre nation. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noDispenseAcrossBorder(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        if (state.hasProperty(DispenserBlock.FACING)
                && TerritoryRules.crossesBorder(level, pos, pos.relative(state.getValue(DispenserBlock.FACING)))) {
            ci.cancel();
        }
    }
}
