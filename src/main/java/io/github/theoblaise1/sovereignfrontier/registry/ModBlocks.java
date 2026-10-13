package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.block.CivilRegistryBlock;
import io.github.theoblaise1.sovereignfrontier.block.NationalChestBlock;
import io.github.theoblaise1.sovereignfrontier.block.TreasuryBlock;
import io.github.theoblaise1.sovereignfrontier.energy.CableBlock;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringStationBlock;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlock;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
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

    // ------------------------------------------------------------------ J3 : énergie et machines

    public static final DeferredBlock<CableBlock> CABLE = BLOCKS.registerBlock("cable_energie", CableBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(0.5F).sound(SoundType.COPPER).noOcclusion());

    public static final DeferredBlock<MachineBlock> THERMAL_GENERATOR = machine("generateur_thermique", MachineKind.THERMAL);
    public static final DeferredBlock<MachineBlock> SOLAR_PANEL = machine("panneau_solaire", MachineKind.SOLAR);
    public static final DeferredBlock<MachineBlock> ENERGY_CELL = machine("cellule_energie", MachineKind.BATTERY);
    public static final DeferredBlock<MachineBlock> GAS_DRILL = machine("foreuse_gaz", MachineKind.GAS_DRILL);
    public static final DeferredBlock<MachineBlock> REACTOR_CONTROLLER = machine("controleur_reacteur", MachineKind.REACTOR);

    public static final DeferredBlock<Block> REACTOR_CASING = BLOCKS.registerSimpleBlock("caisson_reacteur", reinforced());
    public static final DeferredBlock<Block> FUEL_CELL = BLOCKS.registerSimpleBlock("cellule_combustible", reinforced());
    public static final DeferredBlock<Block> HEAT_SINK = BLOCKS.registerSimpleBlock("dissipateur_thermique", reinforced());

    public static final DeferredBlock<EngineeringStationBlock> ENGINEERING_STATION = BLOCKS.registerBlock(
            "station_ingenierie", EngineeringStationBlock::new, reinforced());

    private static DeferredBlock<MachineBlock> machine(String id, MachineKind kind) {
        return BLOCKS.registerBlock(id, props -> new MachineBlock(kind, props), BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL).strength(4.0F, 6.0F).requiresCorrectToolForDrops().sound(SoundType.METAL));
    }

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
