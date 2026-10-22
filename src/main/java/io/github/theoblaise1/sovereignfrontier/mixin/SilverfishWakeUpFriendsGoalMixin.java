package io.github.theoblaise1.sovereignfrontier.mixin;

import io.github.theoblaise1.sovereignfrontier.event.TerritoryEvents;
import net.minecraft.world.entity.monster.Silverfish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le poisson d'argent réveille ses congénères, qui cassent ou convertissent les blocs infestés dans un
 * rayon de 10 blocs (x, z). Ce goal n'emprunte pas {@code EntityMobGriefingEvent} : la règle des 2 blocs
 * ne s'applique donc pas, et on coupe le goal dès qu'un territoire touche sa zone de recherche.
 */
@Mixin(targets = "net.minecraft.world.entity.monster.Silverfish$SilverfishWakeUpFriendsGoal")
public abstract class SilverfishWakeUpFriendsGoalMixin {
    /** Rayon horizontal de la zone parcourue par le goal (voir son {@code tick}). */
    private static final int SEARCH_RADIUS = 10;

    @Shadow
    private Silverfish silverfish;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void sovereignfrontier$noWakeNearTerritory(CallbackInfoReturnable<Boolean> cir) {
        if (TerritoryEvents.territoryWithin(silverfish.level(), silverfish.blockPosition(), SEARCH_RADIUS)) {
            cir.setReturnValue(false);
        }
    }
}
