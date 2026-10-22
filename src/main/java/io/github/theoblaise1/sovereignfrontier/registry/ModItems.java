package io.github.theoblaise1.sovereignfrontier.registry;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registre centralisé des objets : objets-blocs et objets simples. */
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

    // J4 : économie
    public static final DeferredItem<BlockItem> TERMINAL = ITEMS.registerSimpleBlockItem(ModBlocks.TERMINAL);
    public static final DeferredItem<BlockItem> OUTPOST_COUNTER = ITEMS.registerSimpleBlockItem(ModBlocks.OUTPOST_COUNTER);
    public static final DeferredItem<BlockItem> CRATE_COMMUNE = ITEMS.registerSimpleBlockItem(ModBlocks.CRATE_COMMUNE);
    public static final DeferredItem<BlockItem> CRATE_MILITAIRE = ITEMS.registerSimpleBlockItem(ModBlocks.CRATE_MILITAIRE);
    public static final DeferredItem<BlockItem> CRATE_INDUSTRIELLE = ITEMS.registerSimpleBlockItem(ModBlocks.CRATE_INDUSTRIELLE);
    public static final DeferredItem<BlockItem> CRATE_SPATIALE = ITEMS.registerSimpleBlockItem(ModBlocks.CRATE_SPATIALE);

    /** Clés de caisse : piles de 64, aucune recette (vérifié par tools/check_economy.py). */
    public static final DeferredItem<Item> KEY_COMMUNE = ITEMS.registerSimpleItem("cle_commune",
            new Item.Properties().rarity(Rarity.COMMON));
    public static final DeferredItem<Item> KEY_MILITAIRE = ITEMS.registerSimpleItem("cle_militaire",
            new Item.Properties().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> KEY_INDUSTRIELLE = ITEMS.registerSimpleItem("cle_industrielle",
            new Item.Properties().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> KEY_SPATIALE = ITEMS.registerSimpleItem("cle_spatiale",
            new Item.Properties().rarity(Rarity.RARE));

    // J5 : espace (sans modèle ni texture : lot graphique)
    public static final DeferredItem<BlockItem> ROCKET = ITEMS.registerSimpleBlockItem(ModBlocks.ROCKET,
            new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> REFINERY = ITEMS.registerSimpleBlockItem(ModBlocks.REFINERY);
    public static final DeferredItem<BlockItem> OXYGEN_DIFFUSER = ITEMS.registerSimpleBlockItem(ModBlocks.OXYGEN_DIFFUSER);
    public static final DeferredItem<BlockItem> GAS_BOILER = ITEMS.registerSimpleBlockItem(ModBlocks.GAS_BOILER);
    public static final DeferredItem<BlockItem> NUCLEAR_HEATER = ITEMS.registerSimpleBlockItem(ModBlocks.NUCLEAR_HEATER);
    public static final DeferredItem<BlockItem> TITANIUM_ORE = ITEMS.registerSimpleBlockItem(ModBlocks.TITANIUM_ORE);
    public static final DeferredItem<BlockItem> SULFUR_ORE = ITEMS.registerSimpleBlockItem(ModBlocks.SULFUR_ORE);
    public static final DeferredItem<BlockItem> CRYOGENIC_ORE = ITEMS.registerSimpleBlockItem(ModBlocks.CRYOGENIC_ORE);

    /** Carburant raffiné : produit seulement par la Raffinerie (aucune recette). */
    public static final DeferredItem<Item> ROCKET_FUEL = ITEMS.registerSimpleItem("carburant_fusee",
            new Item.Properties().stacksTo(16));
    public static final DeferredItem<Item> OXYGEN_BOTTLE = ITEMS.registerSimpleItem("bouteille_oxygene",
            new Item.Properties().stacksTo(16));
    public static final DeferredItem<Item> ROCKET_HULL = ITEMS.registerSimpleItem("coque_fusee");
    public static final DeferredItem<Item> ROCKET_ENGINE = ITEMS.registerSimpleItem("moteur_fusee");
    public static final DeferredItem<Item> ADVANCED_PROPULSOR = ITEMS.registerSimpleItem("propulseur_avance");
    /**
     * Composant final de fusée (#restreint/fusee_finale) : aucune recette, caisse, vente, entreprise ni /daily ; seule
     * source : le contrat de mission (récompense de contrat, seule exemption).
     */
    public static final DeferredItem<Item> CRYOGENIC_MODULE = ITEMS.registerSimpleItem("module_cryogenique",
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    public static final DeferredItem<Item> RAW_TITANIUM = ITEMS.registerSimpleItem("titane_brut");
    public static final DeferredItem<Item> TITANIUM_INGOT = ITEMS.registerSimpleItem("lingot_titane");
    public static final DeferredItem<Item> SULFUR = ITEMS.registerSimpleItem("soufre");
    public static final DeferredItem<Item> CRYOGENIC_CRYSTAL = ITEMS.registerSimpleItem("cristal_cryogenique");
    /** Protège du froid en brûlant du gaz si la nation du porteur a {@code armor.gas_heated} (WP3). */
    public static final DeferredItem<ArmorItem> HEATED_CHESTPLATE = ITEMS.register("plastron_chauffant",
            () -> new ArmorItem(ModArmorMaterials.HEATED, ArmorItem.Type.CHESTPLATE,
                    new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(20))));

    private ModItems() {
    }
}
