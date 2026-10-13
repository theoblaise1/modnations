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
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Construit et envoie les {@link NationSnapshot} personnalisés aux joueurs. */
public final class NationSync {
    /** Rayon de la carte de l'onglet Territoire (en chunks) : grille de 15×15. */
    public static final int MAP_RADIUS = 7;

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

    /** Faux joueurs (GameTests, mods tiers) : pas de canal négocié, on n'envoie rien. */
    public static boolean canReceive(ServerPlayer player) {
        return !(player instanceof FakePlayer) && player.connection != null
                && player.connection.hasChannel(NationSnapshotS2C.TYPE);
    }

    /** Pousse l'état à jour à tous les membres connectés (le client l'ignore si l'écran est fermé). */
    public static void refreshNation(MinecraftServer server, Nation nation) {
        for (UUID id : nation.members().keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                refreshPlayer(p, false);
            }
        }
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
                    server.getPlayerList().getPlayer(e.getKey()) != null));
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
            if (!stack.isEmpty() && stack.getComponentsPatch().isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        double[] bonuses = new double[ResearchStat.values().length];
        for (ResearchStat stat : ResearchStat.values()) {
            bonuses[stat.ordinal()] = research.bonus(stat);
        }
        return new ResearchView(new ArrayList<>(research.completed()),
                active == null ? Optional.empty() : Optional.of(active.id()), remaining,
                active == null ? 0L : active.totalMs(), research.points(), nation.effectivePopulation(), counts, bonuses);
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
