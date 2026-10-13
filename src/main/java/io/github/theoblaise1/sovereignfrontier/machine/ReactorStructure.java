package io.github.theoblaise1.sovereignfrontier.machine;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Validation du réacteur multibloc 5×5×5 : coque de Caissons de réacteur, Contrôleur au centre
 * d'une face (façade vers l'extérieur), intérieur 3×3×3 de Cellules de combustible, de
 * Dissipateurs thermiques ou d'air (au moins une cellule). 125 lectures, à la demande uniquement.
 */
public final class ReactorStructure {
    /** {@code valid} faux : {@code problem} donne la position fautive (ou null si aucune cellule). */
    public record Result(boolean valid, int cells, int sinks, BlockPos problem) {
    }

    private ReactorStructure() {
    }

    public static Result validate(Level level, BlockPos controller, Direction facing) {
        BlockPos center = controller.relative(facing.getOpposite(), 2);
        int cells = 0;
        int sinks = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (pos.equals(controller)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    boolean shell = Math.abs(dx) == 2 || Math.abs(dy) == 2 || Math.abs(dz) == 2;
                    if (shell) {
                        if (!state.is(ModBlocks.REACTOR_CASING.get())) {
                            return new Result(false, cells, sinks, pos);
                        }
                    } else if (state.is(ModBlocks.FUEL_CELL.get())) {
                        cells++;
                    } else if (state.is(ModBlocks.HEAT_SINK.get())) {
                        sinks++;
                    } else if (!state.isAir()) {
                        return new Result(false, cells, sinks, pos);
                    }
                }
            }
        }
        return new Result(cells > 0, cells, sinks, null);
    }
}
