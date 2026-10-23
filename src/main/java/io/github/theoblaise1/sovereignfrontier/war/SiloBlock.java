// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.war.missile.SiloRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

import javax.annotation.Nullable;

/**
 * Silo à missiles (J7, docs/conception/J7.md §1.6, D12). Un silo appartient à la nation du territoire où il est posé,
 * et une nation n'en a qu'un ({@link SiloRegistry}). Clic droit avec un missile guidé ou une ogive lourde : chargement
 * (pas d'écran au J7) ; clic droit à main vide : rien. Le tir passe par {@code /nation missile fire}.
 * <p>
 * La limite se teste à la pose ({@link #onPlace}) ; l'enregistrement se fait à {@link #setPlacedBy}. Un silo posé hors
 * territoire, ou hors de l'Overworld, reste inerte : aucune nation ne peut le tirer.
 */
@EventBusSubscriber(modid = SovereignFrontier.MODID)
public class SiloBlock extends Block implements EntityBlock {
    public SiloBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SiloBlockEntity(pos, state);
    }

    /** Pose refusée si la nation territoriale a déjà un silo vivant (message {@code missile.error.silo_limit}). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)
                || !event.getPlacedBlock().is(ModBlocks.SILO_MISSILES.get())
                || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        Nation owner = NationData.get(level.getServer()).ownerAt(level.dimension(), event.getPos());
        if (owner != null && SiloRegistry.get(level.getServer()).liveSiloOf(level, owner.id()) != null) {
            event.setCanceled(true);
            player.displayClientMessage(Msg.err("missile.error.silo_limit"), true);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof SiloBlockEntity silo) {
            Nation owner = NationData.get(serverLevel.getServer()).ownerAt(serverLevel.dimension(), pos);
            boolean registered = owner != null && serverLevel.dimension().equals(Level.OVERWORLD)
                    && SiloRegistry.get(serverLevel.getServer()).register(serverLevel, owner.id(), pos);
            silo.setOwner(registered ? owner.id() : null);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            SiloRegistry.get(serverLevel.getServer()).removeAt(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Chargement d'un missile guidé ou d'une ogive lourde tenue en main ; les autres objets suivent leur usage normal. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        boolean guide = stack.is(ModItems.MISSILE_GUIDE.get());
        if (!guide && !stack.is(ModItems.OGIVE_LOURDE.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.FAIL;
        }
        Nation nation = NationData.get(serverPlayer.server).nationOf(player.getUUID());
        if (nation == null) {
            serverPlayer.displayClientMessage(Msg.err("nation.error.no_nation"), true);
            return ItemInteractionResult.FAIL;
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.FIRE_MISSILE)) {
            serverPlayer.displayClientMessage(Msg.err("missile.error.no_permission"), true);
            return ItemInteractionResult.FAIL;
        }
        if (!(level.getBlockEntity(pos) instanceof SiloBlockEntity silo) || !nation.id().equals(silo.owner())) {
            serverPlayer.displayClientMessage(Msg.err("missile.error.no_silo"), true);
            return ItemInteractionResult.FAIL;
        }
        // Silo plein : refus sans message (aucune clé de traduction dédiée au J7).
        int accepted = guide ? silo.loadMissiles(stack.getCount()) : silo.loadWarheads(stack.getCount());
        if (accepted == 0) {
            return ItemInteractionResult.FAIL;
        }
        stack.shrink(accepted);
        return ItemInteractionResult.SUCCESS;
    }
}
