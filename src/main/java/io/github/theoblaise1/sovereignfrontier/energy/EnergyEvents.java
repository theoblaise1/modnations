package io.github.theoblaise1.sovereignfrontier.energy;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Chargement des chunks : un réseau d'énergie resté incomplet parce qu'un chunk était déchargé est recalculé dès son retour. */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class EnergyEvents {
    private EnergyEvents() {
    }

    /** Ne fait qu'un retrait dans le cache : le recalcul a lieu au parcours suivant, une fois le chunk complet. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            EnergyNetworks.chunkLoaded(level.dimension(), event.getChunk().getPos().toLong());
        }
    }
}
