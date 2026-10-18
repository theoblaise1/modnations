package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Onglet créatif unique du mod. */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SovereignFrontier.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Msg.tr("creative_tab"))
                    .icon(() -> ModItems.TREASURY.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModItems.TREASURY.get());
                        output.accept(ModItems.NATIONAL_CHEST.get());
                        output.accept(ModItems.ARMORED_PLATING.get());
                        output.accept(ModItems.CIVIL_REGISTRY.get());
                        output.accept(ModItems.ENGINEERING_STATION.get());
                        output.accept(ModItems.CABLE.get());
                        output.accept(ModItems.THERMAL_GENERATOR.get());
                        output.accept(ModItems.SOLAR_PANEL.get());
                        output.accept(ModItems.ENERGY_CELL.get());
                        output.accept(ModItems.GAS_DRILL.get());
                        output.accept(ModItems.REACTOR_CONTROLLER.get());
                        output.accept(ModItems.REACTOR_CASING.get());
                        output.accept(ModItems.FUEL_CELL.get());
                        output.accept(ModItems.HEAT_SINK.get());
                        output.accept(ModItems.ENRICHED_FUEL.get());
                        output.accept(ModItems.GAS_BOTTLE.get());
                        output.accept(ModItems.FULL_GAS_BOTTLE.get());
                        output.accept(ModItems.CIRCUIT.get());
                        output.accept(ModItems.TERMINAL.get());
                        output.accept(ModItems.OUTPOST_COUNTER.get());
                        output.accept(ModItems.CRATE_COMMUNE.get());
                        output.accept(ModItems.CRATE_MILITAIRE.get());
                        output.accept(ModItems.CRATE_INDUSTRIELLE.get());
                        output.accept(ModItems.CRATE_SPATIALE.get());
                        output.accept(ModItems.KEY_COMMUNE.get());
                        output.accept(ModItems.KEY_MILITAIRE.get());
                        output.accept(ModItems.KEY_INDUSTRIELLE.get());
                        output.accept(ModItems.KEY_SPATIALE.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
