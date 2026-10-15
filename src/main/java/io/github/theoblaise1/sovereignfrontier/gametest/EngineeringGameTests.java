package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRegistry;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringTab;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** GameTests de la Station d'ingénierie : validation des recettes des datapacks et ordre stable de la liste. */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class EngineeringGameTests {
    private static final String AREA = "gametest/empty9";
    private static final String IRON = "{\"item\": \"minecraft:iron_ingot\", \"count\": 2}";
    private static final String COPPER = "{\"item\": \"minecraft:copper_ingot\", \"count\": 1}";

    private EngineeringGameTests() {
    }

    /** Recette JSON telle qu'un datapack la fournit (onglet machines). */
    private static String recipe(String ingredients, String result) {
        return "{\"tab\": \"machines\", \"ingredients\": [" + ingredients + "], \"result\": " + result + "}";
    }

    private static DataResult<EngineeringRecipe.Definition> parse(String json) {
        return EngineeringRecipe.Definition.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json));
    }

    private static EngineeringRecipe named(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
        return new EngineeringRecipe(id, parse(recipe(IRON, COPPER)).result().orElseThrow());
    }

    @GameTest(template = AREA)
    public static void duplicateIngredientIsRejected(GameTestHelper helper) {
        helper.assertTrue(parse(recipe(IRON + "," + IRON, COPPER)).error().isPresent(),
                "deux entrées pour le même objet doivent être rejetées (stock vérifié deux fois)");
        helper.assertTrue(parse(recipe(IRON, COPPER)).result().isPresent(), "une recette valide doit être acceptée");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void resultLargerThanStackIsRejected(GameTestHelper helper) {
        helper.assertTrue(parse(recipe(IRON, "{\"item\": \"minecraft:iron_nugget\", \"count\": 200}")).error().isPresent(),
                "un résultat au-delà d'une pile doit être rejeté");
        helper.assertTrue(parse(recipe(IRON, "{\"item\": \"minecraft:iron_nugget\", \"count\": 64}")).result().isPresent(),
                "un résultat d'une pile entière doit être accepté");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void recipesListedInIdOrder(GameTestHelper helper) {
        EngineeringRegistry registry = new EngineeringRegistry(List.of(named("zeta_test"), named("alpha_test")));
        List<EngineeringRecipe> machines = registry.tab(EngineeringTab.MACHINES);
        helper.assertTrue(machines.size() == 2, "deux recettes attendues dans l'onglet machines, obtenu " + machines.size());
        helper.assertTrue(machines.get(0).id().getPath().equals("alpha_test") && machines.get(1).id().getPath().equals("zeta_test"),
                "ordre par identifiant attendu, indépendant de l'ordre de chargement");
        helper.succeed();
    }
}
