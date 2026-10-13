package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.nation.LogEntry;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Vue de la nation envoyée au client pour l'écran national. Construite côté serveur par
 * {@code NationSync}, personnalisée pour le joueur destinataire (rôle, portefeuille, carte).
 *
 * @param hasNation          faux si le joueur n'appartient à aucune nation (seuls wallet/invitations/map sont pertinents)
 * @param myRole             ordinal de {@code NationRole}, -1 sans nation
 * @param myPermissions      masque de bits de {@code NationPermission} du joueur
 * @param treasuryAccess     vrai si le joueur se tient près d'un Trésor national de sa nation (dépôt/retrait possibles)
 * @param rolePermissions    5 masques de permissions, indexés par ordinal de {@code NationRole}
 * @param incomeIntervalTicks durée d'un cycle de revenu, en ticks
 * @param research           état de recherche de la nation ({@link ResearchView#EMPTY} sans nation)
 */
public record NationSnapshot(
        boolean hasNation,
        long wallet,
        List<String> invitations,
        String nationName,
        String leaderName,
        long createdEpochMs,
        int myRole,
        int myPermissions,
        long treasury,
        boolean treasuryAccess,
        int chunkCount,
        long nextClaimCost,
        int houses,
        int farms,
        int workshops,
        long incomePerCycle,
        int incomeIntervalTicks,
        List<MemberInfo> members,
        int[] rolePermissions,
        List<LogEntry> log,
        MapView map,
        ResearchView research) {

    /** Membre de la nation tel qu'affiché dans la liste. */
    public record MemberInfo(String name, int role, boolean online) {
    }

    /**
     * Carte des chunks autour du joueur : {@code cells[(dz + radius) * size + (dx + radius)]}
     * avec 0 = terres sauvages, 1 = sa propre nation, 2 + k = {@code nationNames.get(k)}.
     */
    public record MapView(int centerX, int centerZ, int radius, byte[] cells, List<String> nationNames,
                          int[] nationColors, int ownColor) {
        public int size() {
            return radius * 2 + 1;
        }

        public int cell(int dx, int dz) {
            return cells[(dz + radius) * size() + (dx + radius)];
        }
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(hasNation);
        buf.writeLong(wallet);
        buf.writeCollection(invitations, FriendlyByteBuf::writeUtf);
        buf.writeUtf(nationName);
        buf.writeUtf(leaderName);
        buf.writeLong(createdEpochMs);
        buf.writeVarInt(myRole);
        buf.writeVarInt(myPermissions);
        buf.writeLong(treasury);
        buf.writeBoolean(treasuryAccess);
        buf.writeVarInt(chunkCount);
        buf.writeLong(nextClaimCost);
        buf.writeVarInt(houses);
        buf.writeVarInt(farms);
        buf.writeVarInt(workshops);
        buf.writeLong(incomePerCycle);
        buf.writeVarInt(incomeIntervalTicks);
        buf.writeVarInt(members.size());
        for (MemberInfo m : members) {
            buf.writeUtf(m.name());
            buf.writeVarInt(m.role());
            buf.writeBoolean(m.online());
        }
        buf.writeVarIntArray(rolePermissions);
        buf.writeVarInt(log.size());
        for (LogEntry e : log) {
            e.write(buf);
        }
        buf.writeVarInt(map.centerX());
        buf.writeVarInt(map.centerZ());
        buf.writeVarInt(map.radius());
        buf.writeByteArray(map.cells());
        buf.writeCollection(map.nationNames(), FriendlyByteBuf::writeUtf);
        buf.writeVarIntArray(map.nationColors());
        buf.writeInt(map.ownColor());
        research.write(buf);
    }

    public static NationSnapshot read(FriendlyByteBuf buf) {
        boolean hasNation = buf.readBoolean();
        long wallet = buf.readLong();
        List<String> invitations = buf.readList(FriendlyByteBuf::readUtf);
        String nationName = buf.readUtf();
        String leaderName = buf.readUtf();
        long created = buf.readLong();
        int myRole = buf.readVarInt();
        int myPerms = buf.readVarInt();
        long treasury = buf.readLong();
        boolean access = buf.readBoolean();
        int chunks = buf.readVarInt();
        long nextCost = buf.readLong();
        int houses = buf.readVarInt();
        int farms = buf.readVarInt();
        int workshops = buf.readVarInt();
        long income = buf.readLong();
        int interval = buf.readVarInt();
        int memberCount = buf.readVarInt();
        List<MemberInfo> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(new MemberInfo(buf.readUtf(), buf.readVarInt(), buf.readBoolean()));
        }
        int[] rolePerms = buf.readVarIntArray();
        int logCount = buf.readVarInt();
        List<LogEntry> log = new ArrayList<>(logCount);
        for (int i = 0; i < logCount; i++) {
            log.add(LogEntry.read(buf));
        }
        MapView map = new MapView(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(),
                buf.readList(FriendlyByteBuf::readUtf), buf.readVarIntArray(), buf.readInt());
        ResearchView research = ResearchView.read(buf);
        return new NationSnapshot(hasNation, wallet, invitations, nationName, leaderName, created, myRole, myPerms,
                treasury, access, chunks, nextCost, houses, farms, workshops, income, interval, members, rolePerms,
                log, map, research);
    }
}
