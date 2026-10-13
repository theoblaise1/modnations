package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre des blocs-entités. */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SovereignFrontier.MODID);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineBlockEntity>> MACHINE =
            BLOCK_ENTITIES.register("machine", () -> BlockEntityType.Builder.of(MachineBlockEntity::new,
                    ModBlocks.THERMAL_GENERATOR.get(), ModBlocks.SOLAR_PANEL.get(), ModBlocks.ENERGY_CELL.get(),
                    ModBlocks.GAS_DRILL.get(), ModBlocks.REACTOR_CONTROLLER.get()).build(null));

    private ModBlockEntities() {
    }
}
