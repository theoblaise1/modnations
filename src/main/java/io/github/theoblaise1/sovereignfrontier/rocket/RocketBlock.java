// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import com.mojang.serialization.MapCodec;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Bloc de la fusée (docs/conception/J5.md §4.1). Ébauche du socle : bloc-entité, propriété {@link #LAUNCHING} et codec ;
 * soute, fiche de vol, protection, compte à rebours et ticker (pendant un vol seulement) arrivent avec le lot WP2.
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

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Msg.info("tooltip.fusee"));
    }
}
