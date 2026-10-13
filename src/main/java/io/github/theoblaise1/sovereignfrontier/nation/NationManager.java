package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Money;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
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

    public static long claimCost(int alreadyClaimed) {
        double cost = SFConfig.CLAIM_BASE_COST.get() * Math.pow(SFConfig.CLAIM_COST_GROWTH.get(), alreadyClaimed);
        return cost >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(cost);
    }

    public static long incomePerCycle(Nation nation) {
        return nation.population() * SFConfig.INCOME_PER_INHABITANT.get();
    }

    @Nullable
    public static UUID findMember(Nation nation, String name) {
        for (UUID id : nation.members().keySet()) {
            if (nation.memberName(id).equalsIgnoreCase(name)) {
                return id;
            }
        }
        return null;
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

    private static void changed(MinecraftServer server, Nation nation) {
        NationSync.refreshNation(server, nation);
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
        long cost = SFConfig.NATION_CREATION_COST.get();
        EconomyData eco = EconomyData.get(server);
        if (cost > 0 && !eco.trySpend(player.getUUID(), cost)) {
            return ActionResult.fail(Msg.err("nation.error.creation_cost", Money.format(cost)));
        }
        Nation nation = data.create(name, player.getUUID(), player.getGameProfile().getName(), TimeUtil.now());
        nation.log("create", player.getGameProfile().getName(), name);
        server.getPlayerList().broadcastSystemMessage(Msg.info("nation.broadcast.created", player.getDisplayName(), name), false);
        changed(server, nation);
        return ActionResult.ok(Msg.ok("nation.created", name));
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
        if (nation.memberCount() >= SFConfig.MAX_MEMBERS.get()) {
            return ActionResult.fail(Msg.err("nation.error.full", SFConfig.MAX_MEMBERS.get()));
        }
        long expiry = TimeUtil.now() + SFConfig.INVITE_EXPIRY_MINUTES.get() * 60_000L;
        nation.invite(target.getUUID(), expiry);
        nation.log("invite", actor.getGameProfile().getName(), target.getGameProfile().getName());

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
        if (nation.memberCount() >= SFConfig.MAX_MEMBERS.get()) {
            return ActionResult.fail(Msg.err("nation.error.full", SFConfig.MAX_MEMBERS.get()));
        }
        data.addMember(nation, player.getUUID(), player.getGameProfile().getName(), NationRole.RECRUE);
        nation.log("join", player.getGameProfile().getName());
        broadcast(server, nation, tag(nation).append(Msg.ok("nation.member_joined", player.getDisplayName())));
        changed(server, nation);
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
        UUID target = findMember(nation, targetName);
        if (target == null) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
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
        changed(server, nation);
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
        changed(server, nation);
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
        UUID target = findMember(nation, targetName);
        if (target == null) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
        if (target.equals(actor.getUUID())) {
            return ActionResult.fail(Msg.err("nation.error.transfer_self"));
        }
        nation.setLeader(target);
        String name = nation.memberName(target);
        nation.log("transfer", actor.getGameProfile().getName(), name);
        broadcast(server, nation, tag(nation).append(Msg.warn("nation.leader_changed", name)));
        changed(server, nation);
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
        UUID target = findMember(nation, targetName);
        if (target == null) {
            return ActionResult.fail(Msg.err("nation.error.not_member", targetName));
        }
        if (target.equals(actor.getUUID())) {
            return ActionResult.fail(Msg.err("nation.error.role_self"));
        }
        nation.setRole(target, role);
        String name = nation.memberName(target);
        nation.log("role", actor.getGameProfile().getName(), name, "@role." + role.id());
        broadcast(server, nation, tag(nation).append(Msg.info("nation.role_changed", name, role.displayName())));
        changed(server, nation);
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
        nation.setPermission(role, permission, granted);
        nation.log(granted ? "perm_grant" : "perm_revoke", actor.getGameProfile().getName(), "@perm." + permission.id(), "@role." + role.id());
        changed(server, nation);
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
        if (SFConfig.CLAIM_REQUIRE_CONTIGUOUS.get() && nation.hasClaimsIn(dimension) && !touchesOwn(data, nation, dimension, chunk)) {
            return ActionResult.fail(Msg.err("claim.error.not_contiguous"));
        }
        long cost = claimCost(nation.claimCount());
        if (nation.treasury() < cost) {
            return ActionResult.fail(Msg.err("claim.error.funds", Money.format(cost), Money.format(nation.treasury())));
        }
        nation.addTreasury(-cost);
        data.claim(nation, dimension, key);
        nation.log("claim", actor.getGameProfile().getName(), chunk.x + ", " + chunk.z,
                dimension.location().toString(), Money.format(cost));
        changed(server, nation);
        return ActionResult.ok(Msg.ok("claim.success", chunk.x, chunk.z, Money.format(cost),
                Money.format(claimCost(nation.claimCount()))));
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
        int buildingsBefore = nation.population();
        data.unclaim(nation, dimension, key);
        int lostBuildings = buildingsBefore - nation.population();
        long refund = (long) Math.floor(claimCost(nation.claimCount()) * SFConfig.UNCLAIM_REFUND_RATIO.get());
        if (refund > 0) {
            nation.addTreasury(refund);
        }
        nation.log("unclaim", actor.getGameProfile().getName(), chunk.x + ", " + chunk.z, dimension.location().toString());
        changed(server, nation);
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
        changed(server, nation);
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
        changed(server, nation);
        return ActionResult.ok(Msg.ok("treasury.withdrawn", Money.format(amount), Money.format(nation.treasury())));
    }

    // ------------------------------------------------------------------ revenu périodique

    /** Appelé à chaque tick serveur : avance un compteur persistant, verse le revenu à chaque cycle. */
    public static void tickIncome(MinecraftServer server) {
        NationData data = NationData.get(server);
        if (!data.advanceIncomeCounter(SFConfig.INCOME_INTERVAL_TICKS.get())) {
            return;
        }
        long now = TimeUtil.now();
        for (Nation nation : data.nationsSnapshot()) {
            nation.purgeExpiredInvites(now);
            long income = incomePerCycle(nation);
            if (income > 0) {
                nation.addTreasury(income);
                broadcast(server, nation, tag(nation).append(Msg.info("treasury.income", Money.format(income), nation.population())));
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
        c.append("\n").append(Msg.info("nation.info.territory", Msg.hl(nation.claimCount()), Msg.money(claimCost(nation.claimCount()))));
        c.append("\n").append(Msg.info("nation.info.population", Msg.hl(nation.population()),
                Msg.hl(nation.buildingCount(BuildingType.HOUSE)), Msg.hl(nation.buildingCount(BuildingType.FARM)),
                Msg.hl(nation.buildingCount(BuildingType.WORKSHOP))));
        c.append("\n").append(Msg.info("nation.info.members", Msg.hl(nation.memberCount()), Msg.hl(SFConfig.MAX_MEMBERS.get())));
        for (Map.Entry<UUID, NationRole> e : nation.members().entrySet()) {
            boolean online = server.getPlayerList().getPlayer(e.getKey()) != null;
            c.append("\n  ").append(Component.literal(online ? "● " : "○ ").withStyle(online ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
                    .append(Component.literal(nation.memberName(e.getKey()) + " ").withStyle(ChatFormatting.WHITE))
                    .append(e.getValue().displayName().withStyle(ChatFormatting.GRAY));
        }
        return c;
    }
}
