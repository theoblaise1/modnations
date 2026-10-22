package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Construit et envoie la vue « Économie » ({@link EconomyView}).
 * <ul>
 *   <li>Mutation (contrat, entreprise, permission) : {@link #markDirty} ajoute la nation à un ensemble ;</li>
 *   <li>vidage : toutes les {@link #FLUSH_INTERVAL_TICKS} ticks, {@link #tick} ne parcourt QUE cet ensemble et envoie
 *       la vue aux membres connectés ;</li>
 *   <li>demande d'un client ({@link #request}) : au plus une par {@link #REQUEST_COOLDOWN_TICKS} ticks et par
 *       joueur ; une demande trop rapprochée est servie au vidage suivant, jamais perdue.</li>
 * </ul>
 * États transitoires en mémoire, vidés à la déconnexion ({@link #forget}) et à l'arrêt ({@link #clear}).
 */
public final class EconomyNetwork {
    public static final int FLUSH_INTERVAL_TICKS = 20;
    public static final int REQUEST_COOLDOWN_TICKS = 5;

    private static final Set<UUID> DIRTY_NATIONS = new LinkedHashSet<>();
    private static final Set<UUID> PENDING_PLAYERS = new LinkedHashSet<>();
    private static final Map<UUID, Integer> LAST_REQUEST_TICK = new HashMap<>();

    private EconomyNetwork() {
    }

    /** Vue personnalisée du joueur ; {@link EconomyView#EMPTY} sans nation. */
    public static EconomyView build(ServerPlayer player) {
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        if (nation == null) {
            return EconomyView.EMPTY;
        }
        return new EconomyView(nation.hasPermission(player.getUUID(), NationPermission.ECONOMY),
                ContractManager.view(player, nation), EnterpriseManager.view(player, nation));
    }

    /** Envoi immédiat (sans limite de débit) : réservé au serveur, après une action du joueur. */
    public static void send(ServerPlayer player) {
        if (NationSync.canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new EconomyViewS2C(build(player)));
        }
    }

    /** Demande d'un client : servie tout de suite, ou au prochain vidage si la précédente est trop récente. */
    public static void request(ServerPlayer player) {
        if (!NationSync.canReceive(player)) {
            return;
        }
        int now = player.server.getTickCount();
        Integer last = LAST_REQUEST_TICK.get(player.getUUID());
        if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
            PENDING_PLAYERS.add(player.getUUID());
            return;
        }
        LAST_REQUEST_TICK.put(player.getUUID(), now);
        send(player);
    }

    /** La vue de cette nation a changé : ses membres connectés la recevront au prochain vidage. */
    public static void markDirty(Nation nation) {
        DIRTY_NATIONS.add(nation.id());
    }

    /** Vrai si un envoi attend pour cette nation (observé par les GameTests). */
    public static boolean isDirty(Nation nation) {
        return DIRTY_NATIONS.contains(nation.id());
    }

    /** Appelé à chaque tick serveur ; ne fait rien hors des ticks de vidage ou quand rien n'attend. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % FLUSH_INTERVAL_TICKS != 0 || (DIRTY_NATIONS.isEmpty() && PENDING_PLAYERS.isEmpty())) {
            return;
        }
        List<UUID> nations = List.copyOf(DIRTY_NATIONS);
        List<UUID> players = List.copyOf(PENDING_PLAYERS);
        DIRTY_NATIONS.clear();
        PENDING_PLAYERS.clear();
        NationData data = NationData.get(server);
        Set<UUID> served = new HashSet<>();
        for (UUID id : nations) {
            Nation nation = data.byId(id);
            if (nation == null) {
                continue;
            }
            for (UUID member : nation.members().keySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(member);
                if (p != null && served.add(member)) {
                    send(p);
                }
            }
        }
        for (UUID id : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && served.add(id)) {
                LAST_REQUEST_TICK.put(id, server.getTickCount());
                send(p);
            }
        }
    }

    /** Oublie les demandes en attente d'un joueur qui se déconnecte. */
    public static void forget(UUID player) {
        PENDING_PLAYERS.remove(player);
        LAST_REQUEST_TICK.remove(player);
    }

    /** Arrêt du serveur : rien ne doit survivre d'une partie à l'autre (solo). */
    public static void clear() {
        DIRTY_NATIONS.clear();
        PENDING_PLAYERS.clear();
        LAST_REQUEST_TICK.clear();
    }
}
