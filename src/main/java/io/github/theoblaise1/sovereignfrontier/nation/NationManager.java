package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostRules;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.space.SpaceRules;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Logique métier des nations, partagée par les commandes et les paquets du GUI.
 * Chaque méthode vérifie ses préconditions et renvoie un {@link ActionResult} au message explicite.
 */
public final class NationManager {
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{3,24}");

    private NationManager() {
    }

    // ------------------------------------------------------------------ utilitaires

    /** Coût brut du chunk suivant : {@code ceil(base × croissance^n)}. */
    public static long claimCost(int alreadyClaimed) {
        return ceilCost(SFConfig.CLAIM_BASE_COST.get() * Math.pow(SFConfig.CLAIM_COST_GROWTH.get(), alreadyClaimed));
    }

    /** Coût du prochain chunk pour cette nation, réduction de recherche incluse (bornée à -50 %). */
    public static long claimCost(Nation nation) {
        double modifier = Math.max(-0.5D, nation.research().bonus(ResearchStat.CLAIM_COST_MULTIPLIER));
        return ceilCost(SFConfig.CLAIM_BASE_COST.get() * Math.pow(SFConfig.CLAIM_COST_GROWTH.get(), nation.claimCount())
                * (1.0D + modifier));
    }

    private static long ceilCost(double cost) {
        // Tolérance d'arrondi : 100 × 1,08 vaut 108,00000000000001 en double.
        return cost >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(cost - 1e-9);
    }

    /**
     * Habitants libres : habitants effectifs moins ceux affectés aux entreprises (J4). Seuls les libres touchent le
     * revenu passif ; les affectés produisent à la place.
     */
    public static int freeInhabitants(Nation nation) {
        return Math.max(0, nation.effectivePopulation() - nation.enterprises().assignedTotal());
    }

    /** Revenu versé à chaque cycle : habitants libres × revenu par habitant × (1 + bonus de revenu). */
    public static long incomePerCycle(Nation nation) {
        double multiplier = 1.0D + Math.max(-1.0D, nation.research().bonus(ResearchStat.INCOME_MULTIPLIER));
        return (long) Math.floor(freeInhabitants(nation) * SFConfig.INCOME_PER_INHABITANT.get() * multiplier + 1e-9);
    }

    /** Ticks restants avant le prochain cycle de revenu (et de production des entreprises). */
    public static int ticksToNextIncome(MinecraftServer server) {
        long left = SFConfig.INCOME_INTERVAL_TICKS.get() - NationData.get(server).incomeTickCounter();
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, left));
    }

    /** Nombre maximal de membres : configuration + bonus de recherche. */
    public static int maxMembers(Nation nation) {
        return SFConfig.MAX_MEMBERS.get() + (int) Math.floor(nation.research().bonus(ResearchStat.MAX_MEMBERS) + 1e-9);
    }

    /** Ajuste la trésorerie (positif = crédit) ; utilisé par les systèmes hors du paquet nation. */
    public static void adjustTreasury(Nation nation, long delta) {
        nation.addTreasury(delta);
    }

    /**
     * Membres portant ce nom : la correspondance exacte prime, sinon la casse est ignorée. Plusieurs
     * résultats = nom ambigu, à refuser (hors ligne, deux membres peuvent ne différer que par la casse).
     */
    public static List<UUID> findMembers(Nation nation, String name) {
        List<UUID> exact = new ArrayList<>();
        List<UUID> loose = new ArrayList<>();
        for (UUID id : nation.members().keySet()) {
            String member = nation.memberName(id);
            if (member.equals(name)) {
                exact.add(id);
            } else if (member.equalsIgnoreCase(name)) {
                loose.add(id);
            }
        }
        return exact.isEmpty() ? loose : exact;
    }

    /** Envoie un message à tous les membres connectés d'une nation. */
    public static void broadcast(MinecraftServer server, Nation nation, Component message) {
        for (UUID id : nation.members().keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.sendSystemMessage(message);
            }
        }
    }

    private static MutableComponent tag(Nation nation) {
        return Component.literal("[" + nation.name() + "] ").withStyle(ChatFormatting.AQUA);
    }

    /**
     * Enregistre un Trésor ou un coffre national posé sur le territoire de sa nation. Appelé par les blocs à la pose.
     */
    public static void coreAdded(ServerLevel level, BlockPos pos) {
        Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), pos);
        if (owner != null) {
            owner.addCore(GlobalPos.of(level.dimension(), pos));
        }
    }

    /**
     * Après une action qui modifie la nation : envoi groupé à ses membres (au plus une fois par seconde),
     * pour qu'une rafale d'actions ne déclenche pas un instantané complet par action et par membre.
     */
    private static void changed(Nation nation) {
        NationSync.scheduleRefresh(nation);
    }

    // ------------------------------------------------------------------ cycle de vie

    public static ActionResult create(ServerPlayer player, String name) {
        MinecraftServer server = player.server;
        NationData data = NationData.get(server);
        if (data.nationOf(player.getUUID()) != null) {
            return ActionResult.fail(Msg.err("nation.error.already_member"));
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            return ActionResult.fail(Msg.err("nation.error.invalid_name"));
        }
        if (data.byName(name) != null) {
            return ActionResult.fail(Msg.err("nation.error.name_taken", name));
        }
        // Le chunk courant devient la capitale, offerte : sans elle, impossible de poser un
        // Trésor national sur son territoire pour alimenter la première revendication.
        ResourceKey<Level> dimension = player.level().dimension();
        ChunkPos capital = player.chunkPosition();
        Nation capitalOwner = data.ownerAt(dimension, capital.toLong());
        if (capitalOwner != null) {
            return ActionResult.fail(Msg.err("nation.error.capital_taken", capitalOwner.name()));
        }
        Component outpost = OutpostRules.claimBlockReason(server, dimension, capital);
        if (outpost != null) {
            return ActionResult.fail(outpost);
        }
        Component space = SpaceRules.foundBlockReason(dimension);
        if (space != null) {
            return ActionResult.fail(space);
        }
        long cost = SFConfig.NATION_CREATION_COST.get();
        EconomyData eco = EconomyData.get(server);
        if (cost > 0 && !eco.trySpend(player.getUUID(), cost)) {
            return ActionResult.fail(Msg.err("nation.error.creation_cost", Money.format(cost)));
        }
        Nation nation = data.create(name, player.getUUID(), player.getGameProfile().getName(), TimeUtil.now());
        data.claim(nation, dimension, capital.toLong());
        NationSync.scheduleTerritoryRefresh(server, dimension, capital.toLong());
        nation.log("create", player.getGameProfile().getName(), name);
        nation.log("capital", capital.x + ", " + capital.z, dimension.location().toString());
        server.getPlayerList().broadcastSystemMessage(Msg.info("nation.broadcast.created", player.getDisplayName(), name), false);
        changed(nation);
        return ActionResult.ok(Msg.ok("nation.created", name, capital.x, capital.z));
    }

    public static ActionResult invite(ServerPlayer actor, ServerPlayer target) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(actor.getUUID(), NationPermission.INVITE)) {
            return noPermission(NationPermission.INVITE);
        }
        if (actor == target) {
            return ActionResult.fail(Msg.err("nation.error.invite_self"));
        }
        if (data.nationOf(target.getUUID()) != null) {
            return ActionResult.fail(Msg.err("nation.error.target_has_nation", target.getDisplayName()));
        }
        if (nation.memberCount() >= maxMembers(nation)) {
            return ActionResult.fail(Msg.err("nation.error.full", maxMembers(nation)));
        }
        long expiry = TimeUtil.now() + SFConfig.INVITE_EXPIRY_MINUTES.get() * 60_000L;
        nation.invite(target.getUUID(), expiry);
        nation.log("invite", actor.getGameProfile().getName(), target.getGameProfile().getName());
        // Le journal des officiers doit montrer l'invitation sans attendre leur prochain rafraîchissement.
        NationSync.scheduleRefresh(nation);

        String command = "/nation join " + nation.name();
        MutableComponent button = Msg.tr("nation.invite.accept_button").withStyle(style -> style
                .withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
        target.sendSystemMessage(Msg.warn("nation.invite.received", actor.getDisplayName(), nation.name(),
                SFConfig.INVITE_EXPIRY_MINUTES.get()).append(" ").append(button));
        NationSync.refreshPlayer(target, false);
        return ActionResult.ok(Msg.ok("nation.invite.sent", target.getDisplayName()));
    }

    public static ActionResult join(ServerPlayer player, String nationName) {
        MinecraftServer server = player.server;
        NationData data = NationData.get(server);
        if (data.nationOf(player.getUUID()) != null) {
            return ActionResult.fail(Msg.err("nation.error.already_member"));
        }
        Nation nation = data.byName(nationName);
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.unknown", nationName));
        }
        if (!nation.hasValidInvite(player.getUUID(), TimeUtil.now())) {
            return ActionResult.fail(Msg.err("nation.error.no_invite", nation.name()));
        }
        if (nation.memberCount() >= maxMembers(nation)) {
            return ActionResult.fail(Msg.err("nation.error.full", maxMembers(nation)));
        }
        data.addMember(nation, player.getUUID(), player.getGameProfile().getName(), NationRole.RECRUE);
        nation.log("join", player.getGameProfile().getName());
        broadcast(server, nation, tag(nation).append(Msg.ok("nation.member_joined", player.getDisplayName())));
        changed(nation);
        return ActionResult.ok(Msg.ok("nation.joined", nation.name()));
    }

    public static ActionResult kick(ServerPlayer actor, String targetName) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(actor.getUUID(), NationPermission.KICK)) {
            return noPermission(NationPermission.KICK);
        }
        List<UUID> matches = findMembers(nation, targetName);
        if (matches.isEmpty()) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
        if (matches.size() > 1) {
            return ActionResult.fail(Msg.err("nation.error.ambiguous_member", targetName));
        }
        UUID target = matches.get(0);
        if (target.equals(actor.getUUID())) {
            return ActionResult.fail(Msg.err("nation.error.kick_self"));
        }
        NationRole actorRole = nation.roleOf(actor.getUUID());
        NationRole targetRole = nation.roleOf(target);
        if (actorRole == null || targetRole == null || !actorRole.outranks(targetRole)) {
            return ActionResult.fail(Msg.err("nation.error.rank_too_low", targetName));
        }
        String name = nation.memberName(target);
        data.removeMember(nation, target);
        nation.log("kick", actor.getGameProfile().getName(), name);
        broadcast(server, nation, tag(nation).append(Msg.warn("nation.member_kicked", name, actor.getDisplayName())));
        ServerPlayer kicked = server.getPlayerList().getPlayer(target);
        if (kicked != null) {
            kicked.sendSystemMessage(Msg.err("nation.you_were_kicked", nation.name()));
            NationSync.refreshPlayer(kicked, false);
        }
        changed(nation);
        return ActionResult.ok(Msg.ok("nation.kicked", name));
    }

    public static ActionResult leave(ServerPlayer player) {
        MinecraftServer server = player.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(player.getUUID()) == NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.leader_cannot_leave"));
        }
        data.removeMember(nation, player.getUUID());
        nation.log("leave", player.getGameProfile().getName());
        broadcast(server, nation, tag(nation).append(Msg.warn("nation.member_left", player.getDisplayName())));
        changed(nation);
        NationSync.refreshPlayer(player, false);
        return ActionResult.ok(Msg.ok("nation.left", nation.name()));
    }

    public static ActionResult transfer(ServerPlayer actor, String targetName) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(actor.getUUID()) != NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.leader_only"));
        }
        List<UUID> matches = findMembers(nation, targetName);
        if (matches.isEmpty()) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
        if (matches.size() > 1) {
            return ActionResult.fail(Msg.err("nation.error.ambiguous_member", targetName));
        }
        UUID target = matches.get(0);
        if (target.equals(actor.getUUID())) {
            return ActionResult.fail(Msg.err("nation.error.transfer_self"));
        }
        nation.setLeader(target);
        String name = nation.memberName(target);
        nation.log("transfer", actor.getGameProfile().getName(), name);
        broadcast(server, nation, tag(nation).append(Msg.warn("nation.leader_changed", name)));
        changed(nation);
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.ok("nation.transferred", name));
    }

    public static ActionResult disband(ServerPlayer actor) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(actor.getUUID()) != NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.leader_only"));
        }
        if (!nation.chest().isEmpty()) {
            return ActionResult.fail(Msg.err("nation.error.chest_not_empty"));
        }
        long refund = nation.treasury();
        if (refund > 0) {
            EconomyData.get(server).add(actor.getUUID(), refund);
        }
        var formerMembers = java.util.List.copyOf(nation.members().keySet());
        // Le territoire disparaît : les cartes des joueurs proches doivent se recolorer.
        for (var claims : nation.claims().entrySet()) {
            for (long chunk : claims.getValue()) {
                NationSync.scheduleTerritoryRefresh(server, claims.getKey(), chunk);
            }
        }
        data.remove(nation);
        server.getPlayerList().broadcastSystemMessage(Msg.warn("nation.broadcast.disbanded", nation.name()), false);
        for (UUID id : formerMembers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                NationSync.refreshPlayer(p, false);
            }
        }
        return ActionResult.ok(Msg.ok("nation.disbanded", nation.name(), Money.format(refund)));
    }

    // ------------------------------------------------------------------ rôles et permissions

    public static ActionResult setRole(ServerPlayer actor, String targetName, NationRole role) {
        MinecraftServer server = actor.server;
        Nation nation = NationData.get(server).nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(actor.getUUID()) != NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.leader_only"));
        }
        if (role == NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.use_transfer"));
        }
        List<UUID> matches = findMembers(nation, targetName);
        if (matches.isEmpty()) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
        if (matches.size() > 1) {
            return ActionResult.fail(Msg.err("nation.error.ambiguous_member", targetName));
        }
        UUID target = matches.get(0);
        if (target.equals(actor.getUUID())) {
            return ActionResult.fail(Msg.err("nation.error.role_self"));
        }
        String name = nation.memberName(target);
        if (!nation.setRole(target, role)) {
            // Rien ne change : ni entrée de journal ni annonce ; l'échec resynchronise l'écran.
            return ActionResult.fail(Msg.warn("nation.role_unchanged", name, role.displayName()));
        }
        nation.log("role", actor.getGameProfile().getName(), name, "@role." + role.id());
        broadcast(server, nation, tag(nation).append(Msg.info("nation.role_changed", name, role.displayName())));
        changed(nation);
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.ok("nation.role_set", name, role.displayName()));
    }

    public static ActionResult setPermission(ServerPlayer actor, NationRole role, NationPermission permission, boolean granted) {
        MinecraftServer server = actor.server;
        Nation nation = NationData.get(server).nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (nation.roleOf(actor.getUUID()) != NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.leader_only"));
        }
        if (role == NationRole.CHEF) {
            return ActionResult.fail(Msg.err("nation.error.chef_locked"));
        }
        if (!nation.setPermission(role, permission, granted)) {
            // Déjà dans cet état (double clic, commande répétée) : le journal ne garde que les vrais changements.
            return ActionResult.fail(Msg.warn(granted ? "nation.perm_already_granted" : "nation.perm_already_revoked",
                    permission.displayName(), role.displayName()));
        }
        nation.log(granted ? "perm_grant" : "perm_revoke", actor.getGameProfile().getName(), "@perm." + permission.id(), "@role." + role.id());
        changed(nation);
        EconomyNetwork.markDirty(nation);
        return ActionResult.ok(Msg.ok(granted ? "nation.perm_granted" : "nation.perm_revoked",
                permission.displayName(), role.displayName()));
    }

    // ------------------------------------------------------------------ territoire

    public static ActionResult claim(ServerPlayer actor, ResourceKey<Level> dimension, ChunkPos chunk) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(actor.getUUID(), NationPermission.CLAIM)) {
            return noPermission(NationPermission.CLAIM);
        }
        long key = chunk.toLong();
        Nation owner = data.ownerAt(dimension, key);
        if (owner == nation) {
            return ActionResult.fail(Msg.err("claim.error.already_own"));
        }
        if (owner != null) {
            return ActionResult.fail(Msg.err("claim.error.owned_by", owner.name()));
        }
        Component outpost = OutpostRules.claimBlockReason(server, dimension, chunk);
        if (outpost != null) {
            return ActionResult.fail(outpost);
        }
        Component space = SpaceRules.claimBlockReason(nation, dimension);
        if (space != null) {
            return ActionResult.fail(space);
        }
        if (SFConfig.CLAIM_REQUIRE_CONTIGUOUS.get() && nation.hasClaimsIn(dimension) && !touchesOwn(data, nation, dimension, chunk)) {
            return ActionResult.fail(Msg.err("claim.error.not_contiguous"));
        }
        long cost = claimCost(nation);
        if (nation.treasury() < cost) {
            return ActionResult.fail(Msg.err("claim.error.funds", Money.format(cost), Money.format(nation.treasury())));
        }
        nation.addTreasury(-cost);
        data.claim(nation, dimension, key);
        NationSync.scheduleTerritoryRefresh(server, dimension, key);
        nation.log("claim", actor.getGameProfile().getName(), chunk.x + ", " + chunk.z,
                dimension.location().toString(), Money.format(cost));
        changed(nation);
        return ActionResult.ok(Msg.ok("claim.success", chunk.x, chunk.z, Money.format(cost),
                Money.format(claimCost(nation))));
    }

    public static ActionResult unclaim(ServerPlayer actor, ResourceKey<Level> dimension, ChunkPos chunk) {
        MinecraftServer server = actor.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(actor.getUUID(), NationPermission.UNCLAIM)) {
            return noPermission(NationPermission.UNCLAIM);
        }
        long key = chunk.toLong();
        if (data.ownerAt(dimension, key) != nation) {
            return ActionResult.fail(Msg.err("claim.error.not_own"));
        }
        if (nation.claimCount() <= 1) {
            // Sans territoire, plus de Trésor national utilisable : la nation serait bloquée.
            return ActionResult.fail(Msg.err("claim.error.last_chunk"));
        }
        ServerLevel level = actor.server.getLevel(dimension);
        if (level != null && nation.holdsCoreIn(level, key)) {
            // Abandonner cette parcelle rendrait le Trésor ou le coffre inaccessible, et ouvrirait sa casse.
            return ActionResult.fail(Msg.err("claim.error.holds_core"));
        }
        int buildingsBefore = nation.population();
        data.unclaim(nation, dimension, key);
        NationSync.scheduleTerritoryRefresh(server, dimension, key);
        int lostBuildings = buildingsBefore - nation.population();
        long refund = (long) Math.floor(claimCost(nation) * SFConfig.UNCLAIM_REFUND_RATIO.get());
        if (refund > 0) {
            nation.addTreasury(refund);
        }
        nation.log("unclaim", actor.getGameProfile().getName(), chunk.x + ", " + chunk.z, dimension.location().toString());
        changed(nation);
        MutableComponent msg = Msg.ok("unclaim.success", chunk.x, chunk.z, Money.format(refund));
        if (lostBuildings > 0) {
            msg.append(" ").append(Msg.warn("unclaim.lost_buildings", lostBuildings));
        }
        return ActionResult.ok(msg);
    }

    private static boolean touchesOwn(NationData data, Nation nation, ResourceKey<Level> dim, ChunkPos c) {
        return data.ownerAt(dim, ChunkPos.asLong(c.x + 1, c.z)) == nation
                || data.ownerAt(dim, ChunkPos.asLong(c.x - 1, c.z)) == nation
                || data.ownerAt(dim, ChunkPos.asLong(c.x, c.z + 1)) == nation
                || data.ownerAt(dim, ChunkPos.asLong(c.x, c.z - 1)) == nation;
    }

    // ------------------------------------------------------------------ trésorerie

    public static ActionResult deposit(ServerPlayer actor, long amount) {
        MinecraftServer server = actor.server;
        Nation nation = NationData.get(server).nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (amount <= 0) {
            return ActionResult.fail(Msg.err("money.error.positive"));
        }
        if (!TreasurySessions.hasAccess(actor)) {
            return ActionResult.fail(Msg.err("treasury.error.no_access"));
        }
        if (!EconomyData.get(server).trySpend(actor.getUUID(), amount)) {
            return ActionResult.fail(Msg.err("money.error.insufficient", Money.format(EconomyData.get(server).balance(actor.getUUID()))));
        }
        nation.addTreasury(amount);
        nation.log("deposit", actor.getGameProfile().getName(), Money.format(amount));
        changed(nation);
        return ActionResult.ok(Msg.ok("treasury.deposited", Money.format(amount), Money.format(nation.treasury())));
    }

    public static ActionResult withdraw(ServerPlayer actor, long amount) {
        MinecraftServer server = actor.server;
        Nation nation = NationData.get(server).nationOf(actor.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(actor.getUUID(), NationPermission.WITHDRAW)) {
            return noPermission(NationPermission.WITHDRAW);
        }
        if (amount <= 0) {
            return ActionResult.fail(Msg.err("money.error.positive"));
        }
        if (!TreasurySessions.hasAccess(actor)) {
            return ActionResult.fail(Msg.err("treasury.error.no_access"));
        }
        if (nation.treasury() < amount) {
            return ActionResult.fail(Msg.err("treasury.error.insufficient", Money.format(nation.treasury())));
        }
        nation.addTreasury(-amount);
        EconomyData.get(server).add(actor.getUUID(), amount);
        nation.log("withdraw", actor.getGameProfile().getName(), Money.format(amount));
        changed(nation);
        return ActionResult.ok(Msg.ok("treasury.withdrawn", Money.format(amount), Money.format(nation.treasury())));
    }

    // ------------------------------------------------------------------ revenu périodique

    /**
     * Appelé à chaque tick serveur : avance un compteur persistant ; à chaque cycle, fait produire les entreprises
     * puis verse le revenu passif des habitants libres. Production et revenu sont évalués au même instant, dans
     * le même passage : un habitant ne peut pas être compté deux fois.
     */
    public static void tickIncome(MinecraftServer server) {
        NationData data = NationData.get(server);
        if (!data.advanceIncomeCounter(SFConfig.INCOME_INTERVAL_TICKS.get())) {
            return;
        }
        long now = TimeUtil.now();
        for (Nation nation : data.nationsSnapshot()) {
            nation.purgeExpiredInvites(now);
            EnterpriseManager.produceCycle(server, nation);
            long income = incomePerCycle(nation);
            if (income > 0) {
                nation.addTreasury(income);
                broadcast(server, nation, tag(nation).append(Msg.info("treasury.income", Money.format(income), freeInhabitants(nation))));
                // Trésorerie affichée (Aperçu, Trésor, bouton « Lancer » de la Science) : envoi groupé.
                NationSync.scheduleRefresh(nation);
            }
        }
    }

    private static ActionResult noPermission(NationPermission permission) {
        return ActionResult.fail(Msg.err("nation.error.no_permission", permission.displayName()));
    }

    /** Résumé lisible pour {@code /nation info}. */
    public static Component describe(MinecraftServer server, Nation nation) {
        MutableComponent c = Component.empty();
        c.append(Component.literal("=== " + nation.name() + " ===").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        c.append("\n").append(Msg.info("nation.info.leader", Msg.hl(nation.memberName(nation.leader()))));
        c.append("\n").append(Msg.info("nation.info.founded", Msg.hl(TimeUtil.date(nation.createdEpochMs()))));
        c.append("\n").append(Msg.info("nation.info.treasury", Msg.money(nation.treasury())));
        c.append("\n").append(Msg.info("nation.info.territory", Msg.hl(nation.claimCount()), Msg.money(claimCost(nation))));
        c.append("\n").append(Msg.info("nation.info.population", Msg.hl(nation.effectivePopulation()),
                Msg.hl(nation.buildingCount(BuildingType.HOUSE)), Msg.hl(nation.buildingCount(BuildingType.FARM)),
                Msg.hl(nation.buildingCount(BuildingType.WORKSHOP))));
        c.append("\n").append(Msg.info("nation.info.members", Msg.hl(nation.memberCount()), Msg.hl(maxMembers(nation))));
        for (Map.Entry<UUID, NationRole> e : nation.members().entrySet()) {
            boolean online = server.getPlayerList().getPlayer(e.getKey()) != null;
            c.append("\n  ").append(Component.literal(online ? "● " : "○ ").withStyle(online ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
                    .append(Component.literal(nation.memberName(e.getKey()) + " ").withStyle(ChatFormatting.WHITE))
                    .append(e.getValue().displayName().withStyle(ChatFormatting.GRAY));
        }
        return c;
    }
}
