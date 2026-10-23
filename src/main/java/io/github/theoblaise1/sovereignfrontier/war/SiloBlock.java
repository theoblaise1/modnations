// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Silo à missiles (J7, docs/conception/J7.md §1.6). Squelette : le tir, le cooldown et la propriété sont au WP3, qui
 * fera aussi de ce bloc un bloc à bloc-entité ({@link SiloBlockEntity}).
 */
public class SiloBlock extends Block {
    public SiloBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
}
