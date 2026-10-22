// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKeys;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.UUID;

/**
 * Gardiens de donjon (J6, docs/conception/J6.md §1.3, §3) : mort du gardien (recharge fixée, clé militaire remise à la
 * nation, message) et gardien non suivi refusé au chargement (doublon après déchargement d'un chunk). Chaque gestionnaire
 * ne fait que des recherches O(1) dans {@link DungeonData}.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class DungeonEvents {
    private DungeonEvents() {
    }

    /** Un gardien dont l'UUID n'est pas celui d'un autel (remplacé par un réarmement) ne rejoint pas le monde. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof DungeonBossEntity boss && event.getLevel() instanceof ServerLevel level
                && DungeonData.get(level.getServer()).altarOf(boss.getUUID()) == null) {
            event.setCanceled(true);
        }
    }

    /**
     * Mort d'un gardien suivi : l'autel passe en recharge (epoch ms, {@code altarCooldownHours} lue à l'exécution), perd
     * son gardien, et la nation de l'activateur reçoit une clé militaire (à défaut, le tueur). Un gardien non suivi ne
     * donne aucune récompense.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof DungeonBossEntity boss) || !(boss.level() instanceof ServerLevel level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        DungeonData data = DungeonData.get(server);
        BlockPos pos = data.altarOf(boss.getUUID());
        DungeonData.AltarState state = pos == null ? null : data.get(pos);
        if (state == null) {
            return;
        }
        data.put(pos, new DungeonData.AltarState(null, state.nation(), TimeUtil.now() + AltarBlock.cooldownMs()));

        ServerPlayer killer = event.getSource().getEntity() instanceof ServerPlayer player ? player : null;
        Nation nation = state.nation() == null ? null : NationData.get(server).byId(state.nation());
        if (nation != null) {
            CrateKeys.giveToNation(server, nation, CrateKind.MILITAIRE, 1, killer);
            notifyMembers(server, nation);
        } else if (killer != null) {
            CrateKeys.give(killer, CrateKind.MILITAIRE, 1);
        }
    }

    /** Message à la nation, aux seuls membres en ligne (aucune file de messages pour les membres hors ligne). */
    private static void notifyMembers(MinecraftServer server, Nation nation) {
        for (UUID member : nation.members().keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                online.sendSystemMessage(Msg.ok("dungeon.boss_slain"));
            }
        }
    }
}
