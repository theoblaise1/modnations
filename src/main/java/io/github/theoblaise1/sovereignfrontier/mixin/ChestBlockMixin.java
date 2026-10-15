package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ChestBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deux coffres séparés par une frontière ne forment jamais un double coffre : sinon la moitié
 * posée hors du territoire donnerait accès (menu, entonnoir) au contenu de l'autre.
 */
@Mixin(ChestBlock.class)
public abstract class ChestBlockMixin {
    /** Voisin candidat à l'assemblage, vu depuis la case où le coffre est posé. */
    @Inject(method = "candidatePartnerFacing", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noPairAcrossBorder(BlockPlaceContext context, Direction direction,
                                                      CallbackInfoReturnable<Direction> cir) {
        BlockPos pos = context.getClickedPos();
        if (TerritoryRules.separatedByBorder(context.getLevel(), pos, pos.relative(direction))) {
            cir.setReturnValue(null);
        }
    }
}
