package io.github.theoblaise1.sovereignfrontier.nation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;

/**
 * Règle de frontière partagée par les événements et les mixins : une action qui part de
 * {@code from} (fluide qui coule, feu qui se propage, distributeur, piston) ne peut pas modifier
 * {@code to} si {@code to} appartient à une nation différente de celle de {@code from}.
 * Une seule recherche O(1) par position dans le cache par chunk.
 */
public final class TerritoryRules {
    private TerritoryRules() {
    }

    public static boolean crossesBorder(LevelAccessor level, BlockPos from, BlockPos to) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        if ((from.getX() >> 4) == (to.getX() >> 4) && (from.getZ() >> 4) == (to.getZ() >> 4)) {
            return false;
        }
        NationData data = NationData.get(serverLevel.getServer());
        Nation target = data.ownerAt(serverLevel.dimension(), to);
        return target != null && target != data.ownerAt(serverLevel.dimension(), from);
    }
}
