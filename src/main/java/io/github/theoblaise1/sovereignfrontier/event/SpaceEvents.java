package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.space.SpaceRules;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Accès aux planètes (docs/conception/J5.md §3) : garde de voyage vers une planète, minage verrouillé et explosions
 * des minerais spatiaux, lits et ancres de réapparition, bordure souple, secours à la connexion. Les décisions sont
 * dans {@link SpaceRules} ; chaque gestionnaire ne fait que des recherches O(1) (aucun parcours de nations ni de
 * chunks).
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class SpaceEvents {
    private SpaceEvents() {
    }

    /**
     * Toute entité (y compris d'un autre mod) qui entre dans une planète doit porter un permis de voyage, posé par le
     * vol en fusée, ou être un opérateur. Le retour vers une autre dimension n'est jamais gardé.
     */
    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity().level().isClientSide() || SpaceRules.mayTravel(event.getEntity(), event.getDimension())) {
            return;
        }
        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            player.displayClientMessage(Msg.err("space.travel.denied"), true);
        }
    }

    /** Un minerai spatial se casse avec la clé d'extraction de la nation du joueur, dans toute dimension. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        Component reason = SpaceRules.miningBlockReason(player, event.getState());
        if (reason != null) {
            event.setCanceled(true);
            player.displayClientMessage(reason, true);
        }
    }

    /** Les minerais spatiaux sont retirés de la liste des blocs détruits par toute explosion. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> level.getBlockState(pos).is(SpaceRules.SPACE_ORES));
    }

    /** Lits et ancres de réapparition : utilisation annulée sur une planète, sans explosion. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        if (!SpaceRules.refusesRespawnBlock(level, state)) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        boolean bed = state.getBlock() instanceof BedBlock;
        event.getEntity().displayClientMessage(Msg.err(bed ? "space.bed" : "space.anchor"), true);
    }

    /** Connexion : retour sur Terre d'un joueur dont la planète n'existe plus sur le serveur. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpaceRules.onLogin(player);
        }
    }

    /** Déconnexion : le repère de planète est exact au moment de la sauvegarde du joueur. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = player.serverLevel();
            SpaceRules.markPlanet(player, SpaceRules.isSpaceDimension(level.dimension()) ? level.dimension() : null);
        }
    }

    /** Toutes les 20 ticks : repère de planète et bordure souple (une recherche de table pour un joueur terrestre). */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % SpaceRules.CHECK_PERIOD == 0) {
            SpaceRules.tickPlayer(player);
        }
    }
}
