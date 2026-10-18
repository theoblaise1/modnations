package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.client.screen.MachineScreen;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.registry.ModMenus;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Point d'entrée client : chargé uniquement sur un client physique. */
@OnlyIn(Dist.CLIENT)
@Mod(value = SovereignFrontier.MODID, dist = Dist.CLIENT)
public final class SovereignFrontierClient {

    public SovereignFrontierClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterKeyMappingsEvent.class, ModKeyMappings::register);
        modBus.addListener(RegisterMenuScreensEvent.class, e -> e.register(ModMenus.MACHINE.get(), MachineScreen::new));
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class, SovereignFrontierClient::registerRenderers);
    }

    /**
     * Toute entité doit avoir un rendu côté client, sinon son affichage plante. Le rendu du marchand (modèle de joueur
     * et textures par variante) arrive avec les ressources graphiques du J4 ; en attente, il est invisible.
     */
    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntityTypes.MERCHANT.get(), NoopRenderer::new);
    }
}
