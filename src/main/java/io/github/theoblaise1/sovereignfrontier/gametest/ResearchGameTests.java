package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.research.NationResearch;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDomain;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchReloadListener;
import io.github.theoblaise1.sovereignfrontier.research.ResearchStat;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GameTests du jalon J2 : données de recherche, cycle de vie d'une recherche, bonus. */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class ResearchGameTests {
    private static final String AREA = "gametest/empty9";

    private ResearchGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    @GameTest(template = AREA)
    public static void allTiersLoaded(GameTestHelper helper) {
        ResearchRegistry registry = ResearchRegistry.server();
        for (ResearchDomain domain : ResearchDomain.values()) {
            helper.assertTrue(registry.domain(domain).size() == domain.tierCount(),
                    domain.id() + " : " + registry.domain(domain).size() + " palier(s) chargé(s) sur " + domain.tierCount());
        }
        helper.assertTrue(registry.size() == 65, "65 paliers attendus, " + registry.size() + " chargés");
        ResearchTier rocket = registry.get(id("industrie_8"));
        helper.assertTrue(rocket != null && rocket.definition().requires().contains(id("technologie_1")),
                "industrie_8 exige technologie_1");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void invalidDefinitionsAreRejected(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("t/civil_1"), json("{\"domain\":\"civil\",\"tier\":1}"));
        files.put(id("t/civil_2"), json("{\"domain\":\"civil\",\"tier\":2,\"requires\":[\"sovereignfrontier:t/industrie_1\"]}"));
        files.put(id("t/industrie_1"), json("{\"domain\":\"industrie\",\"tier\":1,\"requires\":[\"sovereignfrontier:t/civil_2\"]}"));
        files.put(id("t/civil_4"), json("{\"domain\":\"civil\",\"tier\":4}"));
        files.put(id("t/militaire_1"), json("{\"domain\":\"militaire\",\"tier\":1,\"rewards\":[{\"type\":\"bonus\",\"stat\":\"inconnu\",\"amount\":1}]}"));
        files.put(id("t/ressources_1"), json("{\"domain\":\"ressources\",\"tier\":1,\"cost\":{\"items\":[{\"item\":\"minecraft:objet_inexistant\",\"count\":1}]}}"));
        files.put(id("t/technologie_9"), json("{\"domain\":\"technologie\",\"tier\":9}"));
        ResearchRegistry registry = ResearchReloadListener.build(files);
        helper.assertTrue(registry.get(id("t/civil_1")) != null, "palier valide conservé");
        helper.assertTrue(registry.get(id("t/civil_2")) == null && registry.get(id("t/industrie_1")) == null, "cycle écarté");
        helper.assertTrue(registry.get(id("t/civil_4")) == null, "palier sans prédécesseur écarté");
        helper.assertTrue(registry.get(id("t/militaire_1")) == null, "bonus inconnu écarté");
        helper.assertTrue(registry.get(id("t/ressources_1")) == null, "objet inconnu écarté");
        helper.assertTrue(registry.get(id("t/technologie_9")) == null, "palier hors limites écarté");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_research")
    public static void researchLifecycle(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResearchRegistry registry = ResearchRegistry.server();
        ServerPlayer chef = founder(helper, "sf_chercheur");
        expect(helper, NationManager.create(chef, uniqueName(helper, "TestRecherche")), true, "création");
        try {
            lifecycle(helper, server, registry, chef);
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    private static void lifecycle(GameTestHelper helper, MinecraftServer server, ResearchRegistry registry, ServerPlayer chef) {
        Nation nation = NationData.get(server).nationOf(chef.getUUID());

        ResearchTier civil1 = registry.get(id("civil_1"));
        ResearchTier civil2 = registry.get(id("civil_2"));
        helper.assertTrue(civil1 != null && civil2 != null, "civil_1 et civil_2 chargés");
        helper.assertTrue(ResearchManager.status(nation, registry, civil2) == ResearchManager.Status.LOCKED, "civil_2 verrouillé");
        expect(helper, ResearchManager.start(chef, civil1.id()), false, "lancement sans ressources");

        fund(server, nation, civil1);
        long treasuryBefore = nation.treasury();
        expect(helper, ResearchManager.start(chef, civil1.id()), true, "lancement de civil_1");
        helper.assertTrue(nation.treasury() == treasuryBefore - civil1.definition().cost().treasury(), "trésorerie débitée");
        for (ResearchDefinition.ItemCost cost : civil1.definition().cost().items()) {
            helper.assertTrue(ResearchManager.count(nation.chest(), cost.item()) == 0, "objets consommés : " + cost.item());
        }
        helper.assertTrue(nation.research().active() != null, "recherche active");
        expect(helper, ResearchManager.start(chef, civil2.id()), false, "une seule recherche à la fois");

        long beforeCancel = nation.treasury();
        expect(helper, ResearchManager.cancel(chef), true, "annulation");
        helper.assertTrue(nation.research().active() == null, "plus de recherche active");
        helper.assertTrue(nation.treasury() == beforeCancel + civil1.definition().cost().treasury() / 2, "remboursement de 50 %");

        fund(server, nation, civil1);
        expect(helper, ResearchManager.start(chef, civil1.id()), true, "relance de civil_1");
        NationResearch.Active active = nation.research().active();
        nation.research().setActive(new NationResearch.Active(active.id(), active.startEpochMs() - 10_000_000L,
                active.startEpochMs() - 1L, active.paidTreasury()));
        ResearchManager.completeDue(server, System.currentTimeMillis());
        helper.assertTrue(nation.research().isCompleted(civil1.id()), "recherche terminée à l'échéance (epoch)");
        helper.assertTrue(Math.abs(nation.research().bonus(ResearchStat.POPULATION_MULTIPLIER) - 0.05D) < 1e-9, "bonus de population +5 %");
        helper.assertTrue(ResearchManager.status(nation, registry, civil2) == ResearchManager.Status.AVAILABLE, "civil_2 débloqué");

        ResearchManager.adminComplete(server, nation, civil2);
        helper.assertTrue(NationManager.claimCost(nation) == 103L, "claim_cost_multiplier -5 % : ceil(108 × 0,95) = 103, obtenu "
                + NationManager.claimCost(nation));
    }

    /** L'annulation rend une part de la trésorerie réellement payée au lancement, pas du coût courant. */
    @GameTest(template = AREA, batch = "sf_research_refund")
    public static void cancelRefundsAmountPaid(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTier civil1 = ResearchRegistry.server().get(id("civil_1"));
        helper.assertTrue(civil1 != null, "civil_1 chargé");
        ServerPlayer chef = founder(helper, "sf_rembourse");
        expect(helper, NationManager.create(chef, uniqueName(helper, "TestRembourse")), true, "création");
        try {
            Nation nation = NationData.get(server).nationOf(chef.getUUID());
            double rate = SFConfig.RESEARCH_CANCEL_REFUND.get();
            long cost = civil1.definition().cost().treasury();

            fund(server, nation, civil1);
            expect(helper, ResearchManager.start(chef, civil1.id()), true, "lancement de civil_1");
            NationResearch.Active active = nation.research().active();
            helper.assertTrue(active != null && active.paidTreasury() == cost, "montant payé enregistré au lancement");

            // Coût du palier modifié depuis le lancement (datapack + /reload) : seul le montant payé compte.
            nation.research().setActive(new NationResearch.Active(active.id(), active.startEpochMs(), active.endEpochMs(), 300L));
            long before = nation.treasury();
            expect(helper, ResearchManager.cancel(chef), true, "annulation");
            helper.assertTrue(nation.treasury() == before + (long) Math.floor(300L * rate),
                    "remboursement calculé sur le montant payé, obtenu " + (nation.treasury() - before));

            // Palier retiré du registre : le montant payé reste remboursé.
            long now = System.currentTimeMillis();
            nation.research().setActive(new NationResearch.Active(id("palier_retire"), now, now + 60_000L, 800L));
            before = nation.treasury();
            expect(helper, ResearchManager.cancel(chef), true, "annulation d'un palier retiré");
            helper.assertTrue(nation.treasury() == before + (long) Math.floor(800L * rate),
                    "palier retiré : remboursement conservé, obtenu " + (nation.treasury() - before));

            // Recherche d'une sauvegarde antérieure (montant inconnu) : repli sur la définition courante.
            nation.research().setActive(new NationResearch.Active(civil1.id(), now, now + 60_000L, NationResearch.Active.PAID_UNKNOWN));
            before = nation.treasury();
            expect(helper, ResearchManager.cancel(chef), true, "annulation d'une recherche d'ancienne sauvegarde");
            helper.assertTrue(nation.treasury() == before + (long) Math.floor(cost * rate),
                    "ancienne sauvegarde : repli sur le coût de la définition, obtenu " + (nation.treasury() - before));

            // Écran Science : seule la recherche affichée peut être annulée.
            nation.research().setActive(new NationResearch.Active(civil1.id(), now, now + 60_000L, cost));
            expect(helper, ResearchManager.cancel(chef, id("civil_2")), false, "annulation d'un palier qui n'est pas en cours");
            helper.assertTrue(nation.research().active() != null, "la recherche en cours n'est pas annulée");
            expect(helper, ResearchManager.cancel(chef, civil1.id()), true, "annulation du palier affiché");
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void activeResearchSavesAmountPaid(GameTestHelper helper) {
        NationResearch research = new NationResearch();
        research.setActive(new NationResearch.Active(id("civil_3"), 1_000L, 61_000L, 1234L));
        CompoundTag tag = research.save();
        NationResearch.Active loaded = NationResearch.load(tag).active();
        helper.assertTrue(loaded != null && loaded.paidTreasury() == 1234L, "montant payé conservé par la sauvegarde");
        tag.getCompound("active").remove("paid");
        NationResearch.Active old = NationResearch.load(tag).active();
        helper.assertTrue(old != null && old.paidTreasury() == NationResearch.Active.PAID_UNKNOWN,
                "sauvegarde sans montant payé : montant inconnu");
        helper.succeed();
    }

    /** Un même objet cité deux fois dans un coût (datapack) est exigé pour la somme des quantités. */
    @GameTest(template = AREA, batch = "sf_research_items")
    public static void duplicateItemCostsAreSummed(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("t/industrie_1"), json("{\"domain\":\"industrie\",\"tier\":1,\"cost\":{\"items\":["
                + "{\"item\":\"minecraft:iron_ingot\",\"count\":32},{\"item\":\"minecraft:iron_ingot\",\"count\":32}]}}"));
        ResearchRegistry registry = ResearchReloadListener.build(files);
        ResearchTier tier = registry.get(id("t/industrie_1"));
        helper.assertTrue(tier != null, "palier de test chargé");
        int total = ResearchManager.itemTotals(tier.definition().cost()).getOrDefault(Items.IRON_INGOT, 0);
        helper.assertTrue(total == 64, "quantités additionnées : 64 attendus, obtenu " + total);
        ServerPlayer chef = founder(helper, "sf_doublons");
        expect(helper, NationManager.create(chef, uniqueName(helper, "TestDoublons")), true, "création");
        try {
            Nation nation = NationData.get(helper.getLevel().getServer()).nationOf(chef.getUUID());
            nation.chest().addItem(new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(!ResearchManager.blockers(nation, registry, tier).isEmpty(),
                    "32 lingots ne couvrent pas 2 × 32 lingots");
            nation.chest().addItem(new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(ResearchManager.blockers(nation, registry, tier).isEmpty(), "64 lingots suffisent");
        } finally {
            // Coffre non vide = dissolution refusée : la nation resterait et bloquerait les fondations voisines.
            Nation leftover = NationData.get(helper.getLevel().getServer()).nationOf(chef.getUUID());
            if (leftover != null) {
                leftover.chest().clearContent();
            }
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    /** Seuls les objets endommagés, renommés ou enchantés sont écartés : une fusée de vol 3 compte. */
    @GameTest(template = AREA)
    public static void chestCountIgnoresHarmlessComponents(GameTestHelper helper) {
        SimpleContainer chest = new SimpleContainer(9);
        ItemStack longFlight = new ItemStack(Items.FIREWORK_ROCKET, 16);
        longFlight.set(DataComponents.FIREWORKS, new Fireworks(3, List.of()));
        ItemStack renamed = new ItemStack(Items.FIREWORK_ROCKET, 16);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Fusée"));
        chest.setItem(0, new ItemStack(Items.FIREWORK_ROCKET, 16));
        chest.setItem(1, longFlight);
        chest.setItem(2, renamed);
        int rockets = ResearchManager.count(chest, Items.FIREWORK_ROCKET);
        helper.assertTrue(rockets == 32, "fusées : vol 3 comptées, renommées écartées ; 32 attendues, obtenu " + rockets);

        ItemStack damaged = new ItemStack(Items.CROSSBOW);
        damaged.setDamageValue(10);
        ItemStack enchanted = new ItemStack(Items.CROSSBOW);
        enchanted.enchant(helper.getLevel().registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                .getHolderOrThrow(Enchantments.UNBREAKING), 1);
        chest.setItem(3, new ItemStack(Items.CROSSBOW));
        chest.setItem(4, damaged);
        chest.setItem(5, enchanted);
        int crossbows = ResearchManager.count(chest, Items.CROSSBOW);
        helper.assertTrue(crossbows == 1, "arbalètes endommagée et enchantée écartées ; 1 attendue, obtenu " + crossbows);
        helper.succeed();
    }

    /** Joueur factice placé au centre du chunk de la zone de test, qui deviendra la capitale. */
    /** Une confirmation restée ouverte ne doit pas annuler une recherche relancée sur le même palier. */
    @GameTest(template = AREA, batch = "sf_research_stale")
    public static void cancelRefusesRestartedRun(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResearchTier civil1 = ResearchRegistry.server().get(id("civil_1"));
        helper.assertTrue(civil1 != null, "civil_1 chargé");
        ServerPlayer chef = founder(helper, "sf_relance");
        expect(helper, NationManager.create(chef, uniqueName(helper, "TestRelance")), true, "création");
        try {
            Nation nation = NationData.get(server).nationOf(chef.getUUID());
            fund(server, nation, civil1);
            expect(helper, ResearchManager.start(chef, civil1.id()), true, "premier lancement");
            long firstStart = nation.research().active().startEpochMs();
            expect(helper, ResearchManager.cancel(chef), true, "annulation");

            fund(server, nation, civil1);
            expect(helper, ResearchManager.start(chef, civil1.id()), true, "relance du même palier");
            long secondStart = nation.research().active().startEpochMs();
            // Confirmation périmée : même palier, mais un autre début que celui de la recherche en cours.
            expect(helper, ResearchManager.cancel(chef, civil1.id(), firstStart - 1L), false, "début périmé refusé");
            helper.assertTrue(nation.research().active() != null, "la recherche relancée n'est pas annulée");
            expect(helper, ResearchManager.cancel(chef, civil1.id(), secondStart), true, "début affiché accepté");
        } finally {
            NationManager.disband(chef);
        }
        helper.succeed();
    }

    private static ServerPlayer founder(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        BlockPos center = new ChunkPos(helper.absolutePos(BlockPos.ZERO)).getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY());
        player.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D);
        return player;
    }

    /** Le monde de test persiste entre deux exécutions : nom de nation dérivé de la position de la zone. */
    private static String uniqueName(GameTestHelper helper, String prefix) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        return prefix + Math.floorMod(origin.getX() ^ origin.getZ(), 100000);
    }

    /** Remplit le coffre national et la trésorerie pour pouvoir lancer {@code tier}. */
    private static void fund(MinecraftServer server, Nation nation, ResearchTier tier) {
        NationManager.adjustTreasury(nation, tier.definition().cost().treasury());
        for (ResearchDefinition.ItemCost cost : tier.definition().cost().items()) {
            int left = cost.count();
            while (left > 0) {
                int n = Math.min(left, cost.item().getDefaultMaxStackSize());
                nation.chest().addItem(new ItemStack(cost.item(), n));
                left -= n;
            }
        }
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static void expect(GameTestHelper helper, ActionResult result, boolean success, String step) {
        helper.assertTrue(result.success() == success,
                step + " : attendu " + (success ? "succès" : "échec") + ", obtenu « " + result.message().getString() + " »");
    }
}
