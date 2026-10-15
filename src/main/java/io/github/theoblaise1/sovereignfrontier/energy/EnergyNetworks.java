package io.github.theoblaise1.sovereignfrontier.energy;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Réseaux de câbles : calculés à la demande par parcours en largeur puis mis en cache par
 * dimension. Le cache est invalidé quand un câble ou une machine est posé ou retiré, et quand le
 * territoire d'une dimension change ; aucun parcours n'a lieu à chaque tick tant que rien ne change.
 *
 * <p>Un réseau ne traverse jamais une frontière nationale : deux câbles ou machines ne se relient que
 * s'ils ont le même propriétaire ({@link NationData#ownerAt}, {@code null} pour un territoire libre).
 * Le propriétaire est revérifié à chaque transfert, ce qui protège aussi d'un cache construit avant un
 * changement de territoire.
 *
 * <p>Le réseau ne charge jamais de chunk : une position dans un chunk non chargé est ignorée. Un
 * parcours qui a rencontré un tel chunk le note ; son chargement invalide alors le cache de la
 * dimension, si bien qu'un réseau incomplet n'est jamais gardé en cache après le retour du chunk.
 */
public final class EnergyNetworks {
    /** Taille maximale d'un réseau, garde-fou contre les réseaux démesurés. */
    public static final int MAX_CABLES = 4096;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Set<BlockPos>>> CACHE = new HashMap<>();
    /** Chunks non chargés rencontrés par les parcours, par dimension. */
    private static final Map<ResourceKey<Level>, LongSet> PENDING = new HashMap<>();

    private EnergyNetworks() {
    }

    public static void invalidate(LevelAccessor level) {
        if (level instanceof Level l && !l.isClientSide()) {
            invalidateDimension(l.dimension());
        }
    }

    /** À appeler quand le territoire d'une dimension change (prise, abandon, dissolution de nation). */
    public static void invalidateDimension(ResourceKey<Level> dimension) {
        CACHE.remove(dimension);
        PENDING.remove(dimension);
    }

    /** Un chunk vient d'être chargé : si un parcours l'avait rencontré non chargé, le cache de la dimension est recalculé. */
    public static void chunkLoaded(ResourceKey<Level> dimension, long chunk) {
        LongSet pending = PENDING.get(dimension);
        if (pending != null && pending.contains(chunk)) {
            invalidateDimension(dimension);
        }
    }

    public static void clear() {
        CACHE.clear();
        PENDING.clear();
    }

    /** Machines raccordées à {@code source} : voisines directes et via les réseaux de câbles adjacents, même propriétaire. */
    public static List<EnergyNode> connected(ServerLevel level, BlockPos source) {
        NationData data = NationData.get(level.getServer());
        Nation owner = data.ownerAt(level.dimension(), source);
        Set<BlockPos> nodes = new LinkedHashSet<>();
        for (Direction dir : Direction.values()) {
            BlockPos n = source.relative(dir);
            if (!level.isLoaded(n) || data.ownerAt(level.dimension(), n) != owner) {
                continue;
            }
            if (level.getBlockState(n).getBlock() instanceof CableBlock) {
                nodes.addAll(network(level, data, n));
            } else {
                nodes.add(n);
            }
        }
        nodes.remove(source);
        List<EnergyNode> out = new ArrayList<>();
        for (BlockPos pos : nodes) {
            if (level.isLoaded(pos) && data.ownerAt(level.dimension(), pos) == owner
                    && level.getBlockEntity(pos) instanceof EnergyNode node) {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * Positions des nœuds (non-câbles) bordant le réseau qui contient {@code cable}. Le parcours ne
     * traverse que les câbles du même propriétaire et ignore les chunks non chargés (notés en attente).
     */
    private static Set<BlockPos> network(ServerLevel level, NationData data, BlockPos cable) {
        Map<BlockPos, Set<BlockPos>> byCable = CACHE.computeIfAbsent(level.dimension(), d -> new HashMap<>());
        Set<BlockPos> cached = byCable.get(cable);
        if (cached != null) {
            return cached;
        }
        Nation owner = data.ownerAt(level.dimension(), cable);
        Set<BlockPos> cables = new HashSet<>();
        Set<BlockPos> nodes = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(cable);
        cables.add(cable);
        while (!queue.isEmpty() && cables.size() < MAX_CABLES) {
            BlockPos pos = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos n = pos.relative(dir);
                if (cables.contains(n)) {
                    continue;
                }
                if (!level.isLoaded(n)) {
                    markPending(level, n);
                    continue;
                }
                if (data.ownerAt(level.dimension(), n) != owner) {
                    continue;
                }
                if (level.getBlockState(n).getBlock() instanceof CableBlock) {
                    cables.add(n);
                    queue.add(n);
                } else if (level.getBlockEntity(n) instanceof EnergyNode) {
                    nodes.add(n.immutable());
                }
            }
        }
        for (BlockPos c : cables) {
            byCable.put(c, nodes);
        }
        return nodes;
    }

    /** Note que le chunk de {@code pos} était non chargé pendant un parcours : son chargement invalidera le cache. */
    private static void markPending(ServerLevel level, BlockPos pos) {
        PENDING.computeIfAbsent(level.dimension(), d -> new LongOpenHashSet())
                .add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /**
     * Distribue l'énergie disponible de {@code buffer} : machines d'abord (parts égales), puis
     * stockage (seulement pour un générateur). Renvoie la quantité transférée.
     */
    public static long push(ServerLevel level, BlockPos source, EnergyBuffer buffer, EnergyRole role) {
        if (role == EnergyRole.CONSUMER || buffer.stored() <= 0) {
            return 0;
        }
        List<EnergyNode> targets = connected(level, source);
        long time = level.getGameTime();
        long budget = Math.min(buffer.stored(), buffer.maxOut());
        long sent = distribute(targets, EnergyRole.CONSUMER, budget, time);
        if (role == EnergyRole.GENERATOR) {
            sent += distribute(targets, EnergyRole.STORAGE, budget - sent, time);
        }
        buffer.extract(sent);
        return sent;
    }

    private static long distribute(List<EnergyNode> targets, EnergyRole role, long budget, long time) {
        List<EnergyNode> eligible = new ArrayList<>();
        for (EnergyNode n : targets) {
            if (n.energyRole() == role && n.energyBuffer().acceptable(time) > 0) {
                eligible.add(n);
            }
        }
        long sent = 0;
        for (int i = 0; i < eligible.size() && budget - sent > 0; i++) {
            long share = (budget - sent) / (eligible.size() - i);
            sent += eligible.get(i).energyBuffer().receive(Math.max(1, share), time);
        }
        return sent;
    }
}
