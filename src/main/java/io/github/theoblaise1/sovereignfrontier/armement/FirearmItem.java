// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import net.minecraft.world.item.Item;

/**
 * Arme à feu du J6 (pistolet, fusil, fusil de précision) : tir instantané côté serveur (rayon depuis les yeux), munition
 * et déblocage vérifiés par le lot WP2 (docs/conception/J6.md §1.1, §3). Squelette : aucune logique au WP0.
 */
public class FirearmItem extends Item {
    public FirearmItem(Item.Properties properties) {
        super(properties);
    }
}
