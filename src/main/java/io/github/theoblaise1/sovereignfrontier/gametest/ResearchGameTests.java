package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.LinkedHashMap;
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
        ServerPlayer chef = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "sf_chercheur"));
        BlockPos center = new ChunkPos(helper.absolutePos(BlockPos.ZERO)).getMiddleBlockPosition(helper.absolutePos(BlockPos.ZERO).getY());
        chef.moveTo(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D);
        expect(helper, NationManager.create(chef, "TestRecherche"), true, "création");
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
        nation.research().setActive(new NationResearch.Active(active.id(), active.startEpochMs() - 10_000_000L, active.startEpochMs() - 1L));
        ResearchManager.completeDue(server, System.currentTimeMillis());
        helper.assertTrue(nation.research().isCompleted(civil1.id()), "recherche terminée à l'échéance (epoch)");
        helper.assertTrue(Math.abs(nation.research().bonus(ResearchStat.POPULATION_MULTIPLIER) - 0.05D) < 1e-9, "bonus de population +5 %");
        helper.assertTrue(ResearchManager.status(nation, registry, civil2) == ResearchManager.Status.AVAILABLE, "civil_2 débloqué");

        ResearchManager.adminComplete(server, nation, civil2);
        helper.assertTrue(NationManager.claimCost(nation) == 103L, "claim_cost_multiplier -5 % : ceil(108 × 0,95) = 103, obtenu "
                + NationManager.claimCost(nation));

        expect(helper, NationManager.disband(chef), true, "dissolution");
        helper.succeed();
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
