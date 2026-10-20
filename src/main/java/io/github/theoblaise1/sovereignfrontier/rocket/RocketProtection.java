// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Protection de la fusée contre les étrangers (docs/conception/J5.md §1.3 et §4.5). Les explosions n'ont aucun effet
 * (résistance 1 200, {@code #wither_immune}, {@code #dragon_immune}, définies sur le bloc et dans les tags) et les
 * pistons ne la poussent pas ; reste la casse par un joueur, réglée ici : une fusée n'est cassée que par
 * <ul>
 *   <li>un membre de la nation propriétaire ;</li>
 *   <li>un bâtisseur ({@code construire}) de la nation qui possède le chunk où elle se trouve ;</li>
 *   <li>n'importe qui si la nation propriétaire n'existe plus (ou si la fusée n'a jamais eu de nation : elle est
 *       alors inerte).</li>
 * </ul>
 * Un opérateur en {@code /sfadmin bypass} fait exception. La protection du territoire ({@code TerritoryEvents}) reste
 * en plus appliquée à toute casse en territoire.
 */
public final class RocketProtection {
    private RocketProtection() {
    }

    /**
     * Règle de casse (fonction pure).
     *
     * @param rocketNation nation propriétaire, ou {@code null} si elle n'existe pas (ou plus)
     * @param chunkNation  nation qui possède le chunk de la fusée, ou {@code null} en zone libre
     */
    public static boolean mayBreak(UUID player, boolean admin, @Nullable Nation rocketNation, @Nullable Nation chunkNation) {
        if (admin || rocketNation == null) {
            return true;
        }
        if (rocketNation.isMember(player)) {
            return true;
        }
        return chunkNation != null && chunkNation.hasPermission(player, NationPermission.BUILD);
    }

    /** Casse d'une fusée en {@code pos} par ce joueur, d'après l'état réel des nations. */
    public static boolean mayBreak(ServerPlayer player, ServerLevel level, BlockPos pos, RocketBlockEntity rocket) {
        NationData data = NationData.get(level.getServer());
        Nation owner = rocket.owner() == null ? null : data.byId(rocket.owner());
        return mayBreak(player.getUUID(), AdminBypass.isActive(player), owner, data.ownerAt(level.dimension(), pos));
    }

    /**
     * Embarquement et ouverture de la soute : membres de la nation propriétaire seulement (un administrateur en
     * contournement ouvre la soute). Crochet du J7 : les alliés pourront être admis ici.
     */
    public static boolean mayBoard(@Nullable Nation rocketNation, UUID player) {
        return rocketNation != null && rocketNation.isMember(player);
    }

    public static boolean mayOpenHold(@Nullable Nation rocketNation, ServerPlayer player) {
        return AdminBypass.isActive(player) || mayBoard(rocketNation, player.getUUID());
    }
}
