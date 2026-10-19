package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outils communs aux GameTests du J5 (docs/conception/J5.md §7). Règles : gabarit {@link #AREA} ; tout test qui fonde
 * une nation ou remplace le registre des planètes a son propre lot {@code sf_j5_<test>} ; noms uniques
 * ({@link J4TestUtil#unique}) ; nettoyage dans un {@code finally}.
 * <p>
 * Le serveur de GameTests n'a pas les dimensions des planètes (seuls l'Overworld, le Nether et l'End existent) : les
 * tests de vol et d'environnement utilisent une <b>planète de test</b> liée à {@code minecraft:the_end}
 * ({@link #testPlanet}), publiée par {@link #withPlanets}.
 */
public final class J5TestUtil {
    public static final String AREA = J4TestUtil.AREA;

    private J5TestUtil() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    public static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        return J4TestUtil.fakePlayer(helper, name);
    }

    /**
     * Fonde une nation dont {@code chef} est le chef (capitale : chunk de l'origine du test) et lui accorde les paliers
     * {@code tiers} ({@code "technologie_2"}…) par {@link ResearchManager#adminComplete}, sans leurs prérequis.
     */
    public static Nation foundNation(GameTestHelper helper, ServerPlayer chef, String base, String... tiers) {
        Nation nation = J4TestUtil.foundNation(helper, chef, base);
        complete(helper, nation, tiers);
        return nation;
    }

    /** Termine les paliers {@code tiers} pour la nation (échec du test si un palier est inconnu). */
    public static void complete(GameTestHelper helper, Nation nation, String... tiers) {
        for (String tier : tiers) {
            ResearchTier t = ResearchRegistry.server().get(id(tier));
            helper.assertTrue(t != null, "palier de recherche " + tier + " chargé");
            ResearchManager.adminComplete(helper.getLevel().getServer(), nation, t);
        }
    }

    /** Dissolution de nettoyage (coffre national vidé d'abord). */
    public static void disband(ServerPlayer chef) {
        J4TestUtil.disband(chef);
    }

    /** Analyse stricte d'une planète écrite en ligne ({@code data/sovereignfrontier/planets/<name>.json}). */
    public static PlanetRegistry parsePlanets(String... nameThenJson) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        for (int i = 0; i + 1 < nameThenJson.length; i += 2) {
            files.put(id(nameThenJson[i]), JsonParser.parseString(nameThenJson[i + 1]));
        }
        return PlanetRegistry.parse(files);
    }

    /**
     * JSON d'une planète de test semblable à la Lune (carburant 3 ou 12 bouteilles pleines à l'aller, 1 ou 4 au retour,
     * oxygène sans froid, rayon 2 000, réserve de gaz {@code gas}), liée à {@code dimension}.
     */
    public static String planetJson(ResourceKey<Level> dimension, long gas) {
        return """
                {"dimension": "%s", "requires": ["rocket", "dimension.lune"],
                 "outbound": [{"item": "sovereignfrontier:carburant_fusee", "count": 3},
                              {"item": "sovereignfrontier:bouteille_gaz_pleine", "count": 12}],
                 "return": [{"item": "sovereignfrontier:carburant_fusee", "count": 1},
                            {"item": "sovereignfrontier:bouteille_gaz_pleine", "count": 4}],
                 "radius": 2000, "environment": {"oxygen": true, "cold": false}, "gas_reserve": %d,
                 "solar_multiplier": 1.5,
                 "mining": {"unlock": "mining.lunar", "blocks": "#sovereignfrontier:minerais/lune"}}
                """.formatted(dimension.location(), gas);
    }

    /** Planète de test {@code name} liée à {@code minecraft:the_end} (voir {@link #planetJson}), validée. */
    public static PlanetDefinition testPlanet(GameTestHelper helper, String name, long gas) {
        PlanetRegistry parsed = parsePlanets(name, planetJson(Level.END, gas)).validated(ResearchRegistry.server());
        PlanetDefinition planet = parsed.byName(name);
        helper.assertTrue(planet != null, "planète de test « " + name + " » valide");
        return planet;
    }

    /**
     * Publie {@code planets} le temps de {@code body}, puis rétablit le registre précédent quoi qu'il arrive. À utiliser
     * dans un lot propre au test (registre global).
     */
    public static void withPlanets(List<PlanetDefinition> planets, Runnable body) {
        PlanetRegistry previous = PlanetRegistry.replaceForTests(new PlanetRegistry(planets));
        try {
            body.run();
        } finally {
            PlanetRegistry.replaceForTests(previous);
        }
    }
}
