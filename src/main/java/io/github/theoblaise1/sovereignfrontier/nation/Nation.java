package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.contract.NationContracts;
import io.github.theoblaise1.sovereignfrontier.enterprise.NationEnterprises;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.research.NationResearch;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import io.github.theoblaise1.sovereignfrontier.block.NationAccessBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Une nation : membres et rôles, permissions par rôle, trésorerie, territoire, bâtiments civils,
 * coffre national partagé, journal des 50 dernières actions sensibles, recherche, contrats et entreprises.
 * Toute mutation passe par {@link #markDirty()} pour que le {@link NationData} soit sauvegardé.
 */
public final class Nation {
    public static final int CHEST_SIZE = 54;
    public static final int LOG_SIZE = 50;

    private final UUID id;
    private String name;
    private final long createdEpochMs;
    private UUID leader;
    private final LinkedHashMap<UUID, NationRole> members = new LinkedHashMap<>();
    private final Map<UUID, String> memberNames = new HashMap<>();
    private final EnumMap<NationRole, EnumSet<NationPermission>> permissions = new EnumMap<>(NationRole.class);
    private long treasury;
    private final Map<ResourceKey<Level>, LongOpenHashSet> claims = new HashMap<>();
    private int claimCount;
    /** Chunk de fondation (capitale), ou {@code null} pour une nation fondée avant J7 (aucune capitale connue). */
    @Nullable
    private CapitalChunk capital;
    private final Map<UUID, Long> invites = new HashMap<>();
    private final ArrayDeque<LogEntry> log = new ArrayDeque<>();
    private final LinkedHashMap<GlobalPos, BuildingType> buildings = new LinkedHashMap<>();
    /** Trésors et coffres nationaux posés (enregistrés à la pose) : interdisent d'abandonner leur parcelle. */
    private final Set<GlobalPos> cores = new LinkedHashSet<>();
    private final SimpleContainer chest = new SimpleContainer(CHEST_SIZE);
    private NationResearch research = new NationResearch();
    private NationContracts contracts = new NationContracts();
    private NationEnterprises enterprises = new NationEnterprises();
    private NationSpace space = new NationSpace();
    private Runnable dirtyCallback = () -> {
    };

    Nation(UUID id, String name, long createdEpochMs, UUID leader) {
        this.id = id;
        this.name = name;
        this.createdEpochMs = createdEpochMs;
        this.leader = leader;
        for (NationRole role : NationRole.values()) {
            permissions.put(role, NationPermission.defaults(role));
        }
        // Le contenu du coffre alimente l'onglet Science des membres : rafraîchissement groupé
        // (au plus une fois par seconde), jamais un envoi par objet déplacé.
        chest.addListener(c -> {
            markDirty();
            NationSync.scheduleRefresh(this);
        });
    }

    void setDirtyCallback(Runnable callback) {
        this.dirtyCallback = callback;
    }

    public void markDirty() {
        dirtyCallback.run();
    }

    // ------------------------------------------------------------------ identité

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long createdEpochMs() {
        return createdEpochMs;
    }

    public UUID leader() {
        return leader;
    }

    /** Couleur stable dérivée de l'identifiant, utilisée sur la carte. */
    public int color() {
        float hue = (id.hashCode() & 0xFFFF) / 65535.0F;
        return Mth.hsvToRgb(hue, 0.65F, 0.9F);
    }

    // ------------------------------------------------------------------ membres

    public Map<UUID, NationRole> members() {
        return Collections.unmodifiableMap(members);
    }

    public int memberCount() {
        return members.size();
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    @Nullable
    public NationRole roleOf(UUID player) {
        return members.get(player);
    }

    public String memberName(UUID player) {
        return memberNames.getOrDefault(player, player.toString().substring(0, 8));
    }

    public void updateMemberName(UUID player, String name) {
        if (members.containsKey(player) && !name.equals(memberNames.get(player))) {
            memberNames.put(player, name);
            markDirty();
        }
    }

    void addMember(UUID player, String playerName, NationRole role) {
        members.put(player, role);
        memberNames.put(player, playerName);
        invites.remove(player);
        markDirty();
    }

    void removeMember(UUID player) {
        members.remove(player);
        memberNames.remove(player);
        markDirty();
    }

    /** Vrai si le rang a réellement changé (membre connu, rang différent de l'actuel). */
    boolean setRole(UUID player, NationRole role) {
        NationRole old = members.get(player);
        if (old == null || old == role) {
            return false;
        }
        members.put(player, role);
        markDirty();
        return true;
    }

    void setLeader(UUID newLeader) {
        if (leader != null && members.containsKey(leader)) {
            members.put(leader, NationRole.MINISTRE);
        }
        leader = newLeader;
        members.put(newLeader, NationRole.CHEF);
        markDirty();
    }

    // ------------------------------------------------------------------ permissions

    public boolean hasPermission(UUID player, NationPermission permission) {
        NationRole role = members.get(player);
        if (role == null) {
            return false;
        }
        return role == NationRole.CHEF || permissions.get(role).contains(permission);
    }

    public boolean roleHas(NationRole role, NationPermission permission) {
        return role == NationRole.CHEF || permissions.get(role).contains(permission);
    }

    public int permissionMask(NationRole role) {
        return role == NationRole.CHEF ? NationPermission.toMask(EnumSet.allOf(NationPermission.class))
                : NationPermission.toMask(permissions.get(role));
    }

    /** Vrai si la matrice a réellement changé (jamais pour le chef, qui a toutes les permissions). */
    boolean setPermission(NationRole role, NationPermission permission, boolean granted) {
        if (role == NationRole.CHEF) {
            return false;
        }
        EnumSet<NationPermission> set = permissions.get(role);
        boolean changed = granted ? set.add(permission) : set.remove(permission);
        if (changed) {
            markDirty();
        }
        return changed;
    }

    // ------------------------------------------------------------------ invitations

    void invite(UUID player, long expiresAtMs) {
        invites.put(player, expiresAtMs);
        markDirty();
    }

    public boolean hasValidInvite(UUID player, long nowMs) {
        Long expiry = invites.get(player);
        return expiry != null && expiry > nowMs;
    }

    void purgeExpiredInvites(long nowMs) {
        if (invites.values().removeIf(exp -> exp <= nowMs)) {
            markDirty();
        }
    }

    // ------------------------------------------------------------------ trésorerie

    public long treasury() {
        return treasury;
    }

    void addTreasury(long amount) {
        treasury = Math.max(0L, saturatedAdd(treasury, amount));
        markDirty();
    }

    static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return a > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    // ------------------------------------------------------------------ territoire

    public int claimCount() {
        return claimCount;
    }

    public boolean ownsChunk(ResourceKey<Level> dim, long chunk) {
        LongOpenHashSet set = claims.get(dim);
        return set != null && set.contains(chunk);
    }

    public boolean hasClaimsIn(ResourceKey<Level> dim) {
        LongOpenHashSet set = claims.get(dim);
        return set != null && !set.isEmpty();
    }

    Map<ResourceKey<Level>, LongOpenHashSet> claims() {
        return claims;
    }

    void addClaim(ResourceKey<Level> dim, long chunk) {
        if (claims.computeIfAbsent(dim, d -> new LongOpenHashSet()).add(chunk)) {
            claimCount++;
            markDirty();
        }
    }

    void removeClaim(ResourceKey<Level> dim, long chunk) {
        LongOpenHashSet set = claims.get(dim);
        if (set != null && set.remove(chunk)) {
            claimCount--;
            if (set.isEmpty()) {
                claims.remove(dim);
            }
            markDirty();
        }
    }

    /** Chunk de capitale (fondation), ou {@code null} si la nation a été fondée avant J7. */
    @Nullable
    public CapitalChunk capital() {
        return capital;
    }

    /** Fixe le chunk de capitale, à la fondation uniquement (appelé par {@link NationManager#create}). */
    void setCapital(CapitalChunk chunk) {
        capital = chunk;
        markDirty();
    }

    // ------------------------------------------------------------------ bâtiments civils

    /** Nombre de bâtiments civils validés (1 habitant chacun, avant bonus). */
    public int population() {
        return buildings.size();
    }

    /** Habitants effectifs : bâtiments × (1 + bonus de population des recherches), arrondi à l'inférieur. */
    public int effectivePopulation() {
        double multiplier = 1.0D + Math.max(-1.0D, research.bonus(ResearchStat.POPULATION_MULTIPLIER));
        return (int) Math.floor(buildings.size() * multiplier + 1e-9);
    }

    /** État de recherche (paliers terminés, recherche active, points). Appeler {@link #markDirty()} après mutation. */
    public NationResearch research() {
        return research;
    }

    /** Tableau de contrats (J4). Appeler {@link #markDirty()} après mutation. */
    public NationContracts contracts() {
        return contracts;
    }

    /** Entreprises : habitants affectés et production (J4). Appeler {@link #markDirty()} après mutation. */
    public NationEnterprises enterprises() {
        return enterprises;
    }

    /** Programme spatial : premiers atterrissages et vols (J5). Appeler {@link #markDirty()} après mutation. */
    public NationSpace space() {
        return space;
    }

    public int buildingCount(BuildingType type) {
        int n = 0;
        for (BuildingType t : buildings.values()) {
            if (t == type) {
                n++;
            }
        }
        return n;
    }

    public Map<GlobalPos, BuildingType> buildings() {
        return Collections.unmodifiableMap(buildings);
    }

    void addBuilding(GlobalPos pos, BuildingType type) {
        buildings.put(pos, type);
        markDirty();
    }

    /**
     * Trésors et coffres nationaux enregistrés, dans l'ordre de pose (lecture seule). Une position peut être périmée
     * (bloc détruit hors événement) : elle n'est retirée qu'à la vérification de son chunk.
     */
    public Set<GlobalPos> cores() {
        return Collections.unmodifiableSet(cores);
    }

    /** Trésor ou coffre national posé sur le territoire de la nation. */
    public void addCore(GlobalPos pos) {
        cores.add(pos);
        markDirty();
    }

    /**
     * Vrai si un Trésor ou un coffre national de la nation se trouve dans ce chunk. Une position dont le bloc a
     * disparu (explosion, bloc cassé hors événement) est retirée ; un chunk non chargé compte comme occupé,
     * sans être chargé pour la vérifier.
     */
    boolean holdsCoreIn(ServerLevel level, long chunk) {
        boolean holds = false;
        Iterator<GlobalPos> it = cores.iterator();
        while (it.hasNext()) {
            GlobalPos core = it.next();
            BlockPos pos = core.pos();
            if (!core.dimension().equals(level.dimension()) || ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4) != chunk) {
                continue;
            }
            if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || level.getBlockState(pos).getBlock() instanceof NationAccessBlock) {
                holds = true;
            } else {
                it.remove();
                markDirty();
            }
        }
        return holds;
    }

    @Nullable
    BuildingType removeBuilding(GlobalPos pos) {
        BuildingType removed = buildings.remove(pos);
        if (removed != null) {
            markDirty();
        }
        return removed;
    }

    /** Retire les bâtiments situés dans un chunk abandonné ; renvoie le nombre retiré. */
    int removeBuildingsInChunk(ResourceKey<Level> dim, long chunk) {
        int removed = 0;
        Iterator<GlobalPos> it = buildings.keySet().iterator();
        while (it.hasNext()) {
            GlobalPos gp = it.next();
            if (gp.dimension().equals(dim) && chunkKey(gp.pos()) == chunk) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    public static long chunkKey(BlockPos pos) {
        return net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    // ------------------------------------------------------------------ coffre et journal

    public SimpleContainer chest() {
        return chest;
    }

    public void log(String key, String... args) {
        log.addLast(new LogEntry(System.currentTimeMillis(), key, List.of(args)));
        while (log.size() > LOG_SIZE) {
            log.removeFirst();
        }
        markDirty();
    }

    public List<LogEntry> logEntries() {
        return new ArrayList<>(log);
    }

    void rename(String newName) {
        this.name = newName;
        markDirty();
    }

    // ------------------------------------------------------------------ persistance NBT

    CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putLong("created", createdEpochMs);
        tag.putUUID("leader", leader);
        tag.putLong("treasury", treasury);

        ListTag memberList = new ListTag();
        for (Map.Entry<UUID, NationRole> e : members.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("uuid", e.getKey());
            m.putString("role", e.getValue().id());
            m.putString("name", memberName(e.getKey()));
            memberList.add(m);
        }
        tag.put("members", memberList);

        tag.putInt("permVersion", NationPermission.CURRENT_VERSION);
        CompoundTag perms = new CompoundTag();
        for (NationRole role : NationRole.values()) {
            ListTag list = new ListTag();
            for (NationPermission p : permissions.get(role)) {
                list.add(StringTag.valueOf(p.id()));
            }
            perms.put(role.id(), list);
        }
        tag.put("permissions", perms);

        CompoundTag claimTag = new CompoundTag();
        for (Map.Entry<ResourceKey<Level>, LongOpenHashSet> e : claims.entrySet()) {
            claimTag.put(e.getKey().location().toString(), new LongArrayTag(e.getValue().toLongArray()));
        }
        tag.put("claims", claimTag);

        ListTag inviteList = new ListTag();
        for (Map.Entry<UUID, Long> e : invites.entrySet()) {
            CompoundTag i = new CompoundTag();
            i.putUUID("uuid", e.getKey());
            i.putLong("expires", e.getValue());
            inviteList.add(i);
        }
        tag.put("invites", inviteList);

        ListTag logList = new ListTag();
        for (LogEntry entry : log) {
            logList.add(entry.save());
        }
        tag.put("log", logList);

        ListTag buildingList = new ListTag();
        for (Map.Entry<GlobalPos, BuildingType> e : buildings.entrySet()) {
            CompoundTag b = new CompoundTag();
            b.putString("dim", e.getKey().dimension().location().toString());
            b.putLong("pos", e.getKey().pos().asLong());
            b.putString("type", e.getValue().id());
            buildingList.add(b);
        }
        tag.put("buildings", buildingList);

        ListTag coreList = new ListTag();
        for (GlobalPos core : cores) {
            CompoundTag c = new CompoundTag();
            c.putString("dim", core.dimension().location().toString());
            c.putLong("pos", core.pos().asLong());
            coreList.add(c);
        }
        tag.put("cores", coreList);
        if (capital != null) {
            tag.put("capital", capital.save());
        }

        CompoundTag chestTag = new CompoundTag();
        ContainerHelper.saveAllItems(chestTag, chest.getItems(), registries);
        tag.put("chest", chestTag);
        tag.put("research", research.save());
        tag.put("contracts", contracts.save());
        tag.put("enterprises", enterprises.save());
        tag.put("space", space.save());
        return tag;
    }

    static Nation load(CompoundTag tag, HolderLookup.Provider registries) {
        Nation n = new Nation(tag.getUUID("id"), tag.getString("name"), tag.getLong("created"), tag.getUUID("leader"));
        n.treasury = tag.getLong("treasury");

        ListTag memberList = tag.getList("members", Tag.TAG_COMPOUND);
        for (int i = 0; i < memberList.size(); i++) {
            CompoundTag m = memberList.getCompound(i);
            UUID uuid = m.getUUID("uuid");
            n.members.put(uuid, NationRole.byId(m.getString("role")).orElse(NationRole.RECRUE));
            n.memberNames.put(uuid, m.getString("name"));
        }
        n.members.put(n.leader, NationRole.CHEF);

        // Les ensembles sauvegardés remplacent les défauts : une permission ajoutée depuis (permVersion absent = 0)
        // n'y figurerait pas sans migration.
        int permVersion = tag.getInt("permVersion");
        CompoundTag perms = tag.getCompound("permissions");
        for (NationRole role : NationRole.values()) {
            if (!perms.contains(role.id())) {
                continue;
            }
            EnumSet<NationPermission> set = EnumSet.noneOf(NationPermission.class);
            ListTag list = perms.getList(role.id(), Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                NationPermission.byId(list.getString(i)).ifPresent(set::add);
            }
            n.permissions.put(role, NationPermission.migrate(role, set, permVersion));
        }

        CompoundTag claimTag = tag.getCompound("claims");
        for (String dimKey : claimTag.getAllKeys()) {
            ResourceLocation loc = ResourceLocation.tryParse(dimKey);
            if (loc == null) {
                continue;
            }
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, loc);
            LongOpenHashSet set = new LongOpenHashSet(claimTag.getLongArray(dimKey));
            if (!set.isEmpty()) {
                n.claims.put(dim, set);
                n.claimCount += set.size();
            }
        }

        ListTag inviteList = tag.getList("invites", Tag.TAG_COMPOUND);
        for (int i = 0; i < inviteList.size(); i++) {
            CompoundTag inv = inviteList.getCompound(i);
            n.invites.put(inv.getUUID("uuid"), inv.getLong("expires"));
        }

        ListTag logList = tag.getList("log", Tag.TAG_COMPOUND);
        for (int i = 0; i < logList.size(); i++) {
            n.log.addLast(LogEntry.load(logList.getCompound(i)));
        }
        while (n.log.size() > LOG_SIZE) {
            n.log.removeFirst();
        }

        ListTag buildingList = tag.getList("buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            CompoundTag b = buildingList.getCompound(i);
            ResourceLocation loc = ResourceLocation.tryParse(b.getString("dim"));
            if (loc == null) {
                continue;
            }
            GlobalPos gp = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, loc), BlockPos.of(b.getLong("pos")));
            n.buildings.put(gp, BuildingType.byId(b.getString("type")));
        }

        ListTag coreList = tag.getList("cores", Tag.TAG_COMPOUND);
        for (int i = 0; i < coreList.size(); i++) {
            CompoundTag c = coreList.getCompound(i);
            ResourceLocation loc = ResourceLocation.tryParse(c.getString("dim"));
            if (loc != null) {
                n.cores.add(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, loc), BlockPos.of(c.getLong("pos"))));
            }
        }

        // Absent des sauvegardes antérieures à J7 : la nation n'a alors pas de capitale connue (aucune protection).
        if (tag.contains("capital", Tag.TAG_COMPOUND)) {
            n.capital = CapitalChunk.load(tag.getCompound("capital"));
        }
        ContainerHelper.loadAllItems(tag.getCompound("chest"), n.chest.getItems(), registries);
        n.research = NationResearch.load(tag.getCompound("research"));
        n.contracts = NationContracts.load(tag.getCompound("contracts"));
        n.enterprises = NationEnterprises.load(tag.getCompound("enterprises"));
        n.space = NationSpace.load(tag.getCompound("space"));
        return n;
    }

    Set<UUID> memberIds() {
        return members.keySet();
    }
}
