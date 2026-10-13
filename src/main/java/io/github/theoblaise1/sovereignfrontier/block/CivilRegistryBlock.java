package io.github.theoblaise1.sovereignfrontier.block;

import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.CivilBuildings;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * Registre civil : placé dans un bâtiment, un clic droit le fait valider (maison, ferme ou
 * atelier) et l'ajoute à la population de la nation. Le détruire retire le bâtiment.
 */
public class CivilRegistryBlock extends Block {
    public CivilRegistryBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ActionResult result = CivilBuildings.register((ServerPlayer) player, (ServerLevel) level, pos);
        player.sendSystemMessage(result.message());
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            CivilBuildings.onRegistryRemoved(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Msg.info("tooltip.registre_civil"));
    }
}
