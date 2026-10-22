// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.dungeon;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

/**
 * Gardien du donjon (J6) : zombi renforcé, invoqué par l'autel (docs/conception/J6.md §1.3). Rendu vanilla du zombi.
 * Squelette : attributs seulement ; la logique (récompense, persistance, barre de boss) arrive au WP1.
 */
public class DungeonBossEntity extends Zombie {
    public DungeonBossEntity(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    /** 200 PV, armure 6, dégâts 8, résistance au recul 0,5 (par-dessus les attributs du zombi vanilla). */
    public static AttributeSupplier.Builder createAttributes() {
        return Zombie.createAttributes()
                .add(Attributes.MAX_HEALTH, 200.0D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.ATTACK_DAMAGE, 8.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5D);
    }
}
