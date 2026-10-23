// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloc-entité du silo à missiles (J7, docs/conception/J7.md §1.6). Squelette : propriétaire et file de tir sont au
 * WP3 (le cooldown reste dans {@link WarData}).
 */
public class SiloBlockEntity extends BlockEntity {
    public SiloBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SILO_MISSILES.get(), pos, state);
    }
}
