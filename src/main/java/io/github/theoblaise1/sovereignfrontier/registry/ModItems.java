package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre centralisé des objets (pour l'instant : les objets-blocs du jalon J1). */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SovereignFrontier.MODID);

    public static final DeferredItem<BlockItem> TREASURY = ITEMS.registerSimpleBlockItem(ModBlocks.TREASURY);
    public static final DeferredItem<BlockItem> NATIONAL_CHEST = ITEMS.registerSimpleBlockItem(ModBlocks.NATIONAL_CHEST);
    public static final DeferredItem<BlockItem> ARMORED_PLATING = ITEMS.registerSimpleBlockItem(ModBlocks.ARMORED_PLATING);
    public static final DeferredItem<BlockItem> CIVIL_REGISTRY = ITEMS.registerSimpleBlockItem(ModBlocks.CIVIL_REGISTRY);

    // J3
    public static final DeferredItem<BlockItem> CABLE = ITEMS.registerSimpleBlockItem(ModBlocks.CABLE);
    public static final DeferredItem<BlockItem> THERMAL_GENERATOR = ITEMS.registerSimpleBlockItem(ModBlocks.THERMAL_GENERATOR);
    public static final DeferredItem<BlockItem> SOLAR_PANEL = ITEMS.registerSimpleBlockItem(ModBlocks.SOLAR_PANEL);
    public static final DeferredItem<BlockItem> ENERGY_CELL = ITEMS.registerSimpleBlockItem(ModBlocks.ENERGY_CELL);
    public static final DeferredItem<BlockItem> GAS_DRILL = ITEMS.registerSimpleBlockItem(ModBlocks.GAS_DRILL);
    public static final DeferredItem<BlockItem> REACTOR_CONTROLLER = ITEMS.registerSimpleBlockItem(ModBlocks.REACTOR_CONTROLLER);
    public static final DeferredItem<BlockItem> REACTOR_CASING = ITEMS.registerSimpleBlockItem(ModBlocks.REACTOR_CASING);
    public static final DeferredItem<BlockItem> FUEL_CELL = ITEMS.registerSimpleBlockItem(ModBlocks.FUEL_CELL);
    public static final DeferredItem<BlockItem> HEAT_SINK = ITEMS.registerSimpleBlockItem(ModBlocks.HEAT_SINK);
    public static final DeferredItem<BlockItem> ENGINEERING_STATION = ITEMS.registerSimpleBlockItem(ModBlocks.ENGINEERING_STATION);

    public static final DeferredItem<Item> ENRICHED_FUEL = ITEMS.registerSimpleItem("combustible_enrichi");
    public static final DeferredItem<Item> GAS_BOTTLE = ITEMS.registerSimpleItem("bouteille_gaz");
    public static final DeferredItem<Item> FULL_GAS_BOTTLE = ITEMS.registerSimpleItem("bouteille_gaz_pleine");
    public static final DeferredItem<Item> CIRCUIT = ITEMS.registerSimpleItem("circuit");

    private ModItems() {
    }
}
