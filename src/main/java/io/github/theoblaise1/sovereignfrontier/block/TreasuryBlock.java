package io.github.theoblaise1.sovereignfrontier.block;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.TreasurySessions;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
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

/** Trésor national : ouvre l'onglet Trésorerie avec droit de dépôt/retrait (si sur le territoire de sa nation). */
public class TreasuryBlock extends Block implements NationAccessBlock {
    public TreasuryBlock(Properties properties) {
        super(properties);
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
            sp.displayClientMessage(Msg.err("treasury.error.foreign"), true);
            return InteractionResult.CONSUME;
        }
        TreasurySessions.open(sp, GlobalPos.of(level.dimension(), pos));
        NationSync.openScreen(sp, NationTab.TREASURY);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Msg.info("tooltip.tresor_national"));
    }
}
