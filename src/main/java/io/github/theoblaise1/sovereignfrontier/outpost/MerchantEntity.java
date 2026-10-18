// Propriété : WP1
package io.github.theoblaise1.sovereignfrontier.outpost;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Marchand d'avant-poste (invulnérable, immobile, ramené à son poste). Ébauche du socle : seuls le
 * constructeur et les attributs (enregistrés par {@code SovereignFrontier}) sont définitifs.
 */
public class MerchantEntity extends PathfinderMob {
    public MerchantEntity(EntityType<? extends MerchantEntity> type, Level level) {
        super(type, level);
    }

    /** 20 PV, vitesse nulle : le marchand ne se déplace jamais de lui-même. */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D);
    }
}
