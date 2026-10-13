package io.github.theoblaise1.sovereignfrontier;

import io.github.theoblaise1.sovereignfrontier.network.ModPayloads;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModCreativeTabs;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Point d'entrée commun (client et serveur dédié). Les registres sont centralisés dans
 * {@code registry/}, les événements de jeu s'abonnent via {@code @EventBusSubscriber}
 * et le code exclusivement client vit dans {@code client/} ({@code SovereignFrontierClient}).
 */
@Mod(SovereignFrontier.MODID)
public final class SovereignFrontier {
    public static final String MODID = "sovereignfrontier";

    public SovereignFrontier(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        ModAttachments.ATTACHMENTS.register(modBus);
        modBus.addListener(ModPayloads::register);
        container.registerConfig(ModConfig.Type.SERVER, SFConfig.SPEC);
    }
}
