// Propriété : WP4
package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.enterprise.EnterpriseManager;
import io.github.theoblaise1.sovereignfrontier.enterprise.NationEnterprises;
import io.github.theoblaise1.sovereignfrontier.enterprise.Sector;
import io.github.theoblaise1.sovereignfrontier.enterprise.SectorDefinition;
import io.github.theoblaise1.sovereignfrontier.enterprise.SectorRegistry;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.LogEntry;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.network.EconomyNetwork;
import io.github.theoblaise1.sovereignfrontier.network.EconomyView;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GameTests des entreprises (J4, WP4) : chargement strict des secteurs, affectation bornée (population, verrous,
 * permission), sortie du revenu passif, fractions déterministes, multiplicateur de domaine, produits soumis à la
 * recherche, coffre plein, ajustement à la population, absence de double encaissement, vue Économie.
 * <p>
 * La production est testée avec des registres construits à la main ({@link SectorRegistry#parse} puis
 * {@link SectorRegistry#validated}, comme au chargement) : les valeurs du datapack peuvent évoluer sans casser ces
 * tests. Tout test qui fonde une nation a son propre lot.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class EnterpriseGameTests {
    private static final String AREA = J4TestUtil.AREA;
    private static final double EPS = 1e-9D;

    private EnterpriseGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    /** Registre de test : fichiers {@code sovereignfrontier:<secteur>} analysés puis validés comme au chargement. */
    private static SectorRegistry registry(Map<String, String> jsonBySector) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        jsonBySector.forEach((sector, json) -> files.put(id(sector), JsonParser.parseString(json)));
        return SectorRegistry.parse(files, JsonOps.INSTANCE).validated(ResearchRegistry.server());
    }

    /** Clé de traduction (sans préfixe du mod) du message d'un résultat. */
    private static String key(ActionResult result) {
        return result.message().getContents() instanceof TranslatableContents t
                ? t.getKey().substring(SovereignFrontier.MODID.length() + 1) : "";
    }

    private static void expectKey(GameTestHelper helper, ActionResult result, String expected, String step) {
        helper.assertTrue(!result.success() && key(result).equals(expected),
                step + " : refus « " + expected + " » attendu, obtenu « " + result.message().getString() + " »");
    }

    private static int count(SimpleContainer chest, Item item) {
        int n = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            if (chest.getItem(i).is(item)) {
                n += chest.getItem(i).getCount();
            }
        }
        return n;
    }

    private static EconomyView.SectorLine line(EconomyView.EnterprisesView view, Sector sector) {
        return view.sectors().get(sector.ordinal());
    }

    // ------------------------------------------------------------------ chargement

    @GameTest(template = AREA)
    public static void sectorsLoadedInvalidRejected(GameTestHelper helper) {
        SectorRegistry live = SectorRegistry.get();
        helper.assertTrue(live.size() == 4, "4 secteurs publiés (" + live.size() + ")");
        SectorDefinition agricole = live.get(Sector.AGRICOLE);
        helper.assertTrue(agricole != null && agricole.requires().isEmpty() && agricole.domain() == ResearchDomain.RESSOURCES
                && agricole.products().size() == 6 && agricole.products().get(5).item() == Items.LEATHER
                && agricole.products().get(5).requires().equals(List.of(id("ressources_2"))), "secteur agricole");
        SectorDefinition minier = live.get(Sector.MINIER);
        helper.assertTrue(minier != null && minier.products().size() == 8 && Math.abs(minier.bonusPerTier() - 0.04D) < EPS
                && minier.moneyPerWorker() == 1L, "secteur minier : 8 produits, bonus 0,04, 1 cr");
        SectorDefinition logistique = live.get(Sector.LOGISTIQUE);
        helper.assertTrue(logistique != null && logistique.products().isEmpty() && logistique.moneyPerWorker() == 8L
                && logistique.domain() == ResearchDomain.CIVIL && logistique.requires().equals(List.of(id("civil_3"))),
                "secteur logistique : 8 cr, civil_3, aucun objet");
        SectorDefinition industriel = live.get(Sector.INDUSTRIEL);
        helper.assertTrue(industriel != null && industriel.requires().equals(List.of(id("industrie_1")))
                && industriel.domain() == ResearchDomain.INDUSTRIE && industriel.products().size() == 6, "secteur industriel");

        String valid = "{\"domain\": \"ressources\", \"money_per_worker\": 1, "
                + "\"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 1.0}]}";
        helper.assertTrue(registry(Map.of("agricole", valid)).get(Sector.AGRICOLE) != null, "définition valide acceptée");
        helper.assertTrue(registry(Map.of("logistique", "{\"domain\": \"civil\", \"money_per_worker\": 8}"))
                .get(Sector.LOGISTIQUE) != null, "secteur d'argent seul accepté");

        StringBuilder nine = new StringBuilder();
        for (String item : List.of("wheat", "carrot", "potato", "beetroot", "bone_meal", "leather", "cobblestone", "coal", "raw_iron")) {
            nine.append(nine.isEmpty() ? "" : ", ").append("{\"item\": \"minecraft:").append(item).append("\", \"per_worker\": 1}");
        }
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("per_worker nul", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 0}]}");
        invalid.put("per_worker > 64", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 64.5}]}");
        invalid.put("objet inconnu", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:objet_inconnu\", \"per_worker\": 1}]}");
        invalid.put("bonus_per_tier > 0,2", "{\"domain\": \"ressources\", \"bonus_per_tier\": 0.3, \"money_per_worker\": 1}");
        invalid.put("produit en double", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 1},"
                + " {\"item\": \"minecraft:wheat\", \"per_worker\": 2}]}");
        invalid.put("domaine inconnu", "{\"domain\": \"magie\", \"money_per_worker\": 1}");
        invalid.put("prérequis inconnu", "{\"requires\": [\"sovereignfrontier:civil_99\"], \"domain\": \"civil\", \"money_per_worker\": 1}");
        invalid.put("prérequis de produit inconnu", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\","
                + " \"per_worker\": 1, \"requires\": [\"sovereignfrontier:inconnu_1\"]}]}");
        invalid.put("argent non entier", "{\"domain\": \"ressources\", \"money_per_worker\": 1.5}");
        invalid.put("ne produit rien", "{\"domain\": \"civil\"}");
        invalid.put("9 produits", "{\"domain\": \"ressources\", \"products\": [" + nine + "]}");
        invalid.put("objet interdit", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:elytra\", \"per_worker\": 1}]}");
        invalid.put("objet de machine interdit", "{\"domain\": \"industrie\", \"products\": [{\"item\": \"sovereignfrontier:generateur_thermique\", \"per_worker\": 1}]}");
        invalid.put("clé de caisse", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"sovereignfrontier:cle_commune\", \"per_worker\": 1}]}");
        invalid.put("champ inconnu", "{\"domain\": \"ressources\", \"money_per_workers\": 1}");
        invalid.put("champ de produit inconnu", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 1, \"chance\": 1}]}");
        invalid.put("tableau au lieu d'un objet", "[]");
        invalid.forEach((label, json) -> helper.assertTrue(registry(Map.of("agricole", json)).get(Sector.AGRICOLE) == null,
                "définition invalide rejetée en entier (" + label + ")"));

        // Un secteur rejeté n'emporte pas les autres ; noms de fichier hors des 4 secteurs ignorés.
        SectorRegistry mixed = registry(Map.of("agricole", valid, "minier", invalid.get("objet interdit")));
        helper.assertTrue(mixed.size() == 1 && mixed.get(Sector.MINIER) == null, "seul le secteur fautif est rejeté");
        Map<ResourceLocation, JsonElement> misnamed = new LinkedHashMap<>();
        misnamed.put(id("mines"), JsonParser.parseString(valid));
        misnamed.put(ResourceLocation.fromNamespaceAndPath("autre", "agricole"), JsonParser.parseString(valid));
        misnamed.put(id("sous/agricole"), JsonParser.parseString(valid));
        helper.assertTrue(SectorRegistry.parse(misnamed, JsonOps.INSTANCE).size() == 0, "fichiers mal nommés ignorés");
        helper.succeed();
    }

    // ------------------------------------------------------------------ affectation

    @GameTest(template = AREA, batch = "sf_j4_enterprise_assign")
    public static void assignmentBoundedByPopulationAndLock(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_chef");
        ServerPlayer recruit = J4TestUtil.fakePlayer(helper, "sf_j4_ent_recrue");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntAffect");
        try {
            J4TestUtil.addInhabitants(helper, nation, 3);
            NationEnterprises e = nation.enterprises();
            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, 2), true, "2 habitants à l'agricole");
            helper.assertTrue(e.workers(Sector.AGRICOLE) == 2 && NationManager.freeInhabitants(nation) == 1, "1 habitant libre");
            helper.assertTrue(EconomyNetwork.isDirty(nation), "vue Économie à renvoyer après l'affectation");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.MINIER, 2), "enterprise.error.population", "2 demandés, 1 libre");
            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.MINIER, 1), true, "1 habitant au minier");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.MINIER, 1), "enterprise.error.unchanged", "affectation inchangée");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, 3), "enterprise.error.population", "aucun libre");
            helper.assertTrue(e.assignedTotal() == 3, "somme des affectés = population effective");

            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, 0), true, "baisse toujours permise");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.LOGISTIQUE, 1), "enterprise.error.locked", "logistique sans civil_3");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.INDUSTRIEL, 1), "enterprise.error.locked", "industriel sans industrie_1");
            nation.research().complete(id("industrie_1"));
            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.INDUSTRIEL, 2), true, "industriel débloqué");
            nation.research().revoke(id("industrie_1"));
            expectKey(helper, EnterpriseManager.assign(chef, Sector.INDUSTRIEL, 3), "enterprise.error.locked", "hausse refusée une fois verrouillé");
            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.INDUSTRIEL, 0), true, "baisse permise dans un secteur verrouillé");

            expectKey(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, -1), "enterprise.error.range", "nombre négatif");
            expectKey(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, NationEnterprises.MAX_WORKERS + 1),
                    "enterprise.error.range", "au-delà de 10 000");

            J4TestUtil.expect(helper, NationManager.invite(chef, recruit), true, "invitation de la recrue");
            J4TestUtil.expect(helper, NationManager.join(recruit, nation.name()), true, "adhésion de la recrue");
            expectKey(helper, EnterpriseManager.assign(recruit, Sector.AGRICOLE, 1), "nation.error.no_permission",
                    "recrue sans la permission « economie »");
            helper.assertTrue(e.workers(Sector.AGRICOLE) == 0, "refus sans effet");
            ServerPlayer outsider = J4TestUtil.fakePlayer(helper, "sf_j4_ent_apatride");
            expectKey(helper, EnterpriseManager.assign(outsider, Sector.AGRICOLE, 1), "nation.error.no_nation", "sans nation");

            boolean logged = nation.logEntries().stream().anyMatch(l -> l.key().equals("enterprise_assign")
                    && l.args().contains("@enterprise.sector.minier"));
            helper.assertTrue(logged, "affectation inscrite au journal national");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_income")
    public static void assignedWorkersLeavePassiveIncome(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_revenu");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntRevenu");
        try {
            long per = SFConfig.INCOME_PER_INHABITANT.get();
            J4TestUtil.addInhabitants(helper, nation, 4);
            helper.assertTrue(NationManager.incomePerCycle(nation) == 4 * per, "4 habitants libres : " + 4 * per + " cr");
            J4TestUtil.expect(helper, EnterpriseManager.assign(chef, Sector.AGRICOLE, 3), true, "3 habitants à l'agricole");
            helper.assertTrue(NationManager.incomePerCycle(nation) == per, "seul l'habitant libre touche le revenu : " + per + " cr");

            EconomyView.EnterprisesView view = EnterpriseManager.view(helper.getLevel().getServer(), nation, SectorRegistry.get());
            helper.assertTrue(view.population() == 4 && view.assigned() == 3 && view.passiveIncome() == per,
                    "vue : population 4, 3 affectés, revenu passif " + per + " cr");
            helper.assertTrue(view.sectors().size() == 4 && view.ticksToCycle() >= 0
                    && view.ticksToCycle() <= SFConfig.INCOME_INTERVAL_TICKS.get(), "4 lignes et prochain cycle");
            EconomyView.SectorLine agricole = line(view, Sector.AGRICOLE);
            helper.assertTrue(agricole.unlocked() && agricole.workers() == 3 && agricole.moneyPerCycle() == 3L
                    && Math.abs(agricole.mult() - 1.0F) < 1e-6F, "agricole : 3 hab., ×1, 3 cr par cycle");
            helper.assertTrue(agricole.outputs().size() == 6 && Math.abs(agricole.outputs().get(0).perCycle() - 3.0F) < 1e-6F
                    && !agricole.outputs().get(0).locked() && agricole.outputs().get(5).locked(),
                    "agricole : blé 3 par cycle, cuir verrouillé (ressources_2)");
            EconomyView.SectorLine minier = line(view, Sector.MINIER);
            helper.assertTrue(minier.workers() == 0 && minier.moneyPerCycle() == 1L
                    && Math.abs(minier.outputs().get(0).perCycle() - 2.0F) < 1e-6F, "secteur vide : aperçu pour un habitant");
            EconomyView.SectorLine logistique = line(view, Sector.LOGISTIQUE);
            helper.assertTrue(!logistique.unlocked() && !logistique.lockReason().getString().isEmpty(),
                    "logistique verrouillée avec un motif");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ production

    @GameTest(template = AREA, batch = "sf_j4_enterprise_frac")
    public static void fractionsAccumulateDeterministically(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_frac");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntFrac");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            SectorRegistry reg = registry(Map.of(
                    "agricole", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 0.25}]}",
                    "minier", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:coal\", \"per_worker\": 0.1}]}"));
            J4TestUtil.addInhabitants(helper, nation, 3);
            NationEnterprises e = nation.enterprises();
            e.setWorkers(Sector.AGRICOLE, 2);
            nation.chest().clearContent();

            EnterpriseManager.CycleResult first = EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(first.items() == 0 && count(nation.chest(), Items.WHEAT) == 0
                    && Math.abs(e.fraction("minecraft:wheat") - 0.5D) < EPS, "cycle 1 : 0,25 × 2 = 0,5 en attente, rien déposé");
            EnterpriseManager.CycleResult second = EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(second.items() == 1 && count(nation.chest(), Items.WHEAT) == 1
                    && !e.fractions().containsKey("minecraft:wheat"), "cycle 2 : 1 blé, fraction soldée");
            EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(count(nation.chest(), Items.WHEAT) == 1 && Math.abs(e.fraction("minecraft:wheat") - 0.5D) < EPS,
                    "cycle 3 : de nouveau 0,5 en attente");

            // 0,1 × 10 cycles : 1 charbon exactement au 10e (tolérance d'arrondi), jamais avant.
            e.setWorkers(Sector.AGRICOLE, 0);
            e.setWorkers(Sector.MINIER, 1);
            for (int i = 1; i <= 10; i++) {
                EnterpriseManager.runCycle(server, nation, reg);
                int coal = count(nation.chest(), Items.COAL);
                helper.assertTrue(coal == (i == 10 ? 1 : 0), "charbon après " + i + " cycle(s) : " + coal);
            }
            helper.assertTrue(e.fraction("minecraft:coal") < 1e-6D, "aucune fraction résiduelle après 10 × 0,1");
            helper.assertTrue(Math.abs(e.fraction("minecraft:wheat") - 0.5D) < EPS, "fraction d'un secteur sans habitant conservée");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_tiers")
    public static void domainTiersRaiseMultiplier(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_paliers");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntPaliers");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            SectorRegistry reg = registry(Map.of("minier", "{\"domain\": \"ressources\", \"bonus_per_tier\": 0.04, "
                    + "\"money_per_worker\": 10, \"products\": [{\"item\": \"minecraft:cobblestone\", \"per_worker\": 10}]}"));
            SectorDefinition def = reg.get(Sector.MINIER);
            helper.assertTrue(def != null, "secteur de test chargé");
            J4TestUtil.addInhabitants(helper, nation, 5);
            nation.enterprises().setWorkers(Sector.MINIER, 5);
            nation.chest().clearContent();
            helper.assertTrue(Math.abs(EnterpriseManager.multiplier(nation, def) - 1.0D) < EPS, "aucun palier : ×1");

            nation.research().complete(id("ressources_1"));
            nation.research().complete(id("ressources_2"));
            nation.research().complete(id("militaire_2"));
            helper.assertTrue(EnterpriseManager.domainTiers(nation, ResearchDomain.RESSOURCES) == 2,
                    "2 paliers de ressources (le palier militaire ne compte pas)");
            helper.assertTrue(Math.abs(EnterpriseManager.multiplier(nation, def) - 1.08D) < EPS, "2 paliers × 0,04 : ×1,08");

            long before = nation.treasury();
            EnterpriseManager.CycleResult result = EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(result.money() == 54L && nation.treasury() - before == 54L,
                    "argent : round(5 × 10 × 1,08) = 54 cr (obtenu " + result.money() + ")");
            helper.assertTrue(result.items() == 54 && count(nation.chest(), Items.COBBLESTONE) == 54,
                    "objets : floor(5 × 10 × 1,08) = 54 pavés");
            EconomyView.SectorLine line = line(EnterpriseManager.view(server, nation, reg), Sector.MINIER);
            helper.assertTrue(Math.abs(line.mult() - 1.08F) < 1e-6F && line.moneyPerCycle() == 54L
                    && line.lastMoney() == 54L && line.lastItems() == 54, "vue : ×1,08, 54 cr, bilan du dernier cycle");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_gated")
    public static void productGatedByResearch(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_verrou");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntVerrou");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            SectorRegistry reg = registry(Map.of(
                    "agricole", "{\"domain\": \"ressources\", \"products\": [{\"item\": \"minecraft:wheat\", \"per_worker\": 1},"
                            + " {\"item\": \"minecraft:leather\", \"per_worker\": 1, \"requires\": [\"sovereignfrontier:ressources_2\"]}]}",
                    "industriel", "{\"requires\": [\"sovereignfrontier:industrie_1\"], \"domain\": \"industrie\", \"money_per_worker\": 2,"
                            + " \"products\": [{\"item\": \"minecraft:glass\", \"per_worker\": 1}]}"));
            J4TestUtil.addInhabitants(helper, nation, 3);
            NationEnterprises e = nation.enterprises();
            e.setWorkers(Sector.AGRICOLE, 2);
            e.setWorkers(Sector.INDUSTRIEL, 1);
            nation.chest().clearContent();

            long before = nation.treasury();
            EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(count(nation.chest(), Items.WHEAT) == 2 && count(nation.chest(), Items.LEATHER) == 0
                    && !e.fractions().containsKey("minecraft:leather"), "cuir verrouillé : ni produit, ni accumulé");
            helper.assertTrue(count(nation.chest(), Items.GLASS) == 0 && nation.treasury() == before,
                    "secteur verrouillé : ni objet, ni argent");
            EconomyView.EnterprisesView view = EnterpriseManager.view(server, nation, reg);
            helper.assertTrue(line(view, Sector.AGRICOLE).outputs().get(1).locked() && !line(view, Sector.INDUSTRIEL).unlocked(),
                    "vue : produit et secteur verrouillés");
            helper.assertTrue(!line(view, Sector.MINIER).unlocked() && line(view, Sector.MINIER).outputs().isEmpty(),
                    "secteur absent du registre : indisponible");

            nation.research().complete(id("ressources_2"));
            nation.research().complete(id("industrie_1"));
            EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(count(nation.chest(), Items.LEATHER) == 2 && count(nation.chest(), Items.GLASS) == 1
                    && nation.treasury() - before == 2L, "après la recherche : cuir, verre et argent produits");
            view = EnterpriseManager.view(server, nation, reg);
            helper.assertTrue(!line(view, Sector.AGRICOLE).outputs().get(1).locked() && line(view, Sector.INDUSTRIEL).unlocked(),
                    "vue : verrous levés");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_full")
    public static void chestFullLosesAndReports(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_plein");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntPlein");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            SectorRegistry reg = registry(Map.of("minier", "{\"domain\": \"ressources\", \"money_per_worker\": 3,"
                    + " \"products\": [{\"item\": \"minecraft:cobblestone\", \"per_worker\": 5}]}"));
            J4TestUtil.addInhabitants(helper, nation, 4);
            nation.enterprises().setWorkers(Sector.MINIER, 4);
            for (int i = 0; i < Nation.CHEST_SIZE - 1; i++) {
                nation.chest().setItem(i, new ItemStack(Items.STONE, 64));
            }
            nation.chest().setItem(Nation.CHEST_SIZE - 1, new ItemStack(Items.COBBLESTONE, 60));

            long before = nation.treasury();
            EnterpriseManager.CycleResult result = EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(result.items() == 4 && result.lost() == 16, "4 pavés déposés, 16 perdus (obtenu "
                    + result.items() + " / " + result.lost() + ")");
            helper.assertTrue(nation.chest().getItem(Nation.CHEST_SIZE - 1).getCount() == 64, "dernière pile complétée");
            helper.assertTrue(result.money() == 12L && nation.treasury() - before == 12L, "argent versé malgré le coffre plein");
            NationEnterprises e = nation.enterprises();
            helper.assertTrue(e.lastLost() == 16 && e.lastLost(Sector.MINIER) == 16 && e.lastItems(Sector.MINIER) == 4,
                    "pertes inscrites au bilan du cycle");
            EnterpriseManager.CycleResult again = EnterpriseManager.runCycle(server, nation, reg);
            helper.assertTrue(again.items() == 0 && again.lost() == 20 && again.money() == 12L,
                    "coffre plein : tout est perdu, sans exception, argent versé");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_trim")
    public static void populationDropTrims(GameTestHelper helper) {
        NationEnterprises ties = new NationEnterprises();
        for (Sector s : Sector.values()) {
            ties.setWorkers(s, 1);
        }
        helper.assertTrue(EnterpriseManager.trim(ties, 2) == 2 && ties.workers(Sector.AGRICOLE) == 1
                && ties.workers(Sector.MINIER) == 1 && ties.workers(Sector.LOGISTIQUE) == 0
                && ties.workers(Sector.INDUSTRIEL) == 0, "égalité : l'ordinal le plus élevé perd d'abord");

        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_baisse");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntBaisse");
        try {
            J4TestUtil.addInhabitants(helper, nation, 3);
            NationEnterprises e = nation.enterprises();
            e.setWorkers(Sector.AGRICOLE, 2);
            e.setWorkers(Sector.MINIER, 2);
            e.setWorkers(Sector.INDUSTRIEL, 1);
            EnterpriseManager.CycleResult result = EnterpriseManager.runCycle(helper.getLevel().getServer(), nation, SectorRegistry.EMPTY);
            helper.assertTrue(result.trimmed() == 2, "2 habitants retirés (obtenu " + result.trimmed() + ")");
            helper.assertTrue(e.workers(Sector.AGRICOLE) == 1 && e.workers(Sector.MINIER) == 1
                    && e.workers(Sector.LOGISTIQUE) == 0 && e.workers(Sector.INDUSTRIEL) == 1,
                    "minier (égalité, ordinal le plus haut) puis agricole : " + e);
            helper.assertTrue(e.assignedTotal() == nation.effectivePopulation(), "affectés = population effective");
            List<LogEntry> trims = new ArrayList<>(nation.logEntries().stream().filter(l -> l.key().equals("enterprise_trim")).toList());
            helper.assertTrue(trims.size() == 1 && trims.get(0).args().equals(List.of("2")), "ajustement inscrit au journal");
            helper.assertTrue(EnterpriseManager.runCycle(helper.getLevel().getServer(), nation, SectorRegistry.EMPTY).trimmed() == 0,
                    "aucun ajustement de plus au cycle suivant");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_enterprise_dip")
    public static void noDoubleDipSameCycle(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_ent_double");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfEntDouble");
        MinecraftServer server = helper.getLevel().getServer();
        try {
            long per = SFConfig.INCOME_PER_INHABITANT.get();
            SectorRegistry reg = registry(Map.of("agricole", "{\"domain\": \"ressources\", \"money_per_worker\": 1}"));
            J4TestUtil.addInhabitants(helper, nation, 4);
            for (int assigned : new int[]{4, 0, 2, 4, 1}) {
                nation.enterprises().setWorkers(Sector.AGRICOLE, assigned);
                long before = nation.treasury();
                // Même ordre que NationManager.tickIncome : production, puis revenu des habitants libres.
                EnterpriseManager.runCycle(server, nation, reg);
                long income = NationManager.incomePerCycle(nation);
                NationManager.adjustTreasury(nation, income);
                long expected = (4 - assigned) * per + assigned;
                helper.assertTrue(nation.treasury() - before == expected, assigned + " affecté(s) : " + (4 - assigned)
                        + " × " + per + " + " + assigned + " × 1 = " + expected + " cr (obtenu " + (nation.treasury() - before) + ")");
                helper.assertTrue(NationManager.freeInhabitants(nation) + nation.enterprises().assignedTotal() == 4,
                        "chaque habitant compté une seule fois");
            }
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ persistance

    @GameTest(template = AREA)
    public static void enterprisesSectorBalanceRoundTrip(GameTestHelper helper) {
        NationEnterprises e = new NationEnterprises();
        e.setWorkers(Sector.MINIER, 50_000);
        helper.assertTrue(e.workers(Sector.MINIER) == NationEnterprises.MAX_WORKERS, "affectation bornée à 10 000");
        e.setLast(Sector.INDUSTRIEL, 40L, 3, 2);
        e.setLast(40L, 3, 2);
        CompoundTag tag = e.save();
        tag.putIntArray("workers", new int[]{-3, 99_999, 0, 2});
        NationEnterprises back = NationEnterprises.load(tag);
        helper.assertTrue(back.workers(Sector.AGRICOLE) == 0 && back.workers(Sector.MINIER) == NationEnterprises.MAX_WORKERS
                && back.workers(Sector.INDUSTRIEL) == 2, "NBT relu borné");
        helper.assertTrue(back.lastMoney(Sector.INDUSTRIEL) == 40L && back.lastItems(Sector.INDUSTRIEL) == 3
                && back.lastLost(Sector.INDUSTRIEL) == 2 && back.lastMoney(Sector.AGRICOLE) == 0L
                && back.lastMoney() == 40L, "bilan par secteur relu");
        NationEnterprises idle = new NationEnterprises();
        idle.setFraction("minecraft:wheat", 0.5D);
        helper.assertTrue(idle.isIdle() && !back.isIdle(), "entreprise inactive détectée (fractions seules)");
        helper.assertTrue(EnterpriseManager.formatAmount(1.08D).equals("1,08") && EnterpriseManager.formatAmount(2.0D).equals("2")
                && EnterpriseManager.formatAmount(0.005D).equals("0,005"), "format des quantités");
        helper.succeed();
    }
}
