package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.block.CivilRegistryBlock;
import io.github.theoblaise1.sovereignfrontier.block.NationalChestBlock;
import io.github.theoblaise1.sovereignfrontier.block.TreasuryBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre centralisé des blocs. Les identifiants sont originaux et en français. */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(SovereignFrontier.MODID);

    public static final DeferredBlock<TreasuryBlock> TREASURY = BLOCKS.registerBlock("tresor_national",
            TreasuryBlock::new, reinforced());

    public static final DeferredBlock<NationalChestBlock> NATIONAL_CHEST = BLOCKS.registerBlock("coffre_national",
            NationalChestBlock::new, reinforced());

    /** Blindage : sert de socle 3×3 au coffre national (multibloc). */
    public static final DeferredBlock<Block> ARMORED_PLATING = BLOCKS.registerSimpleBlock("blindage_national",
            reinforced());

    public static final DeferredBlock<CivilRegistryBlock> CIVIL_REGISTRY = BLOCKS.registerBlock("registre_civil",
            CivilRegistryBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.WOOD));

    private static BlockBehaviour.Properties reinforced() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(5.0F, 1200.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.NETHERITE_BLOCK);
    }

    private ModBlocks() {
    }
}
