package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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

    /**
     * Double coffre déjà formé avant une revendication : l'accès serveur (menu, entonnoir, capacité
     * NeoForge) ne voit que la moitié de la case interrogée quand l'autre moitié est hors de son territoire.
     */
    @Inject(method = "getContainer", at = @At("HEAD"), cancellable = true)
    private static void sovereignfrontier$singleHalfAcrossBorder(ChestBlock block, BlockState state, Level level,
                                                                 BlockPos pos, boolean override,
                                                                 CallbackInfoReturnable<Container> cir) {
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return;
        }
        BlockPos partner = pos.relative(ChestBlock.getConnectedDirection(state));
        if (TerritoryRules.separatedByBorder(level, pos, partner) && level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            cir.setReturnValue(chest);
        }
    }
}
