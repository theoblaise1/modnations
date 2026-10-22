package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre des types d'entités (attributs enregistrés dans {@code SovereignFrontier}). */
public final class ModEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SovereignFrontier.MODID);

    /** Marchand d'avant-poste : invulnérable et immobile (comportement dans {@link MerchantEntity}). */
    public static final DeferredHolder<EntityType<?>, EntityType<MerchantEntity>> MERCHANT = ENTITY_TYPES.register("marchand",
            () -> EntityType.Builder.<MerchantEntity>of(MerchantEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.95F)
                    .clientTrackingRange(10)
                    .build(SovereignFrontier.MODID + ":marchand"));

    private ModEntityTypes() {
    }
}
