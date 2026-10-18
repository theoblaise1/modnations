// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.reward;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bloc-entité commun aux quatre caisses : rien de persistant (objet phare et durée d'animation côté client).
 * Ébauche du socle.
 */
public class CrateBlockEntity extends BlockEntity {
    public CrateBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CRATE.get(), pos, state);
    }
}
