// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import net.minecraft.world.item.Item;

/**
 * Explosif lancé du J6 : rayon de 20 blocs, 12 de dégâts aux {@code Monster} dans un rayon de 3 blocs, sans destruction
 * de blocs (docs/conception/J6.md §1.1). Squelette : aucune logique au WP0.
 */
public class ExplosiveItem extends Item {
    public ExplosiveItem(Item.Properties properties) {
        super(properties);
    }
}
