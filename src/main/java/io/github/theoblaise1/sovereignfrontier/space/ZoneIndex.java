package io.github.theoblaise1.sovereignfrontier.space;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Zones d'oxygène et de chaleur (docs/conception/J5.md §5.2), en mémoire, sur le thread serveur uniquement.
 * <ul>
 *   <li>Index : dimension → chunk → zones qui le touchent. Une zone d'oxygène couvre les (2r + 1)² chunks autour de
 *       sa machine ({@code radius} en chunks) ; une zone de chaleur, les chunks qui touchent son disque
 *       ({@code radius} en blocs, au plus 33 × 33 chunks pour 256 blocs).</li>
 *   <li>{@link #activate} enregistre la zone une seule fois puis, aux appels suivants avec la même forme, se contente
 *       de la rafraîchir ({@code expiresAt = gameTime + }{@value #TTL_TICKS}) en O(1) : le même objet est partagé par
 *       tous les seaux. Une machine qui s'arrête, perd sa clé ou son territoire, ou dont le chunk se décharge, laisse
 *       expirer sa zone ; {@link #remove} la retire (bloc-entité retiré).</li>
 *   <li>{@link #covers} lit le seul seau du chunk demandé. Oxygène : la nation de la zone doit posséder ce chunk (la
 *       zone s'arrête à la frontière, un abandon de chunk la coupe aussitôt) ; chaleur : distance horizontale au carré
 *       au plus r², frontières ignorées.</li>
 * </ul>
 * Vidé à l'arrêt du serveur et au déchargement d'un niveau ({@code PlanetEvents}). Aucun chargement de chunk, aucun
 * parcours de nations.
 */
public final class ZoneIndex {
    /** Durée de vie d'une zone sans rafraîchissement (ticks de jeu). */
    public static final int TTL_TICKS = 40;

    public enum Kind {
        OXYGEN,
        HEAT
    }

    /** Une zone : sa machine ({@code source}), sa forme, sa nation et son expiration. */
    public static final class Zone {
        private final ResourceKey<Level> dimension;
        private final BlockPos source;
        private final Kind kind;
        private final int radius;
        private final int minY;
        private final int maxY;
        private final UUID nation;
        private long expiresAt;

        Zone(ResourceKey<Level> dimension, BlockPos source, Kind kind, int radius, int minY, int maxY, UUID nation) {
            this.dimension = dimension;
            this.source = source;
            this.kind = kind;
            this.radius = radius;
            this.minY = minY;
            this.maxY = maxY;
            this.nation = nation;
        }

        public ResourceKey<Level> dimension() {
            return dimension;
        }

        public BlockPos source() {
            return source;
        }

        public Kind kind() {
            return kind;
        }

        /** Rayon : en chunks pour l'oxygène, en blocs pour la chaleur. */
        public int radius() {
            return radius;
        }

        public int minY() {
            return minY;
        }

        public int maxY() {
            return maxY;
        }

        public UUID nation() {
            return nation;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public boolean alive(long gameTime) {
            return gameTime < expiresAt;
        }

        void refresh(long gameTime) {
            expiresAt = gameTime + TTL_TICKS;
        }

        boolean sameShape(Kind kind, int radius, int minY, int maxY, UUID nation) {
            return this.kind == kind && this.radius == radius && this.minY == minY && this.maxY == maxY
                    && this.nation.equals(nation);
        }

        /** Vrai si la zone, vivante, couvre {@code pos} ; {@code chunkOwner} : nation propriétaire du chunk de pos. */
        boolean covers(BlockPos pos, long gameTime, @Nullable UUID chunkOwner) {
            if (!alive(gameTime) || pos.getY() < minY || pos.getY() > maxY) {
                return false;
            }
            if (kind == Kind.OXYGEN) {
                return nation.equals(chunkOwner);
            }
            long dx = pos.getX() - source.getX();
            long dz = pos.getZ() - source.getZ();
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<List<Zone>>> BUCKETS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, Zone>> SOURCES = new HashMap<>();

    private ZoneIndex() {
    }

    /**
     * Enregistre la zone de la machine en {@code source}, ou la rafraîchit si elle existe déjà avec la même forme
     * (O(1)) ; une forme différente (configuration, nation) remplace l'ancienne zone.
     *
     * @param radius     en chunks pour l'oxygène, en blocs pour la chaleur
     * @param halfHeight la zone couvre Y de {@code source.y − halfHeight} à {@code source.y + halfHeight}
     * @param nation     nation de la machine (propriétaire de son chunk)
     */
    public static Zone activate(ResourceKey<Level> dimension, BlockPos source, Kind kind, int radius, int halfHeight,
                                UUID nation, long gameTime) {
        Map<BlockPos, Zone> sources = SOURCES.computeIfAbsent(dimension, d -> new HashMap<>());
        int minY = source.getY() - Math.max(0, halfHeight);
        int maxY = source.getY() + Math.max(0, halfHeight);
        int r = Math.max(0, radius);
        Zone existing = sources.get(source);
        if (existing != null && existing.sameShape(kind, r, minY, maxY, nation)) {
            existing.refresh(gameTime);
            return existing;
        }
        if (existing != null) {
            unlink(existing);
        }
        Zone zone = new Zone(dimension, source.immutable(), kind, r, minY, maxY, nation);
        zone.refresh(gameTime);
        sources.put(zone.source, zone);
        Long2ObjectOpenHashMap<List<Zone>> buckets = BUCKETS.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
        LongArrayList chunks = chunksOf(zone);
        for (int i = 0; i < chunks.size(); i++) {
            buckets.computeIfAbsent(chunks.getLong(i), c -> new ArrayList<>(2)).add(zone);
        }
        return zone;
    }

    /** Retire la zone de la machine en {@code source} (bloc-entité retiré) ; sans effet s'il n'y en a pas. */
    public static void remove(ResourceKey<Level> dimension, BlockPos source) {
        Map<BlockPos, Zone> sources = SOURCES.get(dimension);
        Zone zone = sources == null ? null : sources.remove(source);
        if (zone != null) {
            unlink(zone);
        }
    }

    private static void unlink(Zone zone) {
        Long2ObjectOpenHashMap<List<Zone>> buckets = BUCKETS.get(zone.dimension);
        if (buckets == null) {
            return;
        }
        LongArrayList chunks = chunksOf(zone);
        for (int i = 0; i < chunks.size(); i++) {
            long chunk = chunks.getLong(i);
            List<Zone> list = buckets.get(chunk);
            if (list != null && list.remove(zone) && list.isEmpty()) {
                buckets.remove(chunk);
            }
        }
    }

    /** Chunks touchés par la zone : carré de chunks (oxygène) ou chunks qui touchent le disque (chaleur). */
    static LongArrayList chunksOf(Zone zone) {
        LongArrayList out = new LongArrayList();
        int x = zone.source.getX();
        int z = zone.source.getZ();
        if (zone.kind == Kind.OXYGEN) {
            int cx = x >> 4;
            int cz = z >> 4;
            for (int dx = -zone.radius; dx <= zone.radius; dx++) {
                for (int dz = -zone.radius; dz <= zone.radius; dz++) {
                    out.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
            return out;
        }
        long r2 = (long) zone.radius * zone.radius;
        for (int cx = (x - zone.radius) >> 4; cx <= (x + zone.radius) >> 4; cx++) {
            for (int cz = (z - zone.radius) >> 4; cz <= (z + zone.radius) >> 4; cz++) {
                long nx = Math.max(cx * 16L, Math.min(x, cx * 16L + 15)) - x;
                long nz = Math.max(cz * 16L, Math.min(z, cz * 16L + 15)) - z;
                if (nx * nx + nz * nz <= r2) {
                    out.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        return out;
    }

    /** Vrai si une zone vivante du type {@code kind} couvre {@code pos} dans ce niveau (territoire réel). */
    public static boolean covers(ServerLevel level, BlockPos pos, Kind kind) {
        return covers(level.dimension(), pos, kind, level.getGameTime(), ChunkOwners.of(level.getServer()));
    }

    /**
     * Requête pure (GameTests) : une lecture de seau, puis au plus une lecture de propriétaire pour l'oxygène.
     *
     * @param owners propriétaire des chunks (seul celui de {@code pos} est lu)
     */
    public static boolean covers(ResourceKey<Level> dimension, BlockPos pos, Kind kind, long gameTime, ChunkOwners owners) {
        Long2ObjectOpenHashMap<List<Zone>> buckets = BUCKETS.get(dimension);
        if (buckets == null) {
            return false;
        }
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        List<Zone> zones = buckets.get(chunk);
        if (zones == null) {
            return false;
        }
        UUID owner = null;
        boolean ownerRead = false;
        for (Zone zone : zones) {
            if (zone.kind != kind) {
                continue;
            }
            if (kind == Kind.OXYGEN && !ownerRead) {
                owner = owners.ownerAt(dimension, chunk);
                ownerRead = true;
            }
            if (zone.covers(pos, gameTime, owner)) {
                return true;
            }
        }
        return false;
    }

    /** Zone enregistrée pour la machine en {@code source}, vivante ou non (tests, diagnostic). */
    @Nullable
    public static Zone zoneAt(ResourceKey<Level> dimension, BlockPos source) {
        Map<BlockPos, Zone> sources = SOURCES.get(dimension);
        return sources == null ? null : sources.get(source);
    }

    /** Nombre de zones enregistrées dans la dimension. */
    public static int size(ResourceKey<Level> dimension) {
        Map<BlockPos, Zone> sources = SOURCES.get(dimension);
        return sources == null ? 0 : sources.size();
    }

    /** Déchargement d'un niveau : ses machines réenregistreront leurs zones au prochain chargement. */
    public static void clearLevel(ResourceKey<Level> dimension) {
        BUCKETS.remove(dimension);
        SOURCES.remove(dimension);
    }

    /** Arrêt du serveur. */
    public static void clear() {
        BUCKETS.clear();
        SOURCES.clear();
    }
}
