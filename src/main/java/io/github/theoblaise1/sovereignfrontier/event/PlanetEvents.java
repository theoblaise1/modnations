package io.github.theoblaise1.sovereignfrontier.event;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.TravelPermits;
import io.github.theoblaise1.sovereignfrontier.space.ZoneIndex;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Socle du J5 : chargement des planètes ({@code data/<ns>/planets/}) et publication à la mise à jour des tags,
 * contrôle des niveaux au démarrage (une planète publiée sans niveau est signalée : c'est normal sur le serveur de
 * GameTests, qui n'a que l'Overworld, le Nether et l'End), vidage des zones et des permis de voyage à l'arrêt et au
 * déchargement d'un niveau. Les systèmes du J5 (accès, fusée, environnement) ont chacun leur propre classe.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class PlanetEvents {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PlanetEvents() {
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlanetRegistry.ReloadListener());
    }

    /**
     * Après tous les écouteurs de rechargement (premier chargement ou {@code /reload}) : tags liés et registre de
     * recherche à jour ; le candidat est validé puis publié. Priorité haute : les planètes sont publiées avant les
     * registres qui s'en servent au même événement (cibles de dimension des contrats). Après un {@code /reload}, les
     * niveaux sont contrôlés aussitôt (au premier chargement, le serveur n'existe pas encore : voir
     * {@link #onServerStarted}).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            return;
        }
        PlanetRegistry.publishCandidate();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && server.isRunning() && server.isSameThread()) {
            checkLevels(server);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        checkLevels(event.getServer());
    }

    /** Une ERREUR par planète publiée dont le niveau n'existe pas : vols vers elle indisponibles. */
    static void checkLevels(MinecraftServer server) {
        for (PlanetDefinition planet : PlanetRegistry.get().all()) {
            if (server.getLevel(planet.dimension()) == null) {
                LOGGER.error("Dimension {} absente : vols vers la planète « {} » indisponibles", planet.dimension().location(),
                        planet.name());
            }
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide()) {
            ZoneIndex.clearLevel(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ZoneIndex.clear();
        TravelPermits.clear();
    }
}
