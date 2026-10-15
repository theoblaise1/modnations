package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.nation.BuildingType;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.nation.TreasurySessions;
import io.github.theoblaise1.sovereignfrontier.research.NationResearch;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Construit et envoie les {@link NationSnapshot} personnalisés aux joueurs.
 * <ul>
 *   <li>Action explicite d'un joueur ({@link #refreshNation}) : envoi immédiat aux membres.</li>
 *   <li>Changement de fond (coffre, revenu, connexions, porte-monnaie) : {@link #scheduleRefresh},
 *       envoi groupé au plus une fois par {@link #FLUSH_INTERVAL_TICKS} ticks par {@link #tick}.</li>
 *   <li>Demande d'un client ({@link #request}) : au plus une par {@link #REQUEST_COOLDOWN_TICKS} ticks
 *       et par joueur ; une demande trop rapprochée est différée, jamais perdue.</li>
 * </ul>
 * Ces états sont transitoires (rien à envoyer après un redémarrage) : compteurs en mémoire,
 * vidés à la déconnexion ({@link #forget}) et à l'arrêt du serveur ({@link #clear}).
 */
public final class NationSync {
    /** Rayon de la carte de l'onglet Territoire (en chunks) : grille de 15×15. */
    public static final int MAP_RADIUS = 7;
    /** Intervalle minimal entre deux envois groupés des rafraîchissements planifiés (1 s). */
    public static final int FLUSH_INTERVAL_TICKS = 20;
    /** Intervalle minimal entre deux instantanés demandés par un même client (anti-abus). */
    public static final int REQUEST_COOLDOWN_TICKS = 5;
    /** Actions de nation (paquets qui modifient la nation) acceptées par joueur et par fenêtre. */
    public static final int MUTATIONS_PER_WINDOW = 5;
    /** Durée de la fenêtre du plafond d'actions (1 s). */
    public static final int MUTATION_WINDOW_TICKS = 20;

    /** Nations à rafraîchir au prochain envoi groupé. */
    private static final Set<UUID> PENDING_NATIONS = new LinkedHashSet<>();
    /** Joueurs (sans passer par leur nation) à rafraîchir au prochain envoi groupé. */
    private static final Set<UUID> PENDING_PLAYERS = new LinkedHashSet<>();
    /** Tick serveur du dernier instantané servi à la demande de chaque client. */
    private static final Map<UUID, Integer> LAST_REQUEST_TICK = new HashMap<>();
    /** Demande reçue pendant le délai, servie à son expiration (une seule par joueur). */
    private static final Map<UUID, Request> DEFERRED = new HashMap<>();
    /** Compteur d'actions de chaque joueur dans sa fenêtre courante. */
    private static final Map<UUID, MutationWindow> MUTATIONS = new HashMap<>();
    /** Ticks écoulés depuis le dernier envoi groupé (plafonné à l'intervalle). */
    private static int ticksSinceFlush = FLUSH_INTERVAL_TICKS;

    private record Request(boolean open, NationTab tab) {
    }

    /** Début de la fenêtre (tick serveur) et nombre d'actions déjà acceptées dans cette fenêtre. */
    private static final class MutationWindow {
        int start;
        int used;

        MutationWindow(int start) {
            this.start = start;
        }
    }

    private NationSync() {
    }

    public static void openScreen(ServerPlayer player, NationTab tab) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new NationSnapshotS2C(build(player), true, tab.ordinal()));
        }
    }

    public static void refreshPlayer(ServerPlayer player, boolean open) {
        if (canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new NationSnapshotS2C(build(player), open, NationTab.OVERVIEW.ordinal()));
        }
    }

    /**
     * Faux joueurs (GameTests, mods tiers) : pas de canal négocié, on n'envoie rien. Un joueur en
     * cours de déconnexion (encore listé pendant {@code PlayerLoggedOutEvent}) est ignoré aussi.
     */
    public static boolean canReceive(ServerPlayer player) {
        return !(player instanceof FakePlayer) && !player.hasDisconnected() && player.connection != null
                && player.connection.hasChannel(NationSnapshotS2C.TYPE);
    }

    /**
     * En ligne au sens de l'écran national. {@code ServerPlayer#disconnect} précède
     * {@code PlayerLoggedOutEvent} : un joueur qui part n'est plus compté.
     */
    public static boolean isOnline(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        return p != null && !p.hasDisconnected();
    }

    /** Pousse l'état à jour à tous les membres connectés (le client l'ignore si l'écran est fermé). */
    public static void refreshNation(MinecraftServer server, Nation nation) {
        // L'envoi immédiat couvre un éventuel rafraîchissement planifié pour cette nation.
        PENDING_NATIONS.remove(nation.id());
        for (UUID id : nation.members().keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                refreshPlayer(p, false);
            }
        }
    }

    // ------------------------------------------------------------------ envois groupés et limites

    /** Planifie un rafraîchissement des membres de la nation (envoi groupé, au plus 1 par seconde). */
    public static void scheduleRefresh(Nation nation) {
        PENDING_NATIONS.add(nation.id());
    }

    /** Vrai si un rafraîchissement groupé attend pour cette nation (observé par les GameTests). */
    public static boolean isRefreshScheduled(Nation nation) {
        return PENDING_NATIONS.contains(nation.id());
    }

    /** Planifie un rafraîchissement d'un seul joueur, ex. son porte-monnaie (envoi groupé). */
    public static void scheduleRefresh(ServerPlayer player) {
        if (canReceive(player)) {
            PENDING_PLAYERS.add(player.getUUID());
        }
    }

    /**
     * Un changement de territoire recolore la carte de tous les joueurs proches, y compris ceux d'autres nations.
     * Parcourt les joueurs en ligne seulement (jamais les nations ni les chunks) ; l'envoi reste groupé.
     *
     * @param chunk clé de chunk ({@link ChunkPos#toLong()}) de la revendication ou de l'abandon
     */
    public static void scheduleTerritoryRefresh(MinecraftServer server, ResourceKey<Level> dimension, long chunk) {
        int x = ChunkPos.getX(chunk);
        int z = ChunkPos.getZ(chunk);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.level().dimension().equals(dimension)) {
                continue;
            }
            ChunkPos here = p.chunkPosition();
            if (Math.abs((long) here.x - x) <= MAP_RADIUS && Math.abs((long) here.z - z) <= MAP_RADIUS) {
                scheduleRefresh(p);
            }
        }
    }

    /**
     * Instantané demandé par un client (paquet, resynchronisation après un échec) : servi tout de
     * suite, ou différé si le précédent date de moins de {@link #REQUEST_COOLDOWN_TICKS} ticks.
     * Une ouverture d'écran en attente n'est jamais remplacée par un simple rafraîchissement.
     */
    public static void request(ServerPlayer player, boolean open, NationTab tab) {
        if (!canReceive(player)) {
            return;
        }
        int now = player.server.getTickCount();
        Integer last = LAST_REQUEST_TICK.get(player.getUUID());
        if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
            Request pending = DEFERRED.get(player.getUUID());
            if (pending == null || open || !pending.open()) {
                DEFERRED.put(player.getUUID(), new Request(open, tab));
            }
            return;
        }
        DEFERRED.remove(player.getUUID());
        serve(player, new Request(open, tab), now);
    }

    private static void serve(ServerPlayer player, Request request, int now) {
        LAST_REQUEST_TICK.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, new NationSnapshotS2C(build(player), request.open(), request.tab().ordinal()));
    }

    /**
     * Appelé à chaque tick serveur, après les boucles de jeu : sert les demandes différées échues,
     * puis, au plus une fois par seconde, les rafraîchissements planifiés. O(1) quand rien n'attend.
     */
    public static void tick(MinecraftServer server) {
        if (!DEFERRED.isEmpty()) {
            int now = server.getTickCount();
            Iterator<Map.Entry<UUID, Request>> it = DEFERRED.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Request> e = it.next();
                Integer last = LAST_REQUEST_TICK.get(e.getKey());
                if (last != null && now - last < REQUEST_COOLDOWN_TICKS) {
                    continue;
                }
                it.remove();
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null && canReceive(p)) {
                    serve(p, e.getValue(), now);
                }
            }
        }
        if (ticksSinceFlush < FLUSH_INTERVAL_TICKS) {
            ticksSinceFlush++;
        }
        if (ticksSinceFlush < FLUSH_INTERVAL_TICKS || (PENDING_NATIONS.isEmpty() && PENDING_PLAYERS.isEmpty())) {
            return;
        }
        ticksSinceFlush = 0;
        List<UUID> nations = List.copyOf(PENDING_NATIONS);
        List<UUID> players = List.copyOf(PENDING_PLAYERS);
        PENDING_NATIONS.clear();
        PENDING_PLAYERS.clear();
        NationData data = NationData.get(server);
        for (UUID id : nations) {
            Nation nation = data.byId(id);
            if (nation != null) {
                refreshNation(server, nation);
            }
        }
        for (UUID id : players) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Nation nation = p == null ? null : data.nationOf(id);
            // Déjà servi si sa nation vient d'être rafraîchie.
            if (p != null && (nation == null || !nations.contains(nation.id()))) {
                refreshPlayer(p, false);
            }
        }
    }

    /**
     * Vrai si le joueur peut encore lancer une action de nation dans la fenêtre courante. Au-delà, le paquet
     * est ignoré sans réponse : une rafale ne produit ni message ni instantané, et n'épuise pas la trésorerie
     * plus vite que le plafond ne le permet.
     */
    public static boolean allowMutation(ServerPlayer player) {
        int now = player.server.getTickCount();
        MutationWindow window = MUTATIONS.computeIfAbsent(player.getUUID(), id -> new MutationWindow(now));
        if (now - window.start >= MUTATION_WINDOW_TICKS) {
            window.start = now;
            window.used = 0;
        }
        if (window.used >= MUTATIONS_PER_WINDOW) {
            return false;
        }
        window.used++;
        return true;
    }

    /** Oublie les limites et demandes en attente d'un joueur qui se déconnecte. */
    public static void forget(UUID player) {
        LAST_REQUEST_TICK.remove(player);
        DEFERRED.remove(player);
        PENDING_PLAYERS.remove(player);
        MUTATIONS.remove(player);
    }

    /** Arrêt du serveur : rien ne doit survivre d'une partie à l'autre (solo). */
    public static void clear() {
        PENDING_NATIONS.clear();
        PENDING_PLAYERS.clear();
        LAST_REQUEST_TICK.clear();
        DEFERRED.clear();
        MUTATIONS.clear();
        ticksSinceFlush = FLUSH_INTERVAL_TICKS;
    }

    public static NationSnapshot build(ServerPlayer player) {
        MinecraftServer server = player.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(player.getUUID());
        long wallet = EconomyData.get(server).balance(player.getUUID());
        NationSnapshot.MapView map = buildMap(player, data, nation);

        if (nation == null) {
            long now = TimeUtil.now();
            List<String> invitations = new ArrayList<>();
            for (Nation n : data.nations()) {
                if (n.hasValidInvite(player.getUUID(), now)) {
                    invitations.add(n.name());
                }
            }
            return new NationSnapshot(false, wallet, invitations, "", "", 0L, -1, 0, 0L, false, 0,
                    0L, 0, 0, 0, 0L, SFConfig.INCOME_INTERVAL_TICKS.get(), List.of(),
                    new int[NationRole.values().length], List.of(), map, ResearchView.EMPTY);
        }

        NationRole role = nation.roleOf(player.getUUID());
        List<NationSnapshot.MemberInfo> members = new ArrayList<>();
        for (Map.Entry<UUID, NationRole> e : nation.members().entrySet()) {
            members.add(new NationSnapshot.MemberInfo(nation.memberName(e.getKey()), e.getValue().ordinal(),
                    isOnline(server, e.getKey())));
        }
        members.sort((a, b) -> a.role() != b.role() ? Integer.compare(a.role(), b.role()) : a.name().compareToIgnoreCase(b.name()));
        int[] rolePerms = new int[NationRole.values().length];
        for (NationRole r : NationRole.values()) {
            rolePerms[r.ordinal()] = nation.permissionMask(r);
        }
        return new NationSnapshot(true, wallet, List.of(), nation.name(), nation.memberName(nation.leader()),
                nation.createdEpochMs(), role == null ? -1 : role.ordinal(),
                role == null ? 0 : nation.permissionMask(role), nation.treasury(), TreasurySessions.hasAccess(player),
                nation.claimCount(), NationManager.claimCost(nation),
                nation.buildingCount(BuildingType.HOUSE), nation.buildingCount(BuildingType.FARM),
                nation.buildingCount(BuildingType.WORKSHOP), NationManager.incomePerCycle(nation),
                SFConfig.INCOME_INTERVAL_TICKS.get(), members, rolePerms, nation.logEntries(), map, buildResearch(nation));
    }

    private static ResearchView buildResearch(Nation nation) {
        NationResearch research = nation.research();
        NationResearch.Active active = research.active();
        long remaining = active == null ? 0L : Math.max(0L, active.endEpochMs() - TimeUtil.now());
        Map<ResourceLocation, Integer> counts = new HashMap<>();
        SimpleContainer chest = nation.chest();
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (ResearchManager.isPlain(stack)) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        double[] bonuses = new double[ResearchStat.values().length];
        for (ResearchStat stat : ResearchStat.values()) {
            bonuses[stat.ordinal()] = research.bonus(stat);
        }
        return new ResearchView(new ArrayList<>(research.completed()),
                active == null ? Optional.empty() : Optional.of(active.id()), remaining,
                active == null ? 0L : active.totalMs(), active == null ? 0L : active.startEpochMs(), research.points(), nation.effectivePopulation(), counts, bonuses);
    }

    private static NationSnapshot.MapView buildMap(ServerPlayer player, NationData data, Nation own) {
        ChunkPos center = player.chunkPosition();
        int size = MAP_RADIUS * 2 + 1;
        byte[] cells = new byte[size * size];
        List<Nation> others = new ArrayList<>();
        for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
                Nation owner = data.ownerAt(player.level().dimension(), ChunkPos.asLong(center.x + dx, center.z + dz));
                byte value;
                if (owner == null) {
                    value = 0;
                } else if (owner == own) {
                    value = 1;
                } else {
                    int idx = others.indexOf(owner);
                    if (idx < 0) {
                        others.add(owner);
                        idx = others.size() - 1;
                    }
                    value = (byte) Math.min(127, idx + 2);
                }
                cells[(dz + MAP_RADIUS) * size + (dx + MAP_RADIUS)] = value;
            }
        }
        List<String> names = new ArrayList<>(others.size());
        int[] colors = new int[others.size()];
        for (int i = 0; i < others.size(); i++) {
            names.add(others.get(i).name());
            colors[i] = others.get(i).color();
        }
        return new NationSnapshot.MapView(center.x, center.z, MAP_RADIUS, cells, names, colors,
                own == null ? 0x55FF55 : own.color());
    }
}
