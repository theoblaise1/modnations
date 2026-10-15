package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Validation d'un bâtiment civil autour d'un Registre civil. Calcul borné et à la demande
 * (jamais par tick) : remplissage de l'air intérieur limité à {@code maxVolume} blocs. Aucun
 * chunk non chargé n'est lu ni chargé : le résultat est alors « indéterminé » ({@link Validation#unloaded()}).
 * <ul>
 *   <li>Maison : pièce close et couverte contenant un lit et une porte.</li>
 *   <li>Atelier : pièce close et couverte avec établi, four (ou fumoir/haut fourneau) et porte.</li>
 *   <li>Ferme : à défaut de maison ou d'atelier (plein air, ou pièce close comme une serre), au moins
 *   N blocs de terre labourée du territoire de la nation dans un rayon de 4 blocs.</li>
 * </ul>
 */
public final class BuildingValidator {
    /** Zone de comptage d'une ferme : ±4 blocs à l'horizontale, de 2 blocs sous le registre à 1 bloc au-dessus. */
    private static final int FARM_RADIUS = 4;
    private static final int FARM_BELOW = 2;
    private static final int FARM_ABOVE = 1;

    private BuildingValidator() {
    }

    /**
     * @param type     type reconnu, ou {@code null} si invalide
     * @param message  explication (succès ou raison de l'échec)
     * @param boundary blocs bordant l'intérieur d'une pièce close (murs, lit, porte, registre…) ; vide pour une ferme en plein air
     * @param unloaded une partie de la zone est dans un chunk non chargé : résultat indéterminé, à ne pas appliquer
     */
    public record Validation(@Nullable BuildingType type, Component message, Set<BlockPos> boundary, boolean unloaded) {
        public Validation(@Nullable BuildingType type, Component message, Set<BlockPos> boundary) {
            this(type, message, boundary, false);
        }

        public boolean valid() {
            return type != null;
        }
    }

    /** Issue du remplissage : pièce close et couverte ({@code openReason == null}), ouverte, ou zone non chargée. */
    private record Fill(@Nullable Component openReason, boolean unloaded) {
        private static final Fill CLOSED = new Fill(null, false);
        private static final Fill UNLOADED = new Fill(null, true);

        private static Fill open(Component reason) {
            return new Fill(reason, false);
        }
    }

    /**
     * @param ownLand filtre des blocs de terre labourée comptés pour une ferme (territoire de la nation,
     *                lu dans le cache par chunk)
     */
    public static Validation validate(ServerLevel level, BlockPos origin, int maxVolume, int farmMinFarmland,
                                      Predicate<BlockPos> ownLand) {
        Set<BlockPos> interior = new HashSet<>();
        Set<BlockPos> boundary = new HashSet<>();
        Fill fill = floodFill(level, origin, maxVolume, interior, boundary);
        if (fill.unloaded()) {
            return unloaded();
        }

        if (fill.openReason() == null) {
            boolean bed = false;
            boolean door = false;
            boolean crafting = false;
            boolean furnace = false;
            for (BlockPos pos : boundary) {
                Block block = level.getBlockState(pos).getBlock();
                bed |= block instanceof BedBlock;
                door |= block instanceof DoorBlock;
                crafting |= block instanceof CraftingTableBlock;
                furnace |= block instanceof AbstractFurnaceBlock;
            }
            if (door && bed) {
                return new Validation(BuildingType.HOUSE, Msg.ok("building.valid.house", interior.size()), boundary);
            }
            if (door && crafting && furnace) {
                return new Validation(BuildingType.WORKSHOP, Msg.ok("building.valid.workshop", interior.size()), boundary);
            }
            // Serre ou abri : une pièce close qui n'est ni maison ni atelier peut encore être une ferme.
            if (!farmAreaLoaded(level, origin)) {
                return unloaded();
            }
            int farmland = countFarmland(level, origin, ownLand);
            if (farmland >= farmMinFarmland) {
                return new Validation(BuildingType.FARM, Msg.ok("building.valid.farm", farmland), boundary);
            }
            if (!door) {
                return fail(Msg.err("building.error.no_door"), boundary);
            }
            return fail(Msg.err("building.error.closed_room_missing", farmland, farmMinFarmland), boundary);
        }

        if (!farmAreaLoaded(level, origin)) {
            return unloaded();
        }
        int farmland = countFarmland(level, origin, ownLand);
        if (farmland >= farmMinFarmland) {
            return new Validation(BuildingType.FARM, Msg.ok("building.valid.farm", farmland), Set.of());
        }
        return fail(Msg.err("building.error.invalid", fill.openReason(), farmland, farmMinFarmland), boundary);
    }

    /** Vrai si les zones de comptage de deux fermes se chevauchent (moins de 9 blocs d'écart à l'horizontale, 3 au plus en hauteur). */
    public static boolean farmAreasOverlap(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) <= 2 * FARM_RADIUS
                && Math.abs(a.getZ() - b.getZ()) <= 2 * FARM_RADIUS
                && Math.abs(a.getY() - b.getY()) <= FARM_BELOW + FARM_ABOVE;
    }

    private static Validation fail(Component message, Set<BlockPos> boundary) {
        return new Validation(null, message, boundary);
    }

    private static Validation unloaded() {
        return new Validation(null, Msg.err("building.error.unloaded"), Set.of(), true);
    }

    /**
     * Remplit l'air intérieur depuis le registre. S'arrête dès qu'une case voisine est dans un chunk
     * non chargé, avant toute lecture : la bordure ne contient donc que des positions chargées.
     */
    private static Fill floodFill(ServerLevel level, BlockPos origin, int maxVolume,
                                  Set<BlockPos> interior, Set<BlockPos> boundary) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boundary.add(origin);
        for (Direction dir : Direction.values()) {
            BlockPos next = origin.relative(dir);
            if (!chunkLoaded(level, next)) {
                return Fill.UNLOADED;
            }
            if (isPassable(level, next)) {
                if (interior.add(next)) {
                    queue.add(next);
                }
            } else {
                boundary.add(next);
            }
        }
        if (interior.isEmpty()) {
            return Fill.open(Msg.tr("building.reason.no_air"));
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())) {
                return Fill.open(Msg.tr("building.reason.no_roof", pos.toShortString()));
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (interior.contains(next) || boundary.contains(next)) {
                    continue;
                }
                if (!chunkLoaded(level, next)) {
                    return Fill.UNLOADED;
                }
                if (isPassable(level, next)) {
                    interior.add(next);
                    if (interior.size() > maxVolume) {
                        return Fill.open(Msg.tr("building.reason.too_big", maxVolume));
                    }
                    queue.add(next);
                } else {
                    boundary.add(next);
                }
            }
        }
        return Fill.CLOSED;
    }

    /** Chunk chargé (sans le charger) ; la hauteur n'est pas considérée, voir {@link #isPassable}. */
    private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
        return level.hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** Air et blocs sans collision ; portes, trappes et portillons comptent comme murs, hors limites de hauteur aussi. */
    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** Les chunks couvrant la zone d'une ferme (au plus 2×2) sont-ils chargés ? */
    private static boolean farmAreaLoaded(ServerLevel level, BlockPos origin) {
        int minX = SectionPos.blockToSectionCoord(origin.getX() - FARM_RADIUS);
        int maxX = SectionPos.blockToSectionCoord(origin.getX() + FARM_RADIUS);
        int minZ = SectionPos.blockToSectionCoord(origin.getZ() - FARM_RADIUS);
        int maxZ = SectionPos.blockToSectionCoord(origin.getZ() + FARM_RADIUS);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Terre labourée de la zone de la ferme acceptée par {@code ownLand} ; zone supposée chargée. */
    private static int countFarmland(ServerLevel level, BlockPos origin, Predicate<BlockPos> ownLand) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -FARM_RADIUS; dx <= FARM_RADIUS; dx++) {
            for (int dz = -FARM_RADIUS; dz <= FARM_RADIUS; dz++) {
                for (int dy = -FARM_BELOW; dy <= FARM_ABOVE; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.getBlockState(m).getBlock() instanceof FarmBlock && ownLand.test(m)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
