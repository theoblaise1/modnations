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
                        output.accept(ModItems.ROCKET.get());
                        output.accept(ModItems.ROCKET_HULL.get());
                        output.accept(ModItems.ROCKET_ENGINE.get());
                        output.accept(ModItems.ADVANCED_PROPULSOR.get());
                        output.accept(ModItems.CRYOGENIC_MODULE.get());
                        output.accept(ModItems.ROCKET_FUEL.get());
                        output.accept(ModItems.OXYGEN_BOTTLE.get());
                        output.accept(ModItems.REFINERY.get());
                        output.accept(ModItems.OXYGEN_DIFFUSER.get());
                        output.accept(ModItems.GAS_BOILER.get());
                        output.accept(ModItems.NUCLEAR_HEATER.get());
                        output.accept(ModItems.HEATED_CHESTPLATE.get());
                        output.accept(ModItems.TITANIUM_ORE.get());
                        output.accept(ModItems.RAW_TITANIUM.get());
                        output.accept(ModItems.TITANIUM_INGOT.get());
                        output.accept(ModItems.SULFUR_ORE.get());
                        output.accept(ModItems.SULFUR.get());
                        output.accept(ModItems.CRYOGENIC_ORE.get());
                        output.accept(ModItems.CRYOGENIC_CRYSTAL.get());
                        output.accept(ModItems.PISTOL.get());
                        output.accept(ModItems.RIFLE.get());
                        output.accept(ModItems.SNIPER_RIFLE.get());
                        output.accept(ModItems.EXPLOSIVE.get());
                        output.accept(ModItems.AMMO_BASIC.get());
                        output.accept(ModItems.AMMO_PIERCING.get());
                        output.accept(ModItems.AMMO_SULFUR.get());
                        output.accept(ModItems.BALLISTIC_LIGHT_ARMOR.get());
                        output.accept(ModItems.BALLISTIC_HEAVY_ARMOR.get());
                        output.accept(ModItems.HEALING_KIT.get());
                        output.accept(ModItems.DUNGEON_KEY.get());
                        output.accept(ModItems.DUNGEON_ALTAR.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
