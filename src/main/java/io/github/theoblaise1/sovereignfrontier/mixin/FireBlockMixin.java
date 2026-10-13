package io.github.theoblaise1.sovereignfrontier.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.theoblaise1.sovereignfrontier.nation.TerritoryRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Le feu ne se propage pas et ne consume rien dans le territoire d'une autre nation. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    /** Combustion d'un bloc voisin : {@code face} est la face du bloc tournée vers le feu. */
    @Inject(method = "checkBurnOut", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noBurnAcrossBorder(Level level, BlockPos pos, int chance, RandomSource random, int age,
                                                     Direction face, CallbackInfo ci) {
        if (TerritoryRules.crossesBorder(level, pos.relative(face), pos)) {
            ci.cancel();
        }
    }

    /** Propagation : nouveaux blocs de feu posés autour de {@code firePos} pendant son tick. */
    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean sovereignfrontier$noSpreadAcrossBorder(ServerLevel level, BlockPos target, BlockState state, int flags,
                                                           @Local(argsOnly = true) BlockPos firePos) {
        return !TerritoryRules.crossesBorder(level, firePos, target);
    }
}
