package io.github.theoblaise1.sovereignfrontier.research;

import java.util.List;

/**
 * Clés de déblocage consommées par le code du J5 (catalogue de docs/recherche.md), plus {@code refinery} (marquée J3
 * mais câblée au J5). Un déblocage verrouille l'effet, pas seulement la recette (docs/conception/J5.md §1.1) : les
 * machines, objets, vols et minerais vérifient ces clés pour la nation concernée
 * ({@link NationResearch#hasUnlock}).
 * <p>
 * {@code tools/check_research.py} (contrôle 9) ne compte pas les littéraux de ce fichier comme consommateurs : une clé
 * n'est câblée que si une constante est référencée ailleurs ({@code UnlockKeys.ROCKET}), ou par un champ de données
 * (recette d'ingénierie, planète).
 */
public final class UnlockKeys {
    /** {@code industrie_8} : onglet Aérospatial de la Station d'ingénierie. */
    public static final String STATION_AEROSPACE = "station.aerospace";
    /** {@code industrie_8} : fusée ; tout vol l'exige. */
    public static final String ROCKET = "rocket";
    /** {@code ressources_12} : recette de la Raffinerie. */
    public static final String REFINERY = "refinery";
    /** {@code industrie_9} : la Raffinerie produit du carburant de fusée. */
    public static final String ROCKET_FUEL = "rocket.fuel";
    /** {@code technologie_2} : destination Lune, bouteilles d'oxygène, revendication sur la Lune. */
    public static final String DIMENSION_LUNE = "dimension.lune";
    /** {@code technologie_3} : Diffuseur d'oxygène. */
    public static final String OXYGEN_SYSTEMS = "oxygen.systems";
    /** {@code ressources_13} : casser le minerai de titane. */
    public static final String MINING_LUNAR = "mining.lunar";
    /** {@code technologie_4} : destination Mars. */
    public static final String DIMENSION_MARS = "dimension.mars";
    /** {@code technologie_5} : Propulseur avancé (carburant × 0,75). */
    public static final String PROPULSION_ADVANCED = "propulsion.advanced";
    /** {@code ressources_14} : casser le minerai de soufre. */
    public static final String MINING_MARTIAN = "mining.martian";
    /** {@code industrie_11} : condition de la 3e destination, contrat de mission du module. */
    public static final String ROCKET_UPGRADE_MODULE = "rocket.upgrade_module";
    /** {@code technologie_6} : Plastron chauffant. */
    public static final String ARMOR_GAS_HEATED = "armor.gas_heated";
    /** {@code technologie_6} : Chaudière à gaz. */
    public static final String BOILER_GAS = "boiler.gas";
    /** {@code ressources_15} : casser le minerai cryogénique. */
    public static final String MINING_CRYOGENIC = "mining.cryogenic";
    /** {@code industrie_12} : Réchauffeur nucléaire. */
    public static final String HEATER_NUCLEAR = "heater.nuclear";
    /** {@code technologie_7} : destination planète glacée. */
    public static final String DIMENSION_PLANETE_GLACEE = "dimension.planete_glacee";

    /** Les 16 clés câblées par le J5 (15 du catalogue J5, plus {@code refinery}). */
    public static final List<String> J5 = List.of(STATION_AEROSPACE, ROCKET, REFINERY, ROCKET_FUEL, DIMENSION_LUNE,
            OXYGEN_SYSTEMS, MINING_LUNAR, DIMENSION_MARS, PROPULSION_ADVANCED, MINING_MARTIAN, ROCKET_UPGRADE_MODULE,
            ARMOR_GAS_HEATED, BOILER_GAS, MINING_CRYOGENIC, HEATER_NUCLEAR, DIMENSION_PLANETE_GLACEE);

    private UnlockKeys() {
    }
}
