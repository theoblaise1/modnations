package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Une plaque de pression ne s'active pas sous le pas d'un joueur sans droit sur le territoire. */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noTriggerForStrangers(BlockState state, Level level, BlockPos pos, Entity entity,
                                                        CallbackInfo ci) {
        if (TerritoryEvents.blocksTrigger(entity, level, pos)) {
            ci.cancel();
        }
    }
}
