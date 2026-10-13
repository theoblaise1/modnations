package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Enregistrement des paquets (Payload system NeoForge 1.21). Les gestionnaires s'exécutent sur
 * le thread principal (comportement par défaut du registrar) et revalident tout côté serveur.
 * Écouteur ajouté explicitement sur le bus du mod dans {@code SovereignFrontier}.
 */
public final class ModPayloads {
    public static final String PROTOCOL_VERSION = "1";

    private ModPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        // Serveur → client. Le corps de la lambda (et donc la classe cliente) n'est résolu
        // qu'à l'exécution, uniquement sur un client physique : sûr pour un serveur dédié.
        registrar.playToClient(NationSnapshotS2C.TYPE, NationSnapshotS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleSnapshot(payload, context));
        registrar.playToClient(ActionFeedbackS2C.TYPE, ActionFeedbackS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleFeedback(payload, context));
        registrar.playToClient(ResearchDefinitionsS2C.TYPE, ResearchDefinitionsS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleResearchDefinitions(payload, context));

        registrar.playToServer(RequestNationDataC2S.TYPE, RequestNationDataC2S.STREAM_CODEC, ModPayloads::onRequest);
        registrar.playToServer(ClaimChunkC2S.TYPE, ClaimChunkC2S.STREAM_CODEC, ModPayloads::onClaim);
        registrar.playToServer(TreasuryTransferC2S.TYPE, TreasuryTransferC2S.STREAM_CODEC, ModPayloads::onTreasury);
        registrar.playToServer(SetRolePermissionC2S.TYPE, SetRolePermissionC2S.STREAM_CODEC, ModPayloads::onSetPermission);
        registrar.playToClient(EngineeringRecipesS2C.TYPE, EngineeringRecipesS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleEngineeringRecipes(payload, context));
        registrar.playToClient(OpenEngineeringS2C.TYPE, OpenEngineeringS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleOpenEngineering(payload, context));
        registrar.playToServer(EngineeringCraftC2S.TYPE, EngineeringCraftC2S.STREAM_CODEC, ModPayloads::onEngineeringCraft);
        registrar.playToServer(StartResearchC2S.TYPE, StartResearchC2S.STREAM_CODEC, ModPayloads::onStartResearch);
        registrar.playToServer(CancelResearchC2S.TYPE, CancelResearchC2S.STREAM_CODEC, ModPayloads::onCancelResearch);
    }

    private static void onStartResearch(StartResearchC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            reply(player, ResearchManager.start(player, payload.id()));
        }
    }

    private static void onEngineeringCraft(EngineeringCraftC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            reply(player, EngineeringManager.craft(player, payload.id()));
        }
    }

    private static void onCancelResearch(CancelResearchC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            reply(player, ResearchManager.cancel(player));
        }
    }

    private static void onRequest(RequestNationDataC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            if (payload.open()) {
                NationSync.openScreen(player, NationTab.byOrdinal(payload.tab()));
            } else {
                NationSync.refreshPlayer(player, false);
            }
        }
    }

    private static void onClaim(ClaimChunkC2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ChunkPos here = player.chunkPosition();
        if (Math.abs(payload.chunkX() - here.x) > NationSync.MAP_RADIUS || Math.abs(payload.chunkZ() - here.z) > NationSync.MAP_RADIUS) {
            NationSync.refreshPlayer(player, false);
            return;
        }
        ChunkPos target = new ChunkPos(payload.chunkX(), payload.chunkZ());
        ActionResult result = payload.claim()
                ? NationManager.claim(player, player.level().dimension(), target)
                : NationManager.unclaim(player, player.level().dimension(), target);
        reply(player, result);
    }

    private static void onTreasury(TreasuryTransferC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            ActionResult result = payload.deposit()
                    ? NationManager.deposit(player, payload.amount())
                    : NationManager.withdraw(player, payload.amount());
            reply(player, result);
        }
    }

    private static void onSetPermission(SetRolePermissionC2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        NationPermission permission = NationPermission.byOrdinal(payload.permission());
        if (permission == null || payload.role() < 0 || payload.role() >= NationRole.values().length) {
            return;
        }
        reply(player, NationManager.setPermission(player, NationRole.byOrdinal(payload.role()), permission, payload.granted()));
    }

    /**
     * Envoie le résultat dans le chat et dans la barre d'état de l'écran. En cas de succès, {@link NationManager} a déjà
     * rafraîchi tous les membres ; en cas d'échec on renvoie l'état pour resynchroniser l'écran.
     */
    private static void reply(ServerPlayer player, ActionResult result) {
        player.sendSystemMessage(result.message());
        if (NationSync.canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new ActionFeedbackS2C(result.message(), result.success()));
        }
        if (!result.success()) {
            NationSync.refreshPlayer(player, false);
        }
    }
}
