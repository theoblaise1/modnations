package io.github.theoblaise1.sovereignfrontier.network;

import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.Sector;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketManager;
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
    public static final String PROTOCOL_VERSION = "4";

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

        // J4 : économie (marchands, contrats, entreprises, caisses)
        registrar.playToClient(TradeViewS2C.TYPE, TradeViewS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleTradeView(payload, context));
        registrar.playToClient(EconomyViewS2C.TYPE, EconomyViewS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleEconomyView(payload, context));
        registrar.playToClient(CratePreviewS2C.TYPE, CratePreviewS2C.STREAM_CODEC,
                (payload, context) -> io.github.theoblaise1.sovereignfrontier.client.ClientPayloadHandler.handleCratePreview(payload, context));
        registrar.playToServer(TradeActionC2S.TYPE, TradeActionC2S.STREAM_CODEC, ModPayloads::onTradeAction);
        registrar.playToServer(RequestEconomyC2S.TYPE, RequestEconomyC2S.STREAM_CODEC, ModPayloads::onRequestEconomy);
        registrar.playToServer(ContractActionC2S.TYPE, ContractActionC2S.STREAM_CODEC, ModPayloads::onContractAction);
        registrar.playToServer(EnterpriseAssignC2S.TYPE, EnterpriseAssignC2S.STREAM_CODEC, ModPayloads::onEnterpriseAssign);
    }

    // ------------------------------------------------------------------ J4 : économie

    private static void onTradeAction(TradeActionC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)
                && payload.lots() >= 1 && payload.lots() <= TradeActionC2S.MAX_LOTS) {
            reply(player, MarketManager.handleTrade(player, payload));
        }
    }

    /** Débit limité dans {@link EconomyNetwork#request}. */
    private static void onRequestEconomy(RequestEconomyC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            EconomyNetwork.request(player);
        }
    }

    private static void onContractAction(ContractActionC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)
                && ContractActionC2S.isValid(payload.action())) {
            replyEconomy(player, ContractManager.handleAction(player, payload.action(), payload.id()));
        }
    }

    private static void onEnterpriseAssign(EnterpriseAssignC2S payload, IPayloadContext context) {
        Sector sector = Sector.byOrdinal(payload.sector());
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player) && sector != null
                && payload.workers() >= 0 && payload.workers() <= EnterpriseAssignC2S.MAX_WORKERS) {
            replyEconomy(player, EnterpriseManager.assign(player, sector, payload.workers()));
        }
    }

    /** Comme {@link #reply}, et en cas d'échec la vue Économie est renvoyée pour resynchroniser l'onglet. */
    private static void replyEconomy(ServerPlayer player, ActionResult result) {
        reply(player, result);
        if (!result.success()) {
            EconomyNetwork.request(player);
        }
    }

    // Les paquets qui modifient la nation passent par NationSync.allowMutation : au-delà du plafond, ils sont ignorés.

    private static void onStartResearch(StartResearchC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)) {
            reply(player, ResearchManager.start(player, payload.id()));
        }
    }

    private static void onEngineeringCraft(EngineeringCraftC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)) {
            reply(player, EngineeringManager.craft(player, payload.id()));
        }
    }

    private static void onCancelResearch(CancelResearchC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)) {
            reply(player, ResearchManager.cancel(player, payload.id(), payload.startMs()));
        }
    }

    /** Quelques octets suffisent à exiger un instantané complet : débit limité par joueur. */
    private static void onRequest(RequestNationDataC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            NationSync.request(player, payload.open(), NationTab.byOrdinal(payload.tab()));
        }
    }

    private static void onClaim(ClaimChunkC2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !NationSync.allowMutation(player)) {
            return;
        }
        ChunkPos here = player.chunkPosition();
        // En long : la différence de deux int peut déborder (Math.abs(Integer.MIN_VALUE) reste négatif).
        if (Math.abs((long) payload.chunkX() - here.x) > NationSync.MAP_RADIUS
                || Math.abs((long) payload.chunkZ() - here.z) > NationSync.MAP_RADIUS) {
            NationSync.request(player, false, NationTab.TERRITORY);
            return;
        }
        ChunkPos target = new ChunkPos(payload.chunkX(), payload.chunkZ());
        ActionResult result = payload.claim()
                ? NationManager.claim(player, player.level().dimension(), target)
                : NationManager.unclaim(player, player.level().dimension(), target);
        reply(player, result);
    }

    private static void onTreasury(TreasuryTransferC2S payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player && NationSync.allowMutation(player)) {
            ActionResult result = payload.deposit()
                    ? NationManager.deposit(player, payload.amount())
                    : NationManager.withdraw(player, payload.amount());
            reply(player, result);
        }
    }

    private static void onSetPermission(SetRolePermissionC2S payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || !NationSync.allowMutation(player)) {
            return;
        }
        NationPermission permission = NationPermission.byOrdinal(payload.permission());
        if (permission == null || payload.role() < 0 || payload.role() >= NationRole.values().length) {
            return;
        }
        reply(player, NationManager.setPermission(player, NationRole.byOrdinal(payload.role()), permission, payload.granted()));
    }

    /**
     * Envoie le résultat dans le chat et dans la barre d'état de l'écran ouvert ({@code FeedbackScreen}). En cas de
     * succès, l'appelant a déjà rafraîchi ce qui doit l'être ; en cas d'échec on renvoie l'état national pour
     * resynchroniser l'écran (débit limité). Public : réutilisé par les systèmes J4.
     */
    public static void reply(ServerPlayer player, ActionResult result) {
        player.sendSystemMessage(result.message());
        if (NationSync.canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new ActionFeedbackS2C(result.message(), result.success()));
        }
        if (!result.success()) {
            NationSync.request(player, false, NationTab.OVERVIEW);
        }
    }
}
