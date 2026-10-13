package io.github.theoblaise1.sovereignfrontier.client;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import io.github.theoblaise1.sovereignfrontier.client.screen.MachineScreen;
import io.github.theoblaise1.sovereignfrontier.registry.ModMenus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Point d'entrée client : chargé uniquement sur un client physique. */
@OnlyIn(Dist.CLIENT)
@Mod(value = SovereignFrontier.MODID, dist = Dist.CLIENT)
public final class SovereignFrontierClient {

    public SovereignFrontierClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(RegisterKeyMappingsEvent.class, ModKeyMappings::register);
        modBus.addListener(RegisterMenuScreensEvent.class, e -> e.register(ModMenus.MACHINE.get(), MachineScreen::new));
    }
}
