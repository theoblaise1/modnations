package io.github.theoblaise1.sovereignfrontier.event;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.command.TradeCommand;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.outpost.MarketRules;
import io.github.theoblaise1.sovereignfrontier.outpost.MerchantRegistry;
import io.github.theoblaise1.sovereignfrontier.outpost.Outpost;
import io.github.theoblaise1.sovereignfrontier.outpost.OutpostData;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Avant-postes et marchands (J4) : compteur de ticks des rotations, chargement et publication des pools, commande
 * {@code /trade}, protection de la boîte (casse, pose, explosions), griefs (crochet J7) et arrêt. Chaque gestionnaire
 * ne fait que des recherches O(1) dans l'index par chunk des avant-postes.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public final class OutpostEvents {
    private OutpostEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        OutpostData.get(event.getServer()).tick();
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MerchantRegistry.ReloadListener());
    }

    /** Les tags sont liés : validation (objets interdits, écart global) puis publication atomique des pools. */
    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            MerchantRegistry.publishCandidate();
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TradeCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MerchantRegistry.clear();
    }

    // ------------------------------------------------------------------ protection de la boîte

    /** Joueur soumis à la protection : en survie ou aventure, sans {@code /sfadmin bypass}. */
    private static boolean restricted(Player player) {
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }
        return !(player instanceof ServerPlayer sp && AdminBypass.isActive(sp));
    }

    private static boolean protectedBox(Level level, BlockPos pos) {
        return MarketRules.protectionEnabled() && level instanceof ServerLevel serverLevel
                && OutpostData.get(serverLevel.getServer()).boxAt(serverLevel.dimension(), pos) != null;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && restricted(event.getPlayer()) && protectedBox(level, event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Msg.err("outpost.protect.denied"), true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level) || !(event.getEntity() instanceof Player player) || !restricted(player)) {
            return;
        }
        List<BlockPos> positions = new ArrayList<>();
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            for (BlockSnapshot snapshot : multi.getReplacedBlockSnapshots()) {
                positions.add(snapshot.getPos());
            }
        } else {
            positions.add(event.getPos());
        }
        for (BlockPos pos : positions) {
            if (protectedBox(level, pos)) {
                event.setCanceled(true);
                player.displayClientMessage(Msg.err("outpost.protect.denied"), true);
                return;
            }
        }
    }

    /** Les explosions ne détruisent aucun bloc de la boîte (une recherche d'index par bloc touché). */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!MarketRules.protectionEnabled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        OutpostData data = OutpostData.get(level.getServer());
        event.getAffectedBlocks().removeIf(pos -> data.boxAt(level.dimension(), pos) != null);
    }

    // ------------------------------------------------------------------ griefs (crochet J7)

    /**
     * Un membre d'une nation ouvre un conteneur à butin non encore pillé ({@code getLootTable() != null}) dans la boîte
     * d'un avant-poste : {@code grievances[nation] += 1}. Aucun effet de jeu au J4 ({@code /sfadmin outpost info}).
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)
                || player.isSpectator() || player.isSecondaryUseActive() && !player.getMainHandItem().isEmpty()) {
            return;
        }
        if (!(level.getBlockEntity(event.getPos()) instanceof RandomizableContainer container) || container.getLootTable() == null) {
            return;
        }
        recordLooting(level, event.getPos(), player);
    }

    /** Enregistre un pillage si {@code pos} est dans la boîte d'un avant-poste et le joueur membre d'une nation. */
    public static boolean recordLooting(ServerLevel level, BlockPos pos, Entity looter) {
        OutpostData data = OutpostData.get(level.getServer());
        Outpost outpost = data.boxAt(level.dimension(), pos);
        Nation nation = outpost == null ? null : NationData.get(level.getServer()).nationOf(looter.getUUID());
        if (nation == null) {
            return false;
        }
        outpost.recordGrievance(nation.id(), TimeUtil.now());
        data.setDirty();
        return true;
    }
}
