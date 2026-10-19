// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Chaudière à gaz et Réchauffeur nucléaire (docs/conception/J5.md §1.6) : brûlent leur combustible et maintiennent une
 * zone de chaleur ({@link ZoneIndex}) s'ils sont sur le territoire d'une nation qui a leur clé. Ébauche du socle :
 * appelées à chaque tick par {@link MachineBlockEntity}, sans effet jusqu'au lot WP3.
 */
public final class HeaterLogic {
    private HeaterLogic() {
    }

    /** Un tick serveur de la Chaudière à gaz en {@code pos}. */
    public static void tickBoiler(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
    }

    /** Un tick serveur du Réchauffeur nucléaire en {@code pos}. */
    public static void tickNuclear(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
    }
}
