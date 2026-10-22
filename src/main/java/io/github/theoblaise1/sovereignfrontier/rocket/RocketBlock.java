// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import com.mojang.serialization.MapCodec;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Bloc de la fusée (docs/conception/J5.md §4.1) : bloc-entité sans entité de vol. Pose : la nation du poseur devient
 * propriétaire. Clic droit : soute de 9 cases (coffre vanilla d'une rangée) pour les membres de la nation
 * propriétaire ; accroupi : fiche de vol dans le chat et session de 60 s pour {@code /rocket}. Le ticker serveur
 * n'existe que pendant un compte à rebours ({@link #LAUNCHING}), comme le comptoir du J4 : coût nul au repos.
 * <p>
 * Résistance 1 200, non poussable, pioche en fer ; cassable seulement selon {@link RocketProtection}. Cassée, elle
 * lâche sa soute ; déplacée par un vol, elle ne lâche rien.
 */
public class RocketBlock extends BaseEntityBlock {
    /** Vrai pendant un compte à rebours (variante de modèle du lot graphique). */
    public static final BooleanProperty LAUNCHING = BooleanProperty.create("launching");
    public static final MapCodec<RocketBlock> CODEC = simpleCodec(RocketBlock::new);

    public RocketBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LAUNCHING, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LAUNCHING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RocketBlockEntity(pos, state);
    }

    /** Ticker serveur seulement pendant un compte à rebours. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !state.getValue(LAUNCHING)) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.ROCKET.get(), RocketBlockEntity::serverTick);
    }

    /** Pose : la nation du poseur est propriétaire ; sans nation, la fusée est inerte (elle ne vole pas). */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof RocketBlockEntity rocket) {
            Nation nation = NationData.get(serverLevel.getServer()).nationOf(player.getUUID());
            rocket.setOwner(nation == null ? null : nation.id());
            player.displayClientMessage(nation == null ? Msg.warn("rocket.placed.inert") : Msg.ok("rocket.placed", nation.name()), true);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof RocketBlockEntity rocket) {
            Nation nation = RocketSheet.ownerOf(serverLevel, rocket);
            if (!RocketProtection.mayOpenHold(nation, serverPlayer)) {
                serverPlayer.displayClientMessage(nation == null ? Msg.err("rocket.error.inert")
                        : Msg.err("rocket.error.owner", nation.name()), true);
            } else if (serverPlayer.isShiftKeyDown()) {
                RocketSheet.show(serverPlayer, serverLevel, pos, rocket, TimeUtil.now());
            } else if (state.getValue(LAUNCHING)) {
                serverPlayer.displayClientMessage(Msg.warn("rocket.error.sealed"), true);
            } else {
                serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, p) ->
                        new ChestMenu(MenuType.GENERIC_9x1, id, inventory, rocket.hold(), 1), Msg.tr("rocket.hold.title")));
            }
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Retrait du bloc. Hors vol : le compte à rebours s'arrête, les menus de la soute se ferment, le contenu tombe au
     * sol puis la soute est invalidée (aucune duplication par un menu resté ouvert). Déplacé par un vol
     * ({@code moving}) : ni butin ni annulation, la soute a déjà été vidée.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof RocketBlockEntity rocket && !rocket.isMoving()) {
            RocketFlight.onRocketRemoved(serverLevel, pos);
            for (Player viewer : rocket.hold().viewers()) {
                if (viewer instanceof ServerPlayer serverPlayer) {
                    serverPlayer.closeContainer();
                }
            }
            Containers.dropContents(level, pos, rocket.hold());
            rocket.hold().invalidate();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Msg.info("tooltip.fusee"));
    }
}
