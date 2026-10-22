package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.outpost.ComptoirBlockEntity;
import io.github.theoblaise1.sovereignfrontier.reward.CrateBlockEntity;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlockEntity;
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
                    ModBlocks.GAS_DRILL.get(), ModBlocks.REACTOR_CONTROLLER.get(), ModBlocks.REFINERY.get(),
                    ModBlocks.OXYGEN_DIFFUSER.get(), ModBlocks.GAS_BOILER.get(), ModBlocks.NUCLEAR_HEATER.get()).build(null));

    /** Fusée (J5) : propriétaire, départ terrestre, derniers décollages planétaires et soute de 9 cases. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RocketBlockEntity>> ROCKET =
            BLOCK_ENTITIES.register("fusee", () -> BlockEntityType.Builder.of(RocketBlockEntity::new,
                    ModBlocks.ROCKET.get()).build(null));

    /** Comptoir d'avant-poste : pool du gabarit et identifiant de l'avant-poste lié. */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComptoirBlockEntity>> COMPTOIR =
            BLOCK_ENTITIES.register("comptoir", () -> BlockEntityType.Builder.of(ComptoirBlockEntity::new,
                    ModBlocks.OUTPOST_COUNTER.get()).build(null));

    /** Un seul type pour les quatre caisses (animation d'ouverture). */
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrateBlockEntity>> CRATE =
            BLOCK_ENTITIES.register("caisse", () -> BlockEntityType.Builder.of(CrateBlockEntity::new,
                    ModBlocks.CRATE_COMMUNE.get(), ModBlocks.CRATE_MILITAIRE.get(), ModBlocks.CRATE_INDUSTRIELLE.get(),
                    ModBlocks.CRATE_SPATIALE.get()).build(null));

    private ModBlockEntities() {
    }
}
