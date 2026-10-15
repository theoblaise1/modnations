package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Les œufs de tortue ne sont pas piétinés par un joueur sans droit sur le territoire (aucun événement de casse). */
@Mixin(TurtleEggBlock.class)
public abstract class TurtleEggBlockMixin {
    @Inject(method = "stepOn", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noTrampleForStrangers(Level level, BlockPos pos, BlockState state, Entity entity,
                                                        CallbackInfo ci) {
        if (TerritoryEvents.blocksTrigger(entity, level, pos)) {
            ci.cancel();
        }
    }
}
