// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Raffinerie (docs/conception/J5.md §1.4) : 3 bouteilles pleines + 1 poudre de blaze + énergie → 1 carburant de fusée
 * et 3 bouteilles vides, en territoire d'une nation qui a {@code rocket.fuel}. Ébauche du socle : appelée à chaque
 * tick par {@link MachineBlockEntity}, sans effet jusqu'au lot WP2.
 */
public final class RefineryLogic {
    private RefineryLogic() {
    }

    /** Un tick serveur de la Raffinerie en {@code pos} ; état et cases via les accesseurs de {@code machine}. */
    public static void tick(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
    }
}
