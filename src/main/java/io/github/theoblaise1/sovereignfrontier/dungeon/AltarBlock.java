// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Autel du donjon à clé (J6) : avec une clé de donjon dans l'inventaire, il fait apparaître le gardien
 * (docs/conception/J6.md §1.3, §3). Squelette : aucune logique au WP0.
 */
public class AltarBlock extends Block {
    public AltarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }
}
