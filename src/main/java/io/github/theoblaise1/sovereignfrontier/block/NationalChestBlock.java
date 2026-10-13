package io.github.theoblaise1.sovereignfrontier.block;

import com.mojang.serialization.MapCodec;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * Cœur du coffre national, multibloc : il doit reposer au centre d'un socle 3×3 de Blindage
 * national. L'inventaire (54 cases) appartient à la nation, pas au bloc : tous les coffres
 * nationaux d'une même nation ouvrent le même contenu.
 */
public class NationalChestBlock extends HorizontalDirectionalBlock implements NationAccessBlock {
    public static final MapCodec<NationalChestBlock> CODEC = simpleCodec(NationalChestBlock::new);

    public NationalChestBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** Vérifie le socle 3×3 de blindage sous le cœur. */
    public static boolean isStructureValid(Level level, BlockPos core) {
        BlockPos base = core.below();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.getBlockState(base.offset(dx, 0, dz)).is(ModBlocks.ARMORED_PLATING.get())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ServerPlayer sp = (ServerPlayer) player;
        NationData data = NationData.get(sp.server);
        Nation nation = data.nationOf(sp.getUUID());
        if (nation == null) {
            sp.displayClientMessage(Msg.err("nation.error.no_nation"), true);
            return InteractionResult.CONSUME;
        }
        if (data.ownerAt(level.dimension(), pos) != nation) {
            sp.displayClientMessage(Msg.err("chest.error.foreign"), true);
            return InteractionResult.CONSUME;
        }
        if (!nation.hasPermission(sp.getUUID(), NationPermission.NATIONAL_CHEST)) {
            sp.displayClientMessage(Msg.err("nation.error.no_permission", NationPermission.NATIONAL_CHEST.displayName()), true);
            return InteractionResult.CONSUME;
        }
        if (!isStructureValid(level, pos)) {
            sp.displayClientMessage(Msg.err("chest.error.structure"), true);
            return InteractionResult.CONSUME;
        }
        GlobalPos gp = GlobalPos.of(level.dimension(), pos);
        sp.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new NationalChestMenu(id, inventory, nation.chest(), gp, nation.id()),
                Msg.tr("chest.title", nation.name())));
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Msg.info("tooltip.coffre_national"));
    }
}
