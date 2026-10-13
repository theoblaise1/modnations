package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

/**
 * Validation d'un bâtiment civil autour d'un Registre civil. Calcul borné et à la demande
 * (jamais par tick) : remplissage de l'air intérieur limité à {@code maxVolume} blocs.
 * <ul>
 *   <li>Maison : pièce close et couverte contenant un lit et une porte.</li>
 *   <li>Atelier : pièce close et couverte avec établi, four (ou fumoir/haut fourneau) et porte.</li>
 *   <li>Ferme : à défaut de pièce close, au moins N blocs de terre labourée dans un rayon de 4 blocs.</li>
 * </ul>
 */
public final class BuildingValidator {
    private BuildingValidator() {
    }

    /**
     * @param type     type reconnu, ou {@code null} si invalide
     * @param message  explication (succès ou raison de l'échec)
     * @param boundary blocs bordant l'intérieur (murs, lit, porte, registre…) ; vide pour une ferme
     */
    public record Validation(@Nullable BuildingType type, Component message, Set<BlockPos> boundary) {
        public boolean valid() {
            return type != null;
        }
    }

    public static Validation validate(ServerLevel level, BlockPos origin, int maxVolume, int farmMinFarmland) {
        Set<BlockPos> interior = new HashSet<>();
        Set<BlockPos> boundary = new HashSet<>();
        Component openReason = floodFill(level, origin, maxVolume, interior, boundary);

        if (openReason == null) {
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
            if (!door) {
                return fail(Msg.err("building.error.no_door"), boundary);
            }
            if (bed) {
                return new Validation(BuildingType.HOUSE, Msg.ok("building.valid.house", interior.size()), boundary);
            }
            if (crafting && furnace) {
                return new Validation(BuildingType.WORKSHOP, Msg.ok("building.valid.workshop", interior.size()), boundary);
            }
            return fail(Msg.err("building.error.closed_room_missing"), boundary);
        }

        int farmland = countFarmland(level, origin);
        if (farmland >= farmMinFarmland) {
            return new Validation(BuildingType.FARM, Msg.ok("building.valid.farm", farmland), Set.of());
        }
        return fail(Msg.err("building.error.invalid", openReason, farmland, farmMinFarmland), boundary);
    }

    private static Validation fail(Component message, Set<BlockPos> boundary) {
        return new Validation(null, message, boundary);
    }

    /** Renvoie {@code null} si la pièce est close et couverte, sinon la raison de l'ouverture. */
    @Nullable
    private static Component floodFill(ServerLevel level, BlockPos origin, int maxVolume,
                                       Set<BlockPos> interior, Set<BlockPos> boundary) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boundary.add(origin);
        for (Direction dir : Direction.values()) {
            BlockPos next = origin.relative(dir);
            if (isPassable(level, next)) {
                if (interior.add(next)) {
                    queue.add(next);
                }
            } else {
                boundary.add(next);
            }
        }
        if (interior.isEmpty()) {
            return Msg.tr("building.reason.no_air");
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (!level.isLoaded(pos)) {
                return Msg.tr("building.reason.unloaded");
            }
            if (pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())) {
                return Msg.tr("building.reason.no_roof", pos.toShortString());
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (interior.contains(next) || boundary.contains(next)) {
                    continue;
                }
                if (isPassable(level, next)) {
                    interior.add(next);
                    if (interior.size() > maxVolume) {
                        return Msg.tr("building.reason.too_big", maxVolume);
                    }
                    queue.add(next);
                } else {
                    boundary.add(next);
                }
            }
        }
        return null;
    }

    /** Air et blocs sans collision ; portes, trappes et portillons comptent comme murs. */
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

    private static int countFarmland(ServerLevel level, BlockPos origin) {
        int count = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (level.isLoaded(m) && level.getBlockState(m).getBlock() instanceof FarmBlock) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
