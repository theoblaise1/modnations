package io.github.theoblaise1.sovereignfrontier;

import io.github.theoblaise1.sovereignfrontier.dungeon.DungeonBossEntity;
import io.github.theoblaise1.sovereignfrontier.network.ModPayloads;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantEntity;
import io.github.theoblaise1.sovereignfrontier.registry.ModArmorMaterials;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.registry.ModMenus;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModCreativeTabs;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

/**
 * Point d'entrée commun (client et serveur dédié). Les registres sont centralisés dans
 * {@code registry/}, les événements de jeu s'abonnent via {@code @EventBusSubscriber}
 * et le code exclusivement client vit dans {@code client/} ({@code SovereignFrontierClient}).
 */
@Mod(SovereignFrontier.MODID)
public final class SovereignFrontier {
    public static final String MODID = "sovereignfrontier";

    public SovereignFrontier(IEventBus modBus, ModContainer container) {
        ModArmorMaterials.ARMOR_MATERIALS.register(modBus);
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        ModAttachments.ATTACHMENTS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModMenus.MENUS.register(modBus);
        ModEntityTypes.ENTITY_TYPES.register(modBus);
        modBus.addListener(EntityAttributeCreationEvent.class, event -> {
            event.put(ModEntityTypes.MERCHANT.get(), MerchantEntity.createAttributes().build());
            event.put(ModEntityTypes.DUNGEON_BOSS.get(), DungeonBossEntity.createAttributes().build());
        });
        modBus.addListener(ModPayloads::register);
        container.registerConfig(ModConfig.Type.SERVER, SFConfig.SPEC);
    }
}
