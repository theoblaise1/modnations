// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.armement;

import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import net.minecraft.world.item.Item;

/**
 * Munition du J6 (basique, perforante, au soufre) : consommée à chaque tir, multiplicateur de dégâts propre
 * (docs/conception/J6.md §1.2). Une munition n'est compatible avec une arme que si son déblocage est acquis par la nation
 * du tireur ({@link CombatRules#compatibleAmmo}).
 */
public class AmmoItem extends Item {

    /** Type de munition : clé de déblocage, multiplicateur de dégâts, durée de feu infligée à la cible (ticks, 0 = aucun). */
    public enum Kind {
        BASIC(UnlockKeys.AMMO_BASIC, 1.0F, 0),
        PIERCING(UnlockKeys.AMMO_PIERCING, 1.5F, 0),
        SULFUR(UnlockKeys.AMMO_SULFUR, 1.25F, 80);

        private final String unlockKey;
        private final float damageMultiplier;
        private final int fireTicks;

        Kind(String unlockKey, float damageMultiplier, int fireTicks) {
            this.unlockKey = unlockKey;
            this.damageMultiplier = damageMultiplier;
            this.fireTicks = fireTicks;
        }

        public String unlockKey() {
            return unlockKey;
        }

        public float damageMultiplier() {
            return damageMultiplier;
        }

        public int fireTicks() {
            return fireTicks;
        }
    }

    public AmmoItem(Item.Properties properties) {
        super(properties);
    }

    /**
     * Type de cette munition. Le constructeur ne prend que les propriétés (ModItems inchangé pour les munitions) : le type
     * se déduit donc de l'objet enregistré.
     */
    public Kind kind() {
        if (this == ModItems.AMMO_PIERCING.get()) {
            return Kind.PIERCING;
        }
        if (this == ModItems.AMMO_SULFUR.get()) {
            return Kind.SULFUR;
        }
        return Kind.BASIC;
    }
}
