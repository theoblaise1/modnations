// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import net.minecraft.world.item.Item;

/**
 * Munition du J6 (basique, perforante, au soufre) : consommée à chaque tir, multiplicateur de dégâts propre
 * (docs/conception/J6.md §1.2). Squelette : aucune logique au WP0.
 */
public class AmmoItem extends Item {
    public AmmoItem(Item.Properties properties) {
        super(properties);
    }
}
