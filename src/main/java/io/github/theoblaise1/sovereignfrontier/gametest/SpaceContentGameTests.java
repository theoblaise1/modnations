// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.contract.ContractInstance;
import io.github.theoblaise1.sovereignfrontier.contract.ContractKind;
import io.github.theoblaise1.sovereignfrontier.contract.ContractManager;
import io.github.theoblaise1.sovereignfrontier.contract.ContractObjective;
import io.github.theoblaise1.sovereignfrontier.contract.ContractRegistry;
import io.github.theoblaise1.sovereignfrontier.contract.ContractTemplate;
import io.github.theoblaise1.sovereignfrontier.contract.NationContracts;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.reward.CrateEntry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRoller;
import io.github.theoblaise1.sovereignfrontier.reward.CrateTable;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GameTests du contenu économique du J5 (WP4) : les 7 modèles de contrat spatiaux et l'acceptation des dimensions
 * déclarées par une planète, le contrat de mission qui remet le Module cryogénique (seule exemption de la garde des
 * contrats), la caisse spatiale sans entrée provisoire et sans composant final, et les coûts de recherche en matériaux
 * spatiaux.
 * <p>
 * Les données lues sont celles du jeu (modèles, caisse, recherche publiés au démarrage) ; les registres de planètes de
 * test remplacent le registre global le temps d'un test, d'où un lot propre ({@code sf_j5_<test>}). Tout test qui fonde
 * une nation la dissout dans un {@code finally} (coffre national vidé d'abord).
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class SpaceContentGameTests {
    private static final String AREA = J5TestUtil.AREA;
    private static final long HOUR = ContractManager.HOUR_MS;
    private static final int THRESHOLD = 20;

    private SpaceContentGameTests() {
    }

    // ------------------------------------------------------------------ outils

    private static ResourceLocation id(String path) {
        return J5TestUtil.id(path);
    }

    /** Registre de test : fichiers {@code sovereignfrontier:<chemin>} analysés puis validés comme au chargement. */
    private static ContractRegistry registry(GameTestHelper helper, Map<String, String> jsonByPath) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        jsonByPath.forEach((path, json) -> files.put(id(path), JsonParser.parseString(json)));
        return ContractRegistry.parse(files).validated(helper.getLevel().registryAccess(), ResearchRegistry.server());
    }

    private static boolean accepted(GameTestHelper helper, String json) {
        return registry(helper, Map.of("essai", json)).get(id("essai")) != null;
    }

    /** Ajoute le contrat aux contrats en cours de la nation, accepté par {@code by}. */
    private static ContractInstance activate(Nation nation, ContractInstance c, ServerPlayer by, long now) {
        nation.contracts().addActive(c);
        c.accept(now, by.getUUID());
        nation.markDirty();
        return c;
    }

    private static boolean inHistory(Nation nation, int contractId, NationContracts.Status status) {
        return nation.contracts().history().stream().anyMatch(h -> h.id() == contractId && h.status() == status);
    }

    private static int count(Container container, Item item) {
        int n = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static void give(ServerPlayer player, Item item, int amount) {
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            player.getInventory().add(new ItemStack(item, n));
            left -= n;
        }
    }

    /** Exploration d'une dimension du mod : non mise à l'échelle, cible {@code sovereignfrontier:<dimension>}. */
    private static boolean dimensionTarget(ContractTemplate t, String dimension) {
        return t.kind() == ContractKind.EXPLORE && t.objective() instanceof ContractObjective.Explore e
                && e.target() instanceof ContractObjective.DimensionTarget d && d.dimension().equals(id(dimension))
                && !e.scalesWithMembers();
    }

    private static boolean delivery(ContractTemplate t, Item item, int min, int max, int step) {
        return t.kind() == ContractKind.DELIVER && t.objective() instanceof ContractObjective.Deliver d && d.item() == item
                && d.amount().equals(new ContractObjective.Amount(min, max, step));
    }

    private static boolean requires(ContractTemplate t, String... tiers) {
        return t.requires().equals(java.util.Arrays.stream(tiers).map(SpaceContentGameTests::id).toList());
    }

    /** Clés de la récompense : une clé par type de caisse, avec sa chance. */
    private static boolean keys(ContractTemplate t, Map<CrateKind, Double> expected) {
        Map<CrateKind, Double> got = new EnumMap<>(CrateKind.class);
        for (ContractTemplate.KeyChance key : t.reward().keys()) {
            if (key.count() != 1 || got.put(key.crate(), key.chance()) != null) {
                return false;
            }
        }
        return got.equals(expected);
    }

    private static boolean money(ContractTemplate t, long money, double perUnit, long points, double pointsPerUnit) {
        ContractTemplate.Reward r = t.reward();
        return r.money() == money && Math.abs(r.moneyPerUnit() - perUnit) < 1e-9 && r.points() == points
                && Math.abs(r.pointsPerUnit() - pointsPerUnit) < 1e-9;
    }

    // ------------------------------------------------------------------ contrats

    @GameTest(template = AREA, batch = "sf_j5_space_contracts")
    public static void spaceContractsLoaded(GameTestHelper helper) {
        ContractRegistry live = ContractRegistry.get();
        helper.assertTrue(live.size() == 27, "27 modèles publiés (obtenu " + live.size() + ")");
        Map<ContractKind, Integer> kinds = live.countByKind();
        helper.assertTrue(kinds.getOrDefault(ContractKind.DELIVER, 0) == 13 && kinds.getOrDefault(ContractKind.KILL, 0) == 6
                && kinds.getOrDefault(ContractKind.EXPLORE, 0) == 8, "13 livraisons, 6 chasses, 8 explorations : " + kinds);

        ContractTemplate lune = live.get(id("exploration_lune"));
        helper.assertTrue(lune != null && dimensionTarget(lune, "lune") && requires(lune, "technologie_2") && lune.weight() == 8
                && lune.durationHours() == 72 && money(lune, 600, 0.0D, 15, 0.0D) && keys(lune, Map.of(CrateKind.SPATIALE, 1.0D)),
                "exploration_lune : dimension lune, technologie_2, 600 cr, 15 pts, clé spatiale 100 %, 72 h, poids 8");
        ContractTemplate mars = live.get(id("exploration_mars"));
        helper.assertTrue(mars != null && dimensionTarget(mars, "mars") && requires(mars, "technologie_4") && mars.weight() == 6
                && mars.durationHours() == 96 && money(mars, 900, 0.0D, 25, 0.0D) && keys(mars, Map.of(CrateKind.SPATIALE, 1.0D)),
                "exploration_mars : technologie_4, 900 cr, 25 pts, clé spatiale 100 %, 96 h, poids 6");
        ContractTemplate glacee = live.get(id("exploration_planete_glacee"));
        helper.assertTrue(glacee != null && dimensionTarget(glacee, "planete_glacee") && requires(glacee, "technologie_7")
                && glacee.weight() == 4 && glacee.durationHours() == 120 && money(glacee, 1500, 0.0D, 40, 0.0D)
                && keys(glacee, Map.of(CrateKind.SPATIALE, 1.0D, CrateKind.INDUSTRIELLE, 0.5D)),
                "exploration_planete_glacee : technologie_7, 1 500 cr, 40 pts, clé spatiale 100 % et industrielle 50 %, 120 h");

        ContractTemplate titane = live.get(id("livraison_titane"));
        helper.assertTrue(titane != null && delivery(titane, ModItems.TITANIUM_INGOT.get(), 16, 48, 8)
                && requires(titane, "ressources_13") && titane.durationHours() == 72 && money(titane, 300, 10.0D, 10, 0.3D)
                && titane.reward().keys().isEmpty() && titane.reward().items().isEmpty(),
                "livraison_titane : lingots 16 à 48 par 8, ressources_13, 300 + 10/u cr, 10 + 0,3/u pts, 72 h");
        ContractTemplate soufre = live.get(id("livraison_soufre"));
        helper.assertTrue(soufre != null && delivery(soufre, ModItems.SULFUR.get(), 32, 96, 16)
                && requires(soufre, "ressources_14") && soufre.durationHours() == 72 && money(soufre, 400, 6.0D, 12, 0.2D),
                "livraison_soufre : 32 à 96 par 16, ressources_14, 400 + 6/u cr, 12 + 0,2/u pts, 72 h");
        ContractTemplate cristaux = live.get(id("livraison_cristaux"));
        helper.assertTrue(cristaux != null && delivery(cristaux, ModItems.CRYOGENIC_CRYSTAL.get(), 8, 24, 4)
                && requires(cristaux, "ressources_15", "technologie_7") && cristaux.durationHours() == 96
                && money(cristaux, 800, 40.0D, 25, 1.0D),
                "livraison_cristaux : 8 à 24 par 4, ressources_15 et technologie_7, 800 + 40/u cr, 25 + 1,0/u pts, 96 h");
        ContractTemplate mission = live.get(id("mission_module_cryogenique"));
        helper.assertTrue(mission != null && delivery(mission, ModItems.SULFUR.get(), 64, 128, 16)
                && requires(mission, "industrie_11", "ressources_14") && mission.minPopulation() == 12
                && mission.durationHours() == 168 && mission.weight() == 50 && money(mission, 2000, 0.0D, 30, 0.0D)
                && mission.reward().keys().isEmpty() && mission.reward().items().size() == 1
                && mission.reward().items().get(0).item() == ModItems.CRYOGENIC_MODULE.get()
                && mission.reward().items().get(0).count() == 1,
                "mission_module_cryogenique : 64 à 128 soufres, 2 000 cr, 30 pts, 1 module, population 12, 168 h, poids 50");

        for (ContractTemplate t : List.of(lune, mars, glacee, titane, soufre, cristaux, mission)) {
            helper.assertTrue(t.reward().moneyPerUnit() * t.maxScaledAmount() <= ContractTemplate.MONEY_PER_UNIT_CAP
                    && t.reward().points() + t.reward().pointsPerUnit() * t.maxScaledAmount() <= ContractTemplate.POINTS_MAX,
                    t.id() + " : plafonds d'argent et de points du J4 respectés");
        }
        for (ResourceLocation id : List.of(id("livraison_titane"), id("livraison_soufre"), id("livraison_cristaux"),
                id("mission_module_cryogenique"))) {
            helper.assertTrue(live.get(id).objective() instanceof ContractObjective.Deliver d
                    && !RestrictedItems.isBannedFromLoot(d.item()), id + " : matériau livré autorisé");
        }

        // Une cible de dimension est acceptée si la dimension existe au serveur OU si une planète la déclare.
        String explore = "{\"kind\": \"explore\", \"duration_hours\": 24, \"objective\": {\"dimension\": \"%s\"}, "
                + "\"reward\": {\"money\": 10}}";
        ResourceKey<Level> labo = ResourceKey.create(Registries.DIMENSION, id("labo_j5"));
        PlanetDefinition declared = J5TestUtil.parsePlanets("labo", J5TestUtil.planetJson(labo, 1000L))
                .validated(ResearchRegistry.server()).byName("labo");
        helper.assertTrue(declared != null, "planète de test liée à une dimension du mod absente du serveur");
        J5TestUtil.withPlanets(List.of(), () -> {
            helper.assertFalse(accepted(helper, explore.formatted("sovereignfrontier:labo_j5")),
                    "dimension du mod sans planète : modèle rejeté");
            helper.assertTrue(accepted(helper, explore.formatted("minecraft:the_nether")), "dimension vanilla : acceptée");
            helper.assertFalse(accepted(helper, explore.formatted("minecraft:inexistante")), "dimension inconnue : rejetée");
        });
        J5TestUtil.withPlanets(List.of(declared), () -> {
            helper.assertTrue(accepted(helper, explore.formatted("sovereignfrontier:labo_j5")),
                    "dimension déclarée par une planète : acceptée sans niveau au serveur");
            helper.assertFalse(accepted(helper, explore.formatted("sovereignfrontier:inexistante")),
                    "autre dimension du mod : toujours rejetée");
        });
        helper.assertTrue(PlanetRegistry.get().byName("lune") != null, "registre des planètes rétabli");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_mission_module")
    public static void missionContractGrantsModule(GameTestHelper helper) {
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_mission_chef");
        ServerPlayer member = J5TestUtil.fakePlayer(helper, "sf_j5_mission_membre");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfMission");
        MinecraftServer server = helper.getLevel().getServer();
        Item module = ModItems.CRYOGENIC_MODULE.get();
        Item sulfur = ModItems.SULFUR.get();
        try {
            ContractTemplate mission = ContractRegistry.get().get(id("mission_module_cryogenique"));
            helper.assertTrue(mission != null, "modèle de mission publié");

            // Proposée seulement avec industrie_11, ressources_14 et 12 habitants.
            J5TestUtil.complete(helper, nation, "industrie_11");
            helper.assertFalse(ContractManager.eligible(nation, ContractRegistry.get()).contains(mission),
                    "industrie_11 seul : mission non proposée");
            J5TestUtil.complete(helper, nation, "ressources_14");
            helper.assertFalse(ContractManager.eligible(nation, ContractRegistry.get()).contains(mission),
                    "paliers terminés mais population insuffisante : mission non proposée");
            J4TestUtil.addInhabitants(helper, nation, 12);
            helper.assertTrue(ContractManager.eligible(nation, ContractRegistry.get()).contains(mission),
                    "industrie_11, ressources_14 et 12 habitants : mission proposée");

            long now = nation.createdEpochMs() + 13 * HOUR;
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            int tiers = nation.research().completed().size();

            // Plus gros contributeur en ligne (le joueur qui termine) : le module va dans son inventaire.
            ContractInstance first = ContractManager.instantiate(mission, nation.contracts().allocateId(), nation.memberCount(),
                    tiers, RandomSource.create(11L), now);
            helper.assertTrue(first.amount() >= 64 && first.amount() <= 128 && first.amount() % 16 == 0 && first.points() == 30
                    && first.items().size() == 1 && first.items().get(0).item() == module && first.items().get(0).count() == 1,
                    "mission instanciée : 64 à 128 soufres par 16, 30 points, 1 module (" + first + ")");
            activate(nation, first, chef, now);
            chef.getInventory().clearContent();
            give(chef, sulfur, first.amount());
            long pointsBefore = nation.research().points();
            J4TestUtil.expect(helper, ContractManager.deliver(chef, first.id(), Integer.MAX_VALUE, now), true, "livraison du soufre");
            helper.assertTrue(first.isFull() && inHistory(nation, first.id(), NationContracts.Status.COMPLETED), "mission réussie");
            helper.assertTrue(Inventories.countPlain(chef, module) == 1 && count(nation.chest(), module) == 0,
                    "module remis au plus gros contributeur en ligne");
            helper.assertTrue(Inventories.countPlain(chef, sulfur) == 0, "soufre consommé");
            helper.assertTrue(nation.research().points() - pointsBefore == 30L, "+30 points de recherche");
            helper.assertFalse(ContractManager.complete(server, nation, first, chef, now), "second déclenchement refusé");
            helper.assertTrue(Inventories.countPlain(chef, module) == 1 && count(nation.chest(), module) == 0
                    && nation.research().points() - pointsBefore == 30L, "module remis une seule fois");

            // Plus gros contributeur hors ligne : le module va au coffre national (chemin J4).
            J4TestUtil.expect(helper, NationManager.invite(chef, member), true, "invitation");
            J4TestUtil.expect(helper, NationManager.join(member, nation.name()), true, "adhésion");
            chef.getInventory().clearContent();
            member.getInventory().clearContent();
            ContractInstance second = ContractManager.instantiate(mission, nation.contracts().allocateId(), nation.memberCount(),
                    tiers, RandomSource.create(12L), now);
            activate(nation, second, chef, now);
            int total = second.amount();
            give(member, sulfur, total - 16);
            give(chef, sulfur, 16);
            J4TestUtil.expect(helper, ContractManager.deliver(member, second.id(), Integer.MAX_VALUE, now), true,
                    "livraison du membre");
            helper.assertTrue(second.contribution(member.getUUID()) == total - 16 && !second.isFull(), "le membre mène la livraison");
            J4TestUtil.expect(helper, ContractManager.deliver(chef, second.id(), Integer.MAX_VALUE, now), true, "reste livré par le chef");
            helper.assertTrue(inHistory(nation, second.id(), NationContracts.Status.COMPLETED), "seconde mission réussie");
            helper.assertTrue(count(nation.chest(), module) == 1 && Inventories.countPlain(chef, module) == 0
                    && Inventories.countPlain(member, module) == 0, "contributeur hors ligne : module au coffre national");
            helper.assertFalse(ContractManager.complete(server, nation, second, chef, now), "second déclenchement refusé");
            helper.assertTrue(count(nation.chest(), module) == 1, "module remis une seule fois");
        } finally {
            chef.getInventory().clearContent();
            member.getInventory().clearContent();
            NationManager.leave(member);
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_contract_guard")
    public static void contractExemptionOnlyFinalRocket(GameTestHelper helper) {
        Item module = ModItems.CRYOGENIC_MODULE.get();
        String deliver = "{\"kind\": \"deliver\", \"duration_hours\": 24, \"objective\": {\"item\": \"minecraft:wheat\", "
                + "\"amount\": {\"min\": 1, \"max\": 4}}, \"reward\": {\"items\": [%s]}}";
        String elytra = "{\"item\": \"minecraft:elytra\", \"count\": 1}";
        String finalModule = "{\"item\": \"sovereignfrontier:module_cryogenique\", \"count\": 1}";
        String rocket = "{\"item\": \"sovereignfrontier:fusee\", \"count\": 1}";

        // Modèles en ligne : élytres (et fusée complète) rejetées, module accepté, mélange rejeté en entier.
        helper.assertFalse(accepted(helper, deliver.formatted(elytra)), "élytres en récompense : modèle rejeté");
        helper.assertFalse(accepted(helper, deliver.formatted(rocket)), "fusée (hors économie) en récompense : modèle rejeté");
        helper.assertFalse(accepted(helper, deliver.formatted(finalModule + ", " + elytra)), "module et élytres : rejeté en entier");
        ContractTemplate withModule = registry(helper, Map.of("essai", deliver.formatted(finalModule))).get(id("essai"));
        helper.assertTrue(withModule != null && withModule.reward().items().size() == 1
                && withModule.reward().items().get(0).item() == module, "module en récompense : modèle accepté");

        // Seul #restreint/fusee_finale est exempté ; tout le reste de #interdit_caisses reste interdit dans un contrat.
        helper.assertTrue(RestrictedItems.isBannedFromLoot(module) && !RestrictedItems.isBannedFromContracts(module),
                "module : interdit en caisse, permis dans un contrat");
        for (Item banned : List.of(Items.ELYTRA, Items.NETHERITE_SWORD, Items.BEACON, ModItems.ROCKET.get(),
                ModItems.REFINERY.get(), ModItems.OXYGEN_DIFFUSER.get(), ModItems.GAS_BOILER.get(), ModItems.NUCLEAR_HEATER.get(),
                ModItems.ADVANCED_PROPULSOR.get(), ModItems.HEATED_CHESTPLATE.get())) {
            helper.assertTrue(RestrictedItems.isBannedFromContracts(banned), banned + " reste interdit dans un contrat");
        }
        for (Item allowed : List.of(ModItems.ROCKET_HULL.get(), ModItems.ROCKET_ENGINE.get(), ModItems.OXYGEN_BOTTLE.get(),
                ModItems.ROCKET_FUEL.get(), ModItems.TITANIUM_INGOT.get(), ModItems.SULFUR.get(), ModItems.CRYOGENIC_CRYSTAL.get(),
                Items.DIAMOND)) {
            helper.assertFalse(RestrictedItems.isBannedFromContracts(allowed), allowed + " permis dans un contrat");
        }

        // Le module reste interdit dans une caisse : la table fautive est rejetée en entier.
        CrateTable crate = CrateRegistry.parse(JsonParser.parseString("""
                {"rolls": 1, "entries": [
                  {"item": "sovereignfrontier:module_cryogenique", "weight": 5},
                  {"id": "r", "money": 5, "rarity": "rare"}
                ]}"""), helper.getLevel().registryAccess()).getOrThrow();
        helper.assertTrue(crate.bannedItems().equals(List.of(id("module_cryogenique"))), "module détecté dans la caisse");
        Map<CrateKind, CrateTable> candidate = new EnumMap<>(CrateKind.class);
        candidate.put(CrateKind.SPATIALE, crate);
        helper.assertTrue(CrateRegistry.rejectBanned(candidate).isEmpty(), "caisse avec le module rejetée en entier");

        // Garde d'exécution : un contrat dont la récompense mêle élytres et module ne remet que le module.
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_garde_chef");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfGarde");
        try {
            long now = nation.createdEpochMs() + 13 * HOUR;
            nation.contracts().setNextRefresh(now + 24 * HOUR);
            ContractInstance c = activate(nation, new ContractInstance(nation.contracts().allocateId(), id("test_garde"),
                    ContractKind.DELIVER, ContractInstance.Target.item(Items.WHEAT), 4, 100L, 0L, new int[4],
                    List.of(new ContractTemplate.ItemReward(Items.ELYTRA, 1), new ContractTemplate.ItemReward(module, 1)), 48, now),
                    chef, now);
            chef.getInventory().clearContent();
            give(chef, Items.WHEAT, 4);
            J4TestUtil.expect(helper, ContractManager.deliver(chef, c.id(), Integer.MAX_VALUE, now), true, "livraison du blé");
            helper.assertTrue(inHistory(nation, c.id(), NationContracts.Status.COMPLETED), "contrat réussi");
            helper.assertTrue(count(chef.getInventory(), module) == 1, "module remis");
            helper.assertTrue(count(chef.getInventory(), Items.ELYTRA) == 0 && count(nation.chest(), Items.ELYTRA) == 0,
                    "élytres jamais remises (garde d'exécution des récompenses de contrat)");
        } finally {
            chef.getInventory().clearContent();
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ caisse spatiale

    @GameTest(template = AREA)
    public static void spaceCrateNoPlaceholder(GameTestHelper helper) {
        CrateTable t = CrateRegistry.get(CrateKind.SPATIALE);
        helper.assertTrue(t != null, "caisse spatiale publiée");
        helper.assertTrue(t.entries().stream().noneMatch(CrateEntry::isPlaceholder), "aucune entrée provisoire");
        helper.assertTrue(t.bannedItems().isEmpty() && t.rolls() == 2, "aucun objet interdit, 2 tirages");
        helper.assertTrue(crateItem(t, ModItems.ROCKET_HULL.get(), 1, 1, 4) && crateItem(t, ModItems.ROCKET_ENGINE.get(), 1, 1, 2)
                && crateItem(t, ModItems.OXYGEN_BOTTLE.get(), 2, 6, 8) && crateItem(t, ModItems.ROCKET_FUEL.get(), 1, 2, 8),
                "coque ×1 (poids 4), moteur ×1 (2), bouteille d'oxygène 2 à 6 (8), carburant 1 à 2 (8)");
        Set<String> rares = new HashSet<>();
        t.rares().forEach(e -> rares.add(e.key()));
        helper.assertTrue(rares.equals(Set.of("combustible", "gaz_massif", "pactole")), "rares inchangées : " + rares);

        Item module = ModItems.CRYOGENIC_MODULE.get();
        RandomSource random = RandomSource.create(5150L);
        Map<String, Integer> miss = Map.of();
        Map<Item, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < 10_000; i++) {
            CrateRoller.Roll roll = CrateRoller.roll(t, miss, THRESHOLD, random);
            for (CrateRoller.Gain gain : CrateRoller.realize(roll.drawn(), random)) {
                helper.assertFalse(gain.entry().isPlaceholder(), "entrée provisoire tirée");
                if (!gain.isMoney()) {
                    helper.assertFalse(gain.stack().is(module), "module cryogénique tiré d'une caisse");
                    helper.assertFalse(RestrictedItems.isBannedFromLoot(gain.stack()), gain.stack() + " : objet interdit tiré");
                    seen.merge(gain.stack().getItem(), gain.stack().getCount(), Integer::sum);
                }
            }
            miss = roll.miss();
        }
        for (Item expected : List.of(ModItems.ROCKET_HULL.get(), ModItems.ROCKET_ENGINE.get(), ModItems.OXYGEN_BOTTLE.get(),
                ModItems.ROCKET_FUEL.get())) {
            helper.assertTrue(seen.getOrDefault(expected, 0) > 0, expected + " tiré au moins une fois en 10 000 ouvertures");
        }
        helper.assertTrue(CrateRegistry.get(CrateKind.MILITAIRE).entries().stream().filter(CrateEntry::isPlaceholder).count() == 0,
                "la caisse militaire n'a plus d'entrée provisoire (J6)");
        helper.succeed();
    }

    private static boolean crateItem(CrateTable t, Item item, int min, int max, int weight) {
        return t.entries().stream().anyMatch(e -> !e.isRare() && e.weight() == weight
                && e.reward() instanceof CrateEntry.ItemReward it && it.item() == item
                && it.count().min() == min && it.count().max() == max);
    }

    // ------------------------------------------------------------------ recherche

    /** Palier, matériau spatial qui remplace un objet vanilla (quantité), objet vanilla retiré, trésorerie et points. */
    private record Expected(String tier, Item space, int count, Item removed, long treasury, long points) {
    }

    @GameTest(template = AREA)
    public static void researchCostsUseSpaceMaterials(GameTestHelper helper) {
        ResearchRegistry research = ResearchRegistry.server();
        Item titanium = ModItems.TITANIUM_INGOT.get();
        Item sulfur = ModItems.SULFUR.get();
        List<Expected> expected = List.of(
                new Expected("technologie_5", titanium, 24, Items.FIRE_CHARGE, 8950L, 0L),
                new Expected("industrie_11", titanium, 32, Items.PHANTOM_MEMBRANE, 10950L, 30L),
                new Expected("ressources_14", titanium, 32, Items.QUARTZ_BLOCK, 10500L, 45L),
                new Expected("ressources_15", sulfur, 64, Items.ENDER_EYE, 11600L, 50L),
                new Expected("technologie_6", sulfur, 48, Items.LEATHER, 11750L, 5L),
                new Expected("industrie_12", sulfur, 64, Items.MAGMA_BLOCK, 12450L, 35L),
                new Expected("technologie_7", titanium, 64, Items.BLUE_ICE, 14800L, 10L));
        Set<ResourceLocation> changed = new HashSet<>();
        for (Expected e : expected) {
            ResearchTier tier = research.get(id(e.tier()));
            helper.assertTrue(tier != null, "palier " + e.tier() + " chargé");
            changed.add(tier.id());
            ResearchDefinition.Cost cost = tier.definition().cost();
            helper.assertTrue(cost.items().stream().anyMatch(i -> i.item() == e.space() && i.count() == e.count()),
                    e.tier() + " : " + e.count() + " × " + e.space() + " au coût");
            helper.assertTrue(cost.items().stream().noneMatch(i -> i.item() == e.removed()),
                    e.tier() + " : " + e.removed() + " remplacé par le matériau spatial");
            helper.assertTrue(cost.items().size() == 4 && cost.treasury() == e.treasury() && cost.points() == e.points(),
                    e.tier() + " : trésorerie " + e.treasury() + " et points " + e.points() + " inchangés, 4 types d'objets");
        }

        // Matériaux spatiaux : seulement dans ces 7 paliers, qui exigent (transitivement) l'extraction correspondante.
        Map<Item, String> extraction = Map.of(ModItems.RAW_TITANIUM.get(), "mining.lunar", titanium, "mining.lunar",
                sulfur, "mining.martian", ModItems.CRYOGENIC_CRYSTAL.get(), "mining.cryogenic");
        for (ResearchTier tier : research.all()) {
            for (ResearchDefinition.ItemCost item : tier.definition().cost().items()) {
                String key = extraction.get(item.item());
                if (key == null) {
                    continue;
                }
                helper.assertTrue(changed.contains(tier.id()), tier.id() + " : matériau spatial hors des 7 paliers prévus");
                ResourceLocation source = research.unlockSources().get(key);
                helper.assertTrue(source != null && requiresTransitively(research, tier, source),
                        tier.id() + " exige " + source + " (« " + key + " ») pour son coût en " + item.item());
            }
        }
        helper.assertTrue(research.size() == 65, "65 paliers chargés");
        helper.succeed();
    }

    /** Vrai si {@code tier} exige {@code wanted}, directement ou par un enchaînement de prérequis (implicites compris). */
    private static boolean requiresTransitively(ResearchRegistry research, ResearchTier tier, ResourceLocation wanted) {
        Set<ResourceLocation> seen = new HashSet<>();
        Deque<ResourceLocation> todo = new ArrayDeque<>(research.prerequisites(tier));
        while (!todo.isEmpty()) {
            ResourceLocation current = todo.pop();
            if (current.equals(wanted)) {
                return true;
            }
            ResearchTier next = research.get(current);
            if (next != null && seen.add(current)) {
                todo.addAll(research.prerequisites(next));
            }
        }
        return false;
    }
}
