package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.energy.EnergyRole;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRegistry;
import io.github.theoblaise1.sovereignfrontier.machine.GasData;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlock;
import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.machine.MachineMenu;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationSpace;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.rocket.RocketBlock;
import io.github.theoblaise1.sovereignfrontier.space.ChunkOwners;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.ReturnPoints;
import io.github.theoblaise1.sovereignfrontier.space.SpaceDimensions;
import io.github.theoblaise1.sovereignfrontier.space.SpaceState;
import io.github.theoblaise1.sovereignfrontier.space.TravelPermits;
import io.github.theoblaise1.sovereignfrontier.space.ZoneIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * GameTests du socle J5 (WP0) : registres, objets interdits et exemption des contrats, planètes (chargement strict),
 * réserve de gaz par planète, nouveaux types de machine, attachement joueur, solaire planétaire, persistance du
 * programme spatial de la nation, et index en mémoire (zones, points de retour, permis de voyage).
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class FoundationJ5GameTests {
    private static final String AREA = J5TestUtil.AREA;

    private FoundationJ5GameTests() {
    }

    private static ResourceLocation id(String path) {
        return J5TestUtil.id(path);
    }

    // ------------------------------------------------------------------ registres

    @GameTest(template = AREA)
    public static void j5RegistriesPresent(GameTestHelper helper) {
        List<String> blocks = List.of("fusee", "raffinerie", "diffuseur_oxygene", "chaudiere_gaz", "rechauffeur_nucleaire",
                "minerai_titane", "minerai_soufre", "minerai_cryogenique");
        for (String block : blocks) {
            helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id(block)), "bloc " + block + " enregistré");
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id(block)), "objet-bloc " + block + " enregistré");
        }
        List<String> items = List.of("carburant_fusee", "bouteille_oxygene", "coque_fusee", "moteur_fusee",
                "propulseur_avance", "module_cryogenique", "titane_brut", "lingot_titane", "soufre", "cristal_cryogenique",
                "plastron_chauffant");
        for (String item : items) {
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id(item)), "objet " + item + " enregistré");
        }
        helper.assertTrue(blocks.size() == 8 && items.size() == 11, "8 blocs et 11 objets du J5");

        helper.assertTrue(ModItems.ROCKET.get().getDefaultMaxStackSize() == 1, "fusée en pile de 1");
        helper.assertTrue(ModItems.CRYOGENIC_MODULE.get().getDefaultMaxStackSize() == 1
                && new ItemStack(ModItems.CRYOGENIC_MODULE.get()).getRarity() == Rarity.EPIC, "module : pile de 1, épique");
        helper.assertTrue(ModItems.ROCKET_FUEL.get().getDefaultMaxStackSize() == 16
                && ModItems.OXYGEN_BOTTLE.get().getDefaultMaxStackSize() == 16, "carburant et oxygène en piles de 16");
        ArmorItem chestplate = ModItems.HEATED_CHESTPLATE.get();
        helper.assertTrue(chestplate.getType() == ArmorItem.Type.CHESTPLATE && chestplate.getDefense() == 6
                && chestplate.isValidRepairItem(new ItemStack(chestplate), new ItemStack(ModItems.TITANIUM_INGOT.get())),
                "plastron chauffant : torse, défense 6, réparable au lingot de titane");

        BlockState rocket = ModBlocks.ROCKET.get().defaultBlockState();
        helper.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id("fusee"))
                && ModBlockEntities.ROCKET.get().isValid(rocket), "bloc-entité fusee");
        helper.assertTrue(ModBlocks.ROCKET.get().getExplosionResistance() >= 1200.0F
                && rocket.getPistonPushReaction() == PushReaction.BLOCK
                && rocket.hasProperty(RocketBlock.LAUNCHING) && !rocket.getValue(RocketBlock.LAUNCHING),
                "fusée : résistance 1 200, non poussable, propriété launching");
        helper.assertTrue(rocket.is(BlockTags.WITHER_IMMUNE) && rocket.is(BlockTags.DRAGON_IMMUNE)
                && rocket.is(BlockTags.NEEDS_IRON_TOOL), "fusée : insensible au wither et au dragon, pioche en fer");

        Map<MachineKind, Block> machines = new LinkedHashMap<>();
        machines.put(MachineKind.REFINERY, ModBlocks.REFINERY.get());
        machines.put(MachineKind.OXYGEN_DIFFUSER, ModBlocks.OXYGEN_DIFFUSER.get());
        machines.put(MachineKind.GAS_BOILER, ModBlocks.GAS_BOILER.get());
        machines.put(MachineKind.NUCLEAR_HEATER, ModBlocks.NUCLEAR_HEATER.get());
        for (Map.Entry<MachineKind, Block> e : machines.entrySet()) {
            MachineKind kind = e.getKey();
            helper.assertTrue(e.getValue() instanceof MachineBlock mb && mb.kind() == kind && kind.isSpace(),
                    "bloc de machine " + kind.id());
            helper.assertTrue(ModBlockEntities.MACHINE.get().isValid(e.getValue().defaultBlockState()),
                    "bloc-entité machine valable pour " + kind.id());
            helper.assertTrue(e.getValue().defaultBlockState().is(BlockTags.NEEDS_IRON_TOOL)
                    && e.getValue().defaultBlockState().is(BlockTags.MINEABLE_WITH_PICKAXE), kind.id() + " : pioche en fer");
            helper.assertTrue(MachineKind.byOrdinal(kind.ordinal()) == kind, "ordinal de " + kind.id());
        }
        helper.assertTrue(MachineKind.values().length == 9 && !MachineKind.REACTOR.isSpace(), "9 types de machine (4 du J5)");

        helper.assertTrue(NeoForgeRegistries.ATTACHMENT_TYPES.containsKey(id("space")), "attachement joueur space");

        TagKey<Block> lune = TagKey.create(Registries.BLOCK, id("minerais/lune"));
        TagKey<Block> mars = TagKey.create(Registries.BLOCK, id("minerais/mars"));
        TagKey<Block> glacee = TagKey.create(Registries.BLOCK, id("minerais/planete_glacee"));
        TagKey<Block> all = TagKey.create(Registries.BLOCK, id("minerais_spatiaux"));
        BlockState titanium = ModBlocks.TITANIUM_ORE.get().defaultBlockState();
        BlockState sulfur = ModBlocks.SULFUR_ORE.get().defaultBlockState();
        BlockState cryo = ModBlocks.CRYOGENIC_ORE.get().defaultBlockState();
        helper.assertTrue(titanium.is(lune) && sulfur.is(mars) && cryo.is(glacee) && titanium.is(all) && sulfur.is(all)
                && cryo.is(all), "tags de minerais par planète et minerais_spatiaux");
        helper.assertTrue(titanium.is(BlockTags.NEEDS_IRON_TOOL) && sulfur.is(BlockTags.NEEDS_STONE_TOOL)
                && cryo.is(BlockTags.NEEDS_DIAMOND_TOOL) && titanium.is(BlockTags.MINEABLE_WITH_PICKAXE)
                && sulfur.is(BlockTags.MINEABLE_WITH_PICKAXE) && cryo.is(BlockTags.MINEABLE_WITH_PICKAXE),
                "outils des minerais : fer, pierre, diamant");

        Registry<DamageType> damageTypes = helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        for (String name : List.of("asphyxie", "gel")) {
            Optional<Holder.Reference<DamageType>> type = damageTypes.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, id(name)));
            helper.assertTrue(type.isPresent() && type.get().value().msgId().equals(SovereignFrontier.MODID + "." + name)
                    && type.get().value().exhaustion() == 0.0F, "type de dégâts " + name);
            helper.assertTrue(type.get().is(DamageTypeTags.BYPASSES_ARMOR) && type.get().is(DamageTypeTags.NO_KNOCKBACK),
                    name + " : ignore l'armure, sans recul");
        }

        helper.assertTrue(SFConfig.ROCKET_COUNTDOWN_TICKS.getDefault() == 200 && SFConfig.ROCKET_CREW_MAX.getDefault() == 4
                && SFConfig.OXYGEN_BOTTLE_SECONDS.getDefault() == 120 && SFConfig.NUCLEAR_HEATER_RADIUS.getDefault() == 200
                && SFConfig.ROCKET_PROPULSOR_FACTOR.getDefault() == 0.75D, "configuration [space] : défauts du §1.10");
        helper.assertTrue(UnlockKeys.J5.size() == 16 && UnlockKeys.J5.stream().allMatch(ResearchRegistry.server()::knowsUnlock),
                "16 clés câblées par le J5, toutes accordées par un palier");
        helper.succeed();
    }

    // ------------------------------------------------------------------ objets interdits

    @GameTest(template = AREA)
    public static void finalRocketComponentRestricted(GameTestHelper helper) {
        Item module = ModItems.CRYOGENIC_MODULE.get();
        helper.assertTrue(holder(module).is(RestrictedItems.FINAL_ROCKET) && RestrictedItems.isRestricted(module)
                && RestrictedItems.isBannedFromLoot(module), "module dans #restreint/fusee_finale, #restreint et #interdit_caisses");
        for (Item item : List.of(ModItems.ROCKET.get(), ModItems.REFINERY.get(), ModItems.OXYGEN_DIFFUSER.get(),
                ModItems.GAS_BOILER.get(), ModItems.NUCLEAR_HEATER.get(), ModItems.ADVANCED_PROPULSOR.get(),
                ModItems.HEATED_CHESTPLATE.get())) {
            helper.assertTrue(holder(item).is(RestrictedItems.OFF_ECONOMY) && RestrictedItems.isBannedFromLoot(item)
                    && !RestrictedItems.isRestricted(item), BuiltInRegistries.ITEM.getKey(item) + " dans #hors_economie");
        }
        for (Item item : List.of(ModItems.ROCKET_HULL.get(), ModItems.ROCKET_ENGINE.get(), ModItems.OXYGEN_BOTTLE.get(),
                ModItems.ROCKET_FUEL.get())) {
            helper.assertFalse(RestrictedItems.isBannedFromLoot(item), BuiltInRegistries.ITEM.getKey(item) + " autorisé en caisse");
        }
        helper.assertFalse(RestrictedItems.isBannedFromContracts(module), "module permis dans une récompense de contrat");
        helper.assertTrue(RestrictedItems.isBannedFromContracts(Items.ELYTRA), "élytres interdites dans un contrat");
        helper.assertTrue(RestrictedItems.isBannedFromContracts(ModItems.ROCKET.get()), "fusée interdite dans un contrat");
        helper.assertFalse(RestrictedItems.isBannedFromContracts(Items.DIAMOND), "diamant permis dans un contrat");
        helper.assertTrue(RestrictedItems.allowInContractReward(new ItemStack(module), "GameTest"), "module remis par un contrat");
        helper.assertFalse(RestrictedItems.allowInContractReward(new ItemStack(Items.ELYTRA), "GameTest (refus attendu)"),
                "élytres refusées dans un contrat");
        helper.assertFalse(RestrictedItems.allowInLoot(new ItemStack(module), "GameTest (refus attendu)"),
                "module refusé dans une caisse");

        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(RestrictedItems.auditRecipes(server).isEmpty(), "aucune recette chargée ne produit d'objet restreint");
        for (RecipeHolder<?> recipe : server.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = recipe.value().getResultItem(server.registryAccess());
            } catch (RuntimeException e) {
                continue;
            }
            helper.assertFalse(result.is(ModItems.ROCKET_FUEL.get()) || result.is(module),
                    "la recette " + recipe.id() + " ne produit ni carburant ni module");
        }
        for (EngineeringRecipe recipe : EngineeringRegistry.server().all()) {
            Item result = recipe.definition().result().item();
            helper.assertFalse(result == ModItems.ROCKET_FUEL.get() || result == module,
                    "la recette d'ingénierie " + recipe.id() + " ne produit ni carburant ni module");
        }
        for (PlanetDefinition planet : PlanetRegistry.get().all()) {
            helper.assertTrue(planet.module().map(m -> holder(m).is(RestrictedItems.FINAL_ROCKET)).orElse(true),
                    "module de la planète " + planet.name() + " dans #restreint/fusee_finale");
        }
        helper.succeed();
    }

    private static Holder<Item> holder(Item item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
    }

    // ------------------------------------------------------------------ planètes

    @GameTest(template = AREA)
    public static void planetsLoadedAndStrict(GameTestHelper helper) {
        PlanetRegistry published = PlanetRegistry.get();
        helper.assertTrue(published.size() == 3 && published.names().equals("lune, mars, planete_glacee"),
                "3 planètes publiées (lu : " + published.names() + ")");
        PlanetDefinition lune = published.byName("lune");
        PlanetDefinition mars = published.byName("mars");
        PlanetDefinition glacee = published.byName("planete_glacee");
        helper.assertTrue(lune != null && mars != null && glacee != null, "lune, mars et planete_glacee publiées");
        helper.assertTrue(lune.dimension().equals(SpaceDimensions.LUNE) && published.byDimension(SpaceDimensions.LUNE) == lune
                && published.byId(id("lune")) == lune && SpaceDimensions.isPlanet(SpaceDimensions.MARS)
                && !SpaceDimensions.isPlanet(Level.OVERWORLD), "recherche par dimension, identifiant et nom");
        helper.assertTrue(lune.requires().equals(List.of("rocket", "dimension.lune")) && lune.radius() == 2000
                && lune.environment().oxygen() && !lune.environment().cold() && lune.gasReserve().getAsLong() == 400_000L
                && lune.solarMultiplier() == 1.5D && lune.module().isEmpty(), "Lune : clés, rayon, environnement, gaz, solaire");
        helper.assertTrue(lune.outbound().size() == 2 && lune.outbound().get(0).item() == ModItems.ROCKET_FUEL.get()
                && lune.outbound().get(0).count() == 3 && lune.outbound().get(1).item() == ModItems.FULL_GAS_BOTTLE.get()
                && lune.outbound().get(1).count() == 12 && lune.options(false).get(0).count() == 1
                && lune.returnTrip().get(1).count() == 4, "Lune : carburant d'abord, gaz brut ensuite");
        helper.assertTrue(lune.mining().isPresent() && lune.mining().get().unlock().equals(UnlockKeys.MINING_LUNAR)
                && lune.mining().get().blocks().location().equals(id("minerais/lune")), "Lune : minage verrouillé par mining.lunar");
        helper.assertTrue(mars.outbound().size() == 1 && mars.outbound().get(0).count() == 6 && mars.returnTrip().get(0).count() == 4
                && mars.radius() == 3000 && mars.solarMultiplier() == 0.6D, "Mars : 6 / 4 carburants, rayon 3 000");
        helper.assertTrue(glacee.module().orElse(null) == ModItems.CRYOGENIC_MODULE.get() && glacee.environment().cold()
                && glacee.requires().contains(UnlockKeys.ROCKET_UPGRADE_MODULE) && glacee.outbound().get(0).count() == 10,
                "planète glacée : module, froid, rocket.upgrade_module");
        helper.assertTrue(lune.displayName().getContents() instanceof TranslatableContents t
                && t.getKey().equals("sovereignfrontier.planet.lune"), "nom affiché sovereignfrontier.planet.lune");

        // Analyse stricte : chaque fichier fautif est rejeté en entier.
        String base = J5TestUtil.planetJson(Level.END, 1000L);
        helper.assertTrue(J5TestUtil.parsePlanets("essai", base).size() == 1, "planète de référence acceptée");
        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("champ inconnu", edit(base, o -> o.addProperty("couleur", 1)));
        rejected.put("rayon 100", edit(base, o -> o.addProperty("radius", 100)));
        rejected.put("rayon décimal", edit(base, o -> o.addProperty("radius", 2000.5)));
        rejected.put("option vide", edit(base, o -> o.add("outbound", new JsonArray())));
        rejected.put("5 options", edit(base, o -> {
            JsonArray options = new JsonArray();
            for (String item : List.of("carburant_fusee", "bouteille_gaz_pleine", "bouteille_gaz", "circuit", "soufre")) {
                options.add(JsonParser.parseString("{\"item\": \"sovereignfrontier:" + item + "\", \"count\": 1}"));
            }
            o.add("outbound", options);
        }));
        rejected.put("quantité 65", edit(base, o -> o.getAsJsonArray("return").get(0).getAsJsonObject().addProperty("count", 65)));
        rejected.put("objet inconnu", edit(base, o -> o.getAsJsonArray("return").get(0).getAsJsonObject()
                .addProperty("item", "sovereignfrontier:plutonium")));
        rejected.put("objet en double", edit(base, o -> o.getAsJsonArray("return").get(1).getAsJsonObject()
                .addProperty("item", "sovereignfrontier:carburant_fusee")));
        rejected.put("requires vide", edit(base, o -> o.add("requires", new JsonArray())));
        rejected.put("clé mal formée", edit(base, o -> o.getAsJsonArray("requires").add("Dimension Lune")));
        rejected.put("dimension Overworld", edit(base, o -> o.addProperty("dimension", "minecraft:overworld")));
        rejected.put("gaz négatif", edit(base, o -> o.addProperty("gas_reserve", -1)));
        rejected.put("solaire 5", edit(base, o -> o.addProperty("solar_multiplier", 5)));
        rejected.put("environnement incomplet", edit(base, o -> o.getAsJsonObject("environment").remove("cold")));
        rejected.put("tag de minerai sans #", edit(base, o -> o.getAsJsonObject("mining").addProperty("blocks", "sovereignfrontier:minerais/lune")));
        rejected.put("module inconnu", edit(base, o -> o.addProperty("module", "sovereignfrontier:module_inconnu")));
        for (Map.Entry<String, String> e : rejected.entrySet()) {
            helper.assertTrue(J5TestUtil.parsePlanets("essai", e.getValue()).size() == 0, "rejet à l'analyse : " + e.getKey());
        }
        helper.assertTrue(J5TestUtil.parsePlanets("terre", base).size() == 0, "nom « terre » réservé");
        helper.assertTrue(J5TestUtil.parsePlanets("sous/dossier", base).size() == 0, "sous-dossier refusé");

        // Validations liées aux tags et à la recherche (publication).
        ResearchRegistry research = ResearchRegistry.server();
        Map<String, String> unpublishable = new LinkedHashMap<>();
        unpublishable.put("module hors #restreint/fusee_finale", edit(base, o -> o.addProperty("module", "minecraft:diamond")));
        unpublishable.put("clé inconnue", edit(base, o -> o.getAsJsonArray("requires").add("dimension.venus")));
        unpublishable.put("clé de minage inconnue", edit(base, o -> o.getAsJsonObject("mining").addProperty("unlock", "mining.venus")));
        unpublishable.put("tag de minerai inconnu", edit(base, o -> o.getAsJsonObject("mining")
                .addProperty("blocks", "#sovereignfrontier:minerais/venus")));
        for (Map.Entry<String, String> e : unpublishable.entrySet()) {
            PlanetRegistry parsed = J5TestUtil.parsePlanets("essai", e.getValue());
            helper.assertTrue(parsed.size() == 1 && parsed.validated(research).size() == 0,
                    "rejet à la publication : " + e.getKey());
        }
        helper.assertTrue(J5TestUtil.parsePlanets("essai", edit(base, o -> o.addProperty("module", "sovereignfrontier:module_cryogenique")))
                .validated(research).size() == 1, "module cryogénique accepté");

        // Contraintes d'ensemble : dimension partagée (les deux rejetées), au plus 8 planètes.
        PlanetRegistry shared = J5TestUtil.parsePlanets("alpha", base, "beta", base,
                "gamma", edit(base, o -> o.addProperty("dimension", "minecraft:the_nether"))).validated(research);
        helper.assertTrue(shared.size() == 1 && shared.byName("gamma") != null, "dimension en double : les deux planètes rejetées");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int n = i;
            many.add("essai_" + i);
            many.add(edit(base, o -> o.addProperty("dimension", SovereignFrontier.MODID + ":essai_" + n)));
        }
        PlanetRegistry nine = J5TestUtil.parsePlanets(many.toArray(String[]::new)).validated(research);
        helper.assertTrue(nine.size() == PlanetDefinition.MAX_PLANETS && nine.byName("essai_8") == null,
                "au plus 8 planètes publiées (les premières par identifiant)");
        helper.succeed();
    }

    private static String edit(String json, Consumer<JsonObject> change) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        change.accept(o);
        return o.toString();
    }

    @GameTest(template = AREA, batch = "sf_j5_gas_reserve")
    public static void gasReservePerPlanet(GameTestHelper helper) {
        long config = SFConfig.GAS_PER_DIMENSION.get();
        helper.assertTrue(GasData.reserveFor(SpaceDimensions.LUNE) == 400_000L && GasData.reserveFor(SpaceDimensions.MARS) == 600_000L
                && GasData.reserveFor(SpaceDimensions.PLANETE_GLACEE) == 1_000_000L, "réserves des planètes (gas_reserve)");
        helper.assertTrue(GasData.reserveFor(Level.OVERWORLD) == config && GasData.reserveFor(Level.NETHER) == config,
                "Overworld et Nether : gasPerDimension");
        helper.assertTrue(GasData.get(helper.getLevel()).reserve() == config, "niveau du test : gasPerDimension");

        PlanetDefinition withGas = J5TestUtil.testPlanet(helper, "essai_gaz", 1234L);
        PlanetRegistry noGas = J5TestUtil.parsePlanets("essai_sans_gaz", edit(J5TestUtil.planetJson(Level.NETHER, 0L), o -> o.remove("gas_reserve")))
                .validated(ResearchRegistry.server());
        helper.assertTrue(noGas.size() == 1 && noGas.byName("essai_sans_gaz").gasReserve().isEmpty(), "gas_reserve facultatif");
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
        J5TestUtil.withPlanets(List.of(withGas, noGas.byName("essai_sans_gaz")), () -> {
            helper.assertTrue(GasData.reserveFor(Level.END) == 1234L && GasData.get(end).reserve() == 1234L,
                    "planète de test liée à l'End : sa réserve");
            helper.assertTrue(GasData.reserveFor(Level.NETHER) == config, "planète sans gas_reserve : gasPerDimension");
            helper.assertTrue(GasData.reserveFor(SpaceDimensions.LUNE) == config, "Lune non publiée : gasPerDimension");
        });
        helper.assertTrue(GasData.reserveFor(Level.END) == config && GasData.reserveFor(SpaceDimensions.LUNE) == 400_000L,
                "registre rétabli après le test");
        helper.succeed();
    }

    // ------------------------------------------------------------------ machines

    @GameTest(template = AREA)
    public static void newMachineKindsRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Map<MachineKind, Block> blocks = new LinkedHashMap<>();
        blocks.put(MachineKind.REFINERY, ModBlocks.REFINERY.get());
        blocks.put(MachineKind.OXYGEN_DIFFUSER, ModBlocks.OXYGEN_DIFFUSER.get());
        blocks.put(MachineKind.GAS_BOILER, ModBlocks.GAS_BOILER.get());
        blocks.put(MachineKind.NUCLEAR_HEATER, ModBlocks.NUCLEAR_HEATER.get());
        Map<MachineKind, long[]> expected = Map.of(
                MachineKind.REFINERY, new long[]{4, 50_000, 400},
                MachineKind.OXYGEN_DIFFUSER, new long[]{0, 20_000, 200},
                MachineKind.GAS_BOILER, new long[]{2, 0, 0},
                MachineKind.NUCLEAR_HEATER, new long[]{1, 0, 0});
        int x = 1;
        for (Map.Entry<MachineKind, Block> e : blocks.entrySet()) {
            MachineKind kind = e.getKey();
            BlockPos rel = new BlockPos(x, 2, 1);
            x += 2;
            helper.setBlock(rel, e.getValue().defaultBlockState());
            BlockPos pos = helper.absolutePos(rel);
            BlockState state = level.getBlockState(pos);
            MachineBlockEntity be = (MachineBlockEntity) level.getBlockEntity(pos);
            helper.assertTrue(be != null && be.kind() == kind, "bloc-entité de " + kind.id());
            long[] exp = expected.get(kind);
            helper.assertTrue(kind.slotCount() == exp[0] && be.items().getContainerSize() == exp[0]
                    && be.energyBuffer().capacity() == exp[1] && kind.maxIn() == exp[2] && kind.maxOut() == 0
                    && be.energyRole() == EnergyRole.CONSUMER, kind.id() + " : cases, réserve, débit, consommateur");

            List<ItemStack> contents = new ArrayList<>();
            for (int i = 0; i < kind.slotCount(); i++) {
                ItemStack stack = new ItemStack(i % 2 == 0 ? ModItems.FULL_GAS_BOTTLE.get() : ModItems.GAS_BOTTLE.get(), 3 + i);
                be.items().setItem(i, stack.copy());
                contents.add(stack);
            }
            be.setBurn(1200, 2400);
            be.setFuelTicks(6000);
            be.setProgress(50);
            CompoundTag rawTag = be.saveWithoutMetadata(level.registryAccess());
            MachineBlockEntity rawCopy = new MachineBlockEntity(pos, state);
            rawCopy.loadWithComponents(rawTag, level.registryAccess());
            helper.assertTrue(rawCopy.progress() == 50, kind.id() + " : progression relue du NBT");
            be.energyBuffer().setStored(1234);
            be.tick(level, pos, state);
            helper.assertFalse(be.active(), kind.id() + " : ébauche neutre (inactive)");
            CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
            MachineBlockEntity copy = new MachineBlockEntity(pos, state);
            copy.loadWithComponents(tag, level.registryAccess());
            helper.assertTrue(copy.kind() == kind && copy.burn() == 1200 && copy.burnTotal() == 2400 && copy.fuelTicks() == 6000
                    && copy.energyBuffer().stored() == Math.min(1234, exp[1]), kind.id() + " : NBT relu (combustion, combustible, énergie)");
            helper.assertTrue(copy.progress() == be.progress(), kind.id() + " : progression relue (après un tick)");
            for (int i = 0; i < kind.slotCount(); i++) {
                helper.assertTrue(ItemStack.matches(copy.items().getItem(i), contents.get(i)), kind.id() + " : case " + i + " relue");
            }
            be.items().clearContent();
        }

        ItemStack fullGas = new ItemStack(ModItems.FULL_GAS_BOTTLE.get());
        ItemStack emptyGas = new ItemStack(ModItems.GAS_BOTTLE.get());
        ItemStack blaze = new ItemStack(Items.BLAZE_POWDER);
        ItemStack fuel = new ItemStack(ModItems.ENRICHED_FUEL.get());
        ItemStack rocketFuel = new ItemStack(ModItems.ROCKET_FUEL.get());
        helper.assertTrue(MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_GAS, fullGas)
                && !MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_GAS, emptyGas)
                && MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_BLAZE, blaze)
                && !MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_BLAZE, new ItemStack(Items.BLAZE_ROD))
                && !MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_FUEL_OUT, rocketFuel)
                && !MachineMenu.accepts(MachineKind.REFINERY, MachineKind.REFINERY_EMPTY_OUT, emptyGas),
                "raffinerie : gaz plein et poudre de blaze en entrée, sorties fermées");
        helper.assertTrue(MachineMenu.accepts(MachineKind.GAS_BOILER, MachineKind.BOILER_GAS, fullGas)
                && !MachineMenu.accepts(MachineKind.GAS_BOILER, MachineKind.BOILER_GAS, fuel)
                && !MachineMenu.accepts(MachineKind.GAS_BOILER, MachineKind.BOILER_EMPTY_OUT, emptyGas),
                "chaudière : gaz plein en entrée, sortie fermée");
        helper.assertTrue(MachineMenu.accepts(MachineKind.NUCLEAR_HEATER, MachineKind.HEATER_FUEL, fuel)
                && !MachineMenu.accepts(MachineKind.NUCLEAR_HEATER, MachineKind.HEATER_FUEL, fullGas),
                "réchauffeur : combustible enrichi seulement");
        helper.assertFalse(MachineMenu.accepts(MachineKind.OXYGEN_DIFFUSER, 0, fullGas), "diffuseur : aucune case");
        helper.succeed();
    }

    // ------------------------------------------------------------------ attachement joueur

    @GameTest(template = AREA)
    public static void spaceStateCodecRoundTrip(GameTestHelper helper) {
        SpaceState full = new SpaceState(74, 12, 250, 1_000L, 2_000L, 3_000L,
                Optional.of(GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, -20))), Optional.of(SpaceDimensions.LUNE.location()));
        Tag encoded = SpaceState.CODEC.encodeStart(NbtOps.INSTANCE, full).getOrThrow();
        SpaceState back = SpaceState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        helper.assertTrue(back.equals(full), "aller-retour du codec (lu : " + back + ")");
        helper.assertTrue(encoded instanceof CompoundTag c && c.contains("grace_until") && c.contains("return_point")
                && c.getString("last_planet").equals("sovereignfrontier:lune"), "noms persistés");
        SpaceState empty = SpaceState.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow();
        helper.assertTrue(empty.equals(SpaceState.EMPTY) && empty.returnPoint().isEmpty() && empty.lastPlanet().isEmpty()
                && empty.nextFlight() == 0L, "NBT vide : valeurs par défaut");
        helper.assertTrue(new SpaceState(-5, -1, -2, 0L, 0L, 0L, Optional.empty(), Optional.empty()).equals(SpaceState.EMPTY),
                "compteurs négatifs ramenés à 0");
        SpaceState respawned = full.afterRespawn();
        helper.assertTrue(respawned.oxygen() == 0 && respawned.cold() == 0 && respawned.heat() == 0
                && respawned.nextFlight() == 3_000L && respawned.returnPoint().equals(full.returnPoint())
                && respawned.lastPlanet().equals(full.lastPlanet()), "réapparition : oxygène, froid et charge à 0, le reste gardé");
        helper.assertTrue(full.withOxygen(5).oxygen() == 5 && full.withCold(7).cold() == 7 && full.withHeat(9).heat() == 9
                && full.withNextFlight(42L).nextFlight() == 42L && full.withGraceUntil(43L).graceUntil() == 43L
                && full.withLastLoginGrace(44L).lastLoginGrace() == 44L && full.withReturnPoint(Optional.empty()).returnPoint().isEmpty()
                && full.withLastPlanet(Optional.empty()).lastPlanet().isEmpty() && full.inGrace(999L) && !full.inGrace(1_000L),
                "copies modifiées et acclimatation");

        ServerPlayer player = J5TestUtil.fakePlayer(helper, "sf_j5_etat");
        helper.assertTrue(player.getData(ModAttachments.SPACE).equals(SpaceState.EMPTY), "attachement absent : état vide");
        player.setData(ModAttachments.SPACE, full);
        helper.assertTrue(player.getData(ModAttachments.SPACE).equals(full), "attachement lu après écriture");
        player.setData(ModAttachments.SPACE, SpaceState.EMPTY);
        helper.succeed();
    }

    // ------------------------------------------------------------------ solaire

    @GameTest(template = AREA)
    public static void solarOnPlanetIgnoresEarthRain(GameTestHelper helper) {
        PlanetRegistry planets = PlanetRegistry.get();
        PlanetDefinition lune = planets.byName("lune");
        PlanetDefinition mars = planets.byName("mars");
        PlanetDefinition glacee = planets.byName("planete_glacee");
        helper.assertTrue(lune != null && mars != null && glacee != null, "planètes publiées");
        int base = 8;
        helper.assertTrue(MachineBlockEntity.solarOutput(true, false, true, null, base) == 8
                && MachineBlockEntity.solarOutput(true, true, true, null, base) == 0
                && MachineBlockEntity.solarOutput(false, false, true, null, base) == 0
                && MachineBlockEntity.solarOutput(true, false, false, null, base) == 0, "hors planète : inchangé (pluie, nuit, ciel)");
        helper.assertTrue(MachineBlockEntity.solarOutput(true, true, true, lune, base) == 12
                && MachineBlockEntity.solarOutput(true, false, true, lune, base) == 12, "Lune : × 1,5, pluie terrestre ignorée");
        helper.assertTrue(MachineBlockEntity.solarOutput(true, true, true, mars, base) == 5
                && MachineBlockEntity.solarOutput(true, true, true, glacee, base) == 2, "Mars × 0,6 et planète glacée × 0,3 (arrondis)");
        helper.assertTrue(MachineBlockEntity.solarOutput(false, false, true, lune, base) == 0
                && MachineBlockEntity.solarOutput(true, false, false, lune, base) == 0, "planète : ni la nuit, ni sans ciel");

        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(4, 2, 4);
        helper.setBlock(rel, ModBlocks.SOLAR_PANEL.get().defaultBlockState());
        BlockPos pos = helper.absolutePos(rel);
        MachineBlockEntity panel = (MachineBlockEntity) level.getBlockEntity(pos);
        helper.assertTrue(panel != null && SpaceDimensions.planet(level) == null, "panneau posé hors planète");
        long expected = MachineBlockEntity.solarOutput(level.isDay(), level.isRaining(), level.canSeeSky(pos.above()), null,
                SFConfig.SOLAR_OUTPUT.get());
        panel.tick(level, pos, level.getBlockState(pos));
        helper.assertTrue(panel.energyBuffer().stored() == expected, "le panneau applique la même règle (attendu " + expected
                + ", lu " + panel.energyBuffer().stored() + ")");
        helper.setBlock(rel, Blocks.AIR.defaultBlockState());
        helper.succeed();
    }

    // ------------------------------------------------------------------ nation

    @GameTest(template = AREA, batch = "sf_j5_nation_space")
    public static void nationSpaceNbtRoundTrip(GameTestHelper helper) {
        NationSpace space = new NationSpace();
        helper.assertTrue(space.recordLanding(SpaceDimensions.LUNE, 1_000L) && !space.recordLanding(SpaceDimensions.LUNE, 9_000L)
                && space.firstLanding(SpaceDimensions.LUNE) == 1_000L, "premier atterrissage gardé");
        space.recordLanding(SpaceDimensions.MARS, 2_000L);
        space.addFlight();
        space.addFlight();
        space.addFlight();
        CompoundTag saved = space.save();
        NationSpace back = NationSpace.load(saved);
        helper.assertTrue(back.save().equals(saved) && back.flights() == 3 && back.landed().size() == 2
                && back.firstLanding(SpaceDimensions.MARS) == 2_000L && !back.hasLanded(SpaceDimensions.PLANETE_GLACEE),
                "aller-retour NBT identique");
        NationSpace none = NationSpace.load(new CompoundTag());
        helper.assertTrue(none.flights() == 0 && none.landed().isEmpty(), "NBT absent : programme vide");

        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_espace");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            Nation nation = J5TestUtil.foundNation(helper, chef, "SfJ5Espace");
            helper.assertTrue(nation.space().flights() == 0 && nation.space().landed().isEmpty(), "nouvelle nation : programme vide");
            nation.space().recordLanding(SpaceDimensions.LUNE, 5_000L);
            nation.space().addFlight();
            nation.markDirty();
            CompoundTag all = NationData.get(server).save(new CompoundTag(), server.registryAccess());
            ListTag list = all.getList("nations", Tag.TAG_COMPOUND);
            CompoundTag mine = null;
            for (int i = 0; i < list.size(); i++) {
                if (list.getCompound(i).getUUID("id").equals(nation.id())) {
                    mine = list.getCompound(i);
                }
            }
            helper.assertTrue(mine != null && mine.getCompound("space").equals(nation.space().save()),
                    "NBT de la nation : clé « space »");
            NationSpace reread = NationSpace.load(mine.getCompound("space"));
            helper.assertTrue(reread.firstLanding(SpaceDimensions.LUNE) == 5_000L && reread.flights() == 1,
                    "programme spatial relu depuis le NBT de la nation");
        } finally {
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ index en mémoire

    @GameTest(template = AREA)
    public static void spaceIndexesAndReturnPoints(GameTestHelper helper) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, id("essai_zones"));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<Long, UUID> territory = new HashMap<>();
        territory.put(ChunkPos.asLong(0, 0), a);
        territory.put(ChunkPos.asLong(1, 0), a);
        territory.put(ChunkPos.asLong(2, 0), a);
        territory.put(ChunkPos.asLong(-1, 0), b);
        ChunkOwners owners = (d, chunk) -> d.equals(dim) ? territory.get(chunk) : null;
        try {
            BlockPos diffuser = new BlockPos(8, 64, 8);
            ZoneIndex.Zone zone = ZoneIndex.activate(dim, diffuser, ZoneIndex.Kind.OXYGEN, 1, 32, a, 100L);
            helper.assertTrue(ZoneIndex.covers(dim, new BlockPos(8, 64, 8), ZoneIndex.Kind.OXYGEN, 100L, owners)
                    && ZoneIndex.covers(dim, new BlockPos(24, 64, 8), ZoneIndex.Kind.OXYGEN, 100L, owners),
                    "oxygène : chunks de la nation dans le 3 × 3");
            helper.assertFalse(ZoneIndex.covers(dim, new BlockPos(-8, 64, 8), ZoneIndex.Kind.OXYGEN, 100L, owners),
                    "oxygène : chunk étranger exclu");
            helper.assertFalse(ZoneIndex.covers(dim, new BlockPos(24, 64, 24), ZoneIndex.Kind.OXYGEN, 100L, owners),
                    "oxygène : chunk libre exclu");
            helper.assertFalse(ZoneIndex.covers(dim, new BlockPos(40, 64, 8), ZoneIndex.Kind.OXYGEN, 100L, owners),
                    "oxygène : chunk de la nation hors du 3 × 3 exclu");
            helper.assertTrue(ZoneIndex.covers(dim, new BlockPos(8, 96, 8), ZoneIndex.Kind.OXYGEN, 100L, owners)
                    && !ZoneIndex.covers(dim, new BlockPos(8, 97, 8), ZoneIndex.Kind.OXYGEN, 100L, owners),
                    "oxygène : ± 32 blocs en hauteur");
            helper.assertFalse(ZoneIndex.covers(dim, new BlockPos(8, 64, 8), ZoneIndex.Kind.HEAT, 100L, owners),
                    "une zone d'oxygène ne chauffe pas");
            helper.assertTrue(ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, 139L, owners)
                    && !ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, 140L, owners), "expiration après 40 ticks");
            helper.assertTrue(ZoneIndex.activate(dim, diffuser, ZoneIndex.Kind.OXYGEN, 1, 32, a, 130L) == zone
                    && ZoneIndex.size(dim) == 1 && ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, 160L, owners),
                    "rafraîchissement : même zone, nouvelle échéance");
            territory.remove(ChunkPos.asLong(1, 0));
            helper.assertFalse(ZoneIndex.covers(dim, new BlockPos(24, 64, 8), ZoneIndex.Kind.OXYGEN, 160L, owners),
                    "chunk abandonné : retiré de la zone sans réenregistrement");

            BlockPos boiler = new BlockPos(1000, 64, 0);
            ZoneIndex.activate(dim, boiler, ZoneIndex.Kind.HEAT, 50, 32, a, 200L);
            helper.assertTrue(ZoneIndex.covers(dim, new BlockPos(1049, 64, 0), ZoneIndex.Kind.HEAT, 200L, owners)
                    && !ZoneIndex.covers(dim, new BlockPos(1051, 64, 0), ZoneIndex.Kind.HEAT, 200L, owners)
                    && ZoneIndex.covers(dim, new BlockPos(1035, 64, 35), ZoneIndex.Kind.HEAT, 200L, owners)
                    && !ZoneIndex.covers(dim, new BlockPos(1036, 64, 36), ZoneIndex.Kind.HEAT, 200L, owners)
                    && ZoneIndex.covers(dim, new BlockPos(1000, 64, -49), ZoneIndex.Kind.HEAT, 200L, owners),
                    "chaleur : disque de rayon 50, frontières ignorées");
            ZoneIndex.remove(dim, diffuser);
            helper.assertTrue(ZoneIndex.size(dim) == 1 && ZoneIndex.zoneAt(dim, diffuser) == null
                    && !ZoneIndex.covers(dim, diffuser, ZoneIndex.Kind.OXYGEN, 160L, owners), "retrait d'une zone");
        } finally {
            ZoneIndex.clearLevel(dim);
        }
        helper.assertTrue(ZoneIndex.size(dim) == 0, "zones vidées");

        // Points de retour : départ terrestre, puis noyau national, puis point d'apparition.
        Map<Long, UUID> earth = new HashMap<>();
        earth.put(ChunkPos.asLong(1, 0), a);
        earth.put(ChunkPos.asLong(2, 0), b);
        earth.put(ChunkPos.asLong(5, 5), a);
        ChunkOwners earthOwners = (d, chunk) -> d.equals(Level.OVERWORLD) ? earth.get(chunk) : null;
        BlockPos spawn = new BlockPos(0, 70, 0);
        GlobalPos freeHome = GlobalPos.of(Level.OVERWORLD, new BlockPos(8, 70, 8));
        GlobalPos ownHome = GlobalPos.of(Level.OVERWORLD, new BlockPos(24, 70, 8));
        GlobalPos rivalHome = GlobalPos.of(Level.OVERWORLD, new BlockPos(40, 70, 8));
        GlobalPos core = GlobalPos.of(Level.OVERWORLD, new BlockPos(88, 70, 88));
        GlobalPos lostCore = GlobalPos.of(Level.OVERWORLD, new BlockPos(40, 70, 9));
        GlobalPos lunarCore = GlobalPos.of(SpaceDimensions.LUNE, new BlockPos(8, 70, 8));
        List<GlobalPos> cores = List.of(lunarCore, lostCore, core);
        helper.assertTrue(ReturnPoints.resolve(freeHome, a, cores, earthOwners, spawn).source() == ReturnPoints.Source.HOME
                && ReturnPoints.resolve(ownHome, a, cores, earthOwners, spawn).column().equals(ownHome), "départ libre ou national");
        ReturnPoints.Target fallback = ReturnPoints.resolve(rivalHome, a, cores, earthOwners, spawn);
        helper.assertTrue(fallback.source() == ReturnPoints.Source.CORE && fallback.column().equals(core),
                "départ chez un rival : premier noyau national de l'Overworld sur le territoire de la nation");
        helper.assertTrue(ReturnPoints.resolve(GlobalPos.of(SpaceDimensions.LUNE, new BlockPos(8, 70, 8)), a, cores, earthOwners, spawn)
                .source() == ReturnPoints.Source.CORE, "départ hors Overworld ignoré");
        ReturnPoints.Target last = ReturnPoints.resolve(rivalHome, a, List.of(lunarCore, lostCore), earthOwners, spawn);
        helper.assertTrue(last.source() == ReturnPoints.Source.SPAWN && last.column().equals(GlobalPos.of(Level.OVERWORLD, spawn)),
                "sans noyau valable : point d'apparition");
        helper.assertTrue(ReturnPoints.resolve(null, null, List.of(), earthOwners, spawn).source() == ReturnPoints.Source.SPAWN
                && ReturnPoints.resolve(freeHome, null, List.of(), earthOwners, spawn).source() == ReturnPoints.Source.HOME
                && ReturnPoints.resolve(ownHome, null, List.of(), earthOwners, spawn).source() == ReturnPoints.Source.SPAWN,
                "sans nation : départ libre seulement");

        // Permis de voyage : posés pour la durée d'un transfert seulement.
        ServerPlayer player = J5TestUtil.fakePlayer(helper, "sf_j5_permis");
        helper.assertFalse(TravelPermits.has(player.getUUID()), "aucun permis au départ");
        helper.assertTrue(TravelPermits.with(player, () -> TravelPermits.has(player.getUUID())), "permis pendant le transfert");
        helper.assertFalse(TravelPermits.has(player.getUUID()), "permis retiré après le transfert");
        TravelPermits.grant(player.getUUID());
        TravelPermits.revoke(player.getUUID());
        helper.assertFalse(TravelPermits.has(player.getUUID()), "permis révoqué");
        helper.succeed();
    }
}
