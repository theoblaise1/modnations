// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Diffuseur d'oxygène (docs/conception/J5.md §5.2 et §5.3) : consomme son énergie et maintient une zone d'oxygène
 * ({@link ZoneIndex}) sur les chunks de sa nation, si celle-ci a {@code oxygen.systems}. Ébauche du socle : appelée à
 * chaque tick par {@link MachineBlockEntity}, sans effet jusqu'au lot WP3.
 */
public final class OxygenDiffuserLogic {
    private OxygenDiffuserLogic() {
    }

    /** Un tick serveur du diffuseur en {@code pos} ; état via les accesseurs de {@code machine}. */
    public static void tick(ServerLevel level, BlockPos pos, MachineBlockEntity machine) {
    }
}
