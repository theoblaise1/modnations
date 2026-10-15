package io.github.theoblaise1.sovereignfrontier.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

/** Une éponge n'absorbe ni eau, ni algues dans le territoire d'une autre nation. */
@Mixin(SpongeBlock.class)
public abstract class SpongeBlockMixin {
    /** Parcours en largeur de l'absorption : les cases au-delà de la frontière ne sont ni vidées ni traversées. */
    @ModifyArg(method = "removeWaterBreadthFirstSearch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;breadthFirstTraversal(Lnet/minecraft/core/BlockPos;IILjava/util/function/BiConsumer;Ljava/util/function/Predicate;)I"),
            index = 4)
    private Predicate<BlockPos> sovereignfrontier$stopAtBorder(Predicate<BlockPos> absorb,
                                                             @Local(argsOnly = true) Level level,
                                                             @Local(argsOnly = true) BlockPos sponge) {
        return pos -> !TerritoryRules.crossesBorder(level, sponge, pos) && absorb.test(pos);
    }
}
