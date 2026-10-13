package io.github.theoblaise1.sovereignfrontier.energy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
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
 * dimension. Le cache est invalidé quand un câble ou une machine est posé ou retiré ; aucun
 * parcours n'a lieu à chaque tick tant que le réseau ne change pas.
 */
public final class EnergyNetworks {
    /** Taille maximale d'un réseau, garde-fou contre les réseaux démesurés. */
    public static final int MAX_CABLES = 4096;

    private static final Map<ResourceKey<Level>, Map<BlockPos, Set<BlockPos>>> CACHE = new HashMap<>();

    private EnergyNetworks() {
    }

    public static void invalidate(LevelAccessor level) {
        if (level instanceof Level l && !l.isClientSide()) {
            CACHE.remove(l.dimension());
        }
    }

    public static void clear() {
        CACHE.clear();
    }

    /** Machines raccordées à {@code source} : voisines directes et via les réseaux de câbles adjacents. */
    public static List<EnergyNode> connected(ServerLevel level, BlockPos source) {
        Set<BlockPos> nodes = new LinkedHashSet<>();
        for (Direction dir : Direction.values()) {
            BlockPos n = source.relative(dir);
            if (level.getBlockState(n).getBlock() instanceof CableBlock) {
                nodes.addAll(network(level, n));
            } else {
                nodes.add(n);
            }
        }
        nodes.remove(source);
        List<EnergyNode> out = new ArrayList<>();
        for (BlockPos pos : nodes) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof EnergyNode node) {
                out.add(node);
            }
        }
        return out;
    }

    /** Positions des nœuds (non-câbles) bordant le réseau qui contient {@code cable}. */
    private static Set<BlockPos> network(ServerLevel level, BlockPos cable) {
        Map<BlockPos, Set<BlockPos>> byCable = CACHE.computeIfAbsent(level.dimension(), d -> new HashMap<>());
        Set<BlockPos> cached = byCable.get(cable);
        if (cached != null) {
            return cached;
        }
        Set<BlockPos> cables = new HashSet<>();
        Set<BlockPos> nodes = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(cable);
        cables.add(cable);
        while (!queue.isEmpty() && cables.size() < MAX_CABLES) {
            BlockPos pos = queue.poll();
            for (Direction dir : Direction.values()) {
                BlockPos n = pos.relative(dir);
                if (cables.contains(n) || !level.isLoaded(n)) {
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

    /**
     * Distribue l'énergie disponible de {@code buffer} : machines d'abord (parts égales), puis
     * stockage (seulement pour un générateur). Renvoie la quantité transférée.
     */
    public static long push(ServerLevel level, BlockPos source, EnergyBuffer buffer, EnergyRole role) {
        if (role == EnergyRole.CONSUMER || buffer.stored() <= 0) {
            return 0;
        }
        List<EnergyNode> targets = connected(level, source);
        long budget = Math.min(buffer.stored(), buffer.maxOut());
        long sent = distribute(targets, EnergyRole.CONSUMER, budget);
        if (role == EnergyRole.GENERATOR) {
            sent += distribute(targets, EnergyRole.STORAGE, budget - sent);
        }
        buffer.extract(sent);
        return sent;
    }

    private static long distribute(List<EnergyNode> targets, EnergyRole role, long budget) {
        List<EnergyNode> eligible = new ArrayList<>();
        for (EnergyNode n : targets) {
            if (n.energyRole() == role && n.energyBuffer().space() > 0) {
                eligible.add(n);
            }
        }
        long sent = 0;
        for (int i = 0; i < eligible.size() && budget - sent > 0; i++) {
            long share = (budget - sent) / (eligible.size() - i);
            sent += eligible.get(i).energyBuffer().receive(Math.max(1, share));
        }
        return sent;
    }
}
