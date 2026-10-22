# Espace : planètes, fusée, oxygène et froid — contrat de données (J5)

Ce document est le **contrat** entre le contenu JSON (`src/main/resources/data/sovereignfrontier/`, modifiable par
un datapack de monde) et le code du programme spatial : où vivent les données, format de chaque fichier, règles de
validation, et ce que le code attend des dimensions. Les valeurs d'équilibrage globales (compte à rebours, oxygène,
froid, chaleur…) sont dans la configuration serveur, section `[space]` ; les valeurs propres à une planète (coûts de
vol, rayon, gaz, solaire, environnement) sont du **contenu** et vivent dans `planets/<nom>.json`. La conception
complète est dans [`docs/conception/J5.md`](conception/J5.md) ; ce qui diffère du code livré est signalé ici.

`python3 tools/check_space.py` vérifie tout ce qui concerne les planètes et leurs dimensions ; le câblage des
déblocages est contrôlé par `tools/check_research.py` (voir « Validation »).

## Règles du moteur
- **Chargement strict des planètes.** `data/<ns>/planets/` est lu par un écouteur de rechargement (`/reload` en jeu).
  Un fichier invalide (champ inconnu, borne dépassée, objet ou clé inconnus…) est **ignoré** avec une ligne ERREUR en
  français (« Planète sovereignfrontier:mars ignorée : … ») ; les autres restent chargées. Une planète ignorée devient
  indisponible : elle ne retombe pas sur l'ancienne version.
- **Publication atomique.** L'écouteur ne construit qu'un candidat. Les validations qui demandent les tags et le
  registre de recherche (clés de déblocage connues, tag de minerai existant et non vide, module membre de
  `#sovereignfrontier:restreint/fusee_finale`, une planète par dimension et par nom, 8 planètes au plus) sont faites
  au `TagsUpdatedEvent` (`SERVER_DATA_LOAD`, priorité haute : les planètes sont publiées avant les contrats qui
  ciblent leur dimension), puis le registre publié est remplacé d'un coup. Journal : « Sovereign Frontier : N
  planète(s) publiée(s) : lune, mars, planete_glacee ».
- **Constantes de code.** Les bornes de validation (tableau « Planètes ») sont des constantes Java
  (`PlanetDefinition`), **jamais** lues dans `SFConfig` : la configuration serveur n'est chargée qu'après le premier
  chargement des datapacks. La section `[space]` n'est lue qu'à l'exécution.
- **Dimensions : au démarrage seulement.** Les JSON `dimension/`, `dimension_type/` et `worldgen/` sont chargés par
  Minecraft au démarrage du serveur, pas par `/reload`. Ils s'ajoutent automatiquement à un monde existant au
  démarrage suivant. Une erreur dans l'un d'eux empêche le serveur de démarrer (d'où `check_space.py` en CI). Au
  `ServerStartedEvent`, chaque planète publiée dont le niveau est absent produit une ligne ERREUR « Dimension
  sovereignfrontier:mars absente : vols vers la planète « mars » indisponibles » ; c'est normal sur le serveur de
  GameTests, qui n'a que l'Overworld, le Nether et l'End.
- **Instantanés et temps.** Un compte à rebours relit la planète à chaque contrôle : si un `/reload` l'a retirée, le
  vol est annulé sans rien consommer. Délai de vol, acclimatation et premier atterrissage : timestamps epoch
  persistés ; compte à rebours : non persisté (un arrêt l'annule, rien n'est consommé) ; le transfert tient en un
  seul tick du thread principal.

## Où vivent les données

| Contenu | Fichier (`data/sovereignfrontier/…`) | Identifiant | Classe | Ligne du journal au démarrage |
|---|---|---|---|---|
| Planètes | `planets/<nom>.json` | nom du fichier (`lune`, `mars`, `planete_glacee`) | `PlanetRegistry` | « N planète(s) publiée(s) : … » |
| Dimensions | `dimension/<nom>.json` | `sovereignfrontier:<nom>` | Minecraft (`LevelStem`) | — |
| Types de dimension | `dimension_type/<nom>.json` | `sovereignfrontier:<nom>` | Minecraft | — |
| Réglages de bruit | `worldgen/noise_settings/<nom>.json` | `sovereignfrontier:<nom>` | Minecraft | — |
| Biomes | `worldgen/biome/<nom>.json` | `sovereignfrontier:<nom>` | Minecraft | — |
| Minerais | `worldgen/configured_feature/minerai_<x>.json`, `worldgen/placed_feature/minerai_<x>.json` | `minerai_titane`, `minerai_soufre`, `minerai_cryogenique` | Minecraft | — |
| Fusion du titane | `recipe/lingot_titane_four.json`, `recipe/lingot_titane_haut_fourneau.json` | — | datapack vanilla | — |
| Recettes de la fusée et de l'environnement | `engineering/{coque_fusee, moteur_fusee, fusee, raffinerie, propulseur_avance, bouteille_oxygene, diffuseur_oxygene, chaudiere_gaz, plastron_chauffant, rechauffeur_nucleaire}.json` | identifiant de la recette | `EngineeringRegistry` | « N recette(s) d'ingénierie chargée(s) » |
| Minerais verrouillés | `tags/block/minerais/{lune, mars, planete_glacee}.json`, `tags/block/minerais_spatiaux.json` | `#sovereignfrontier:minerais/<planète>` | `SpaceRules` | — |
| Dégâts d'oxygène et de froid | `damage_type/{asphyxie, gel}.json` (+ `minecraft/tags/damage_type/{bypasses_armor, no_knockback}.json`) | `sovereignfrontier:asphyxie`, `:gel` | `Environment` | — |
| Butin des blocs | `loot_table/blocks/<bloc>.json` (8 blocs du J5) | table de butin vanilla | — | — |
| Composant final | `tags/item/restreint/fusee_finale.json` | `#sovereignfrontier:restreint/fusee_finale` | `RestrictedItems` | voir `docs/economie.md` |
| Contrats et caisse spatiale | `contracts/…`, `crates/spatiale.json` | voir `docs/economie.md` | | |

Le nom d'un fichier de planète **est** son identifiant : `[a-z0-9_]`, 32 caractères au plus, sans sous-dossier, et
jamais `terre` (nom réservé au retour vers l'Overworld). Le nom affiché est `sovereignfrontier.planet.<nom>` dans
`en_us.json` (repli sur l'identifiant brut pour une planète de datapack tiers) ; `check_space.py` et `check_lang.py`
vérifient sa présence pour les planètes du mod.

## Planètes — `planets/<nom>.json`
```json
{
  "dimension": "sovereignfrontier:lune",
  "requires": ["rocket", "dimension.lune"],
  "outbound": [
    {"item": "sovereignfrontier:carburant_fusee", "count": 3},
    {"item": "sovereignfrontier:bouteille_gaz_pleine", "count": 12}
  ],
  "return": [
    {"item": "sovereignfrontier:carburant_fusee", "count": 1},
    {"item": "sovereignfrontier:bouteille_gaz_pleine", "count": 4}
  ],
  "radius": 2000,
  "environment": {"oxygen": true, "cold": false},
  "gas_reserve": 400000,
  "solar_multiplier": 1.5,
  "mining": {"unlock": "mining.lunar", "blocks": "#sovereignfrontier:minerais/lune"}
}
```

| Champ | Obligatoire | Règle |
|---|---|---|
| `dimension` | oui | identifiant d'une dimension ; jamais `minecraft:overworld` ; une seule planète par dimension (sinon **toutes** celles qui la partagent sont rejetées) |
| `requires` | oui | 1 à 8 clés de déblocage distinctes, au format du catalogue de `docs/recherche.md` ; la nation du pilote doit toutes les posséder pour un vol **aller** (le retour n'exige aucune recherche) ; chaque clé doit être accordée par un palier de recherche |
| `outbound`, `return` | oui | 1 à 4 options `{item, count}` (objets distincts, `count` de 1 à 64, objets enregistrés) ; **une option = un seul objet**, essayées dans l'ordre du JSON : la première dont la quantité d'objets *intacts* (ni renommés, ni enchantés, ni abîmés) est dans la soute est retenue |
| `radius` | oui | 256 à 30 000 : rayon (blocs) de la bordure souple autour de (0, 0) |
| `environment` | oui | `{oxygen, cold}`, deux booléens : respiration obligatoire hors zone oxygénée ; froid mortel hors chaleur |
| `gas_reserve` | non | 0 à 10¹² litres : réserve de gaz **finie** de la dimension ; absente : `gasPerDimension` (`[energy]`) |
| `solar_multiplier` | non | 0 à 4 : facteur de production des panneaux solaires ; la pluie terrestre est ignorée sur une planète |
| `module` | non | objet qui doit être dans la soute pour un vol aller ; **membre de `#sovereignfrontier:restreint/fusee_finale`** (vérifié au chargement et en CI) |
| `mining` | non | `{unlock, blocks}` : clé d'extraction de la nation et tag de blocs (`#espace:chemin`, existant et non vide) des minerais verrouillés |

Au plus **8 planètes** publiées (les premières par identifiant sont gardées). Champs inconnus refusés.

Planètes livrées :

| Planète | Prérequis (`requires`) | Aller | Retour | Rayon | Environnement | Gaz | Solaire | Extraction |
|---|---|---|---|---|---|---|---|---|
| `lune` | `rocket`, `dimension.lune` | 3 carburants **ou** 12 bouteilles pleines | 1 carburant **ou** 4 bouteilles pleines | 2 000 | oxygène | 400 000 L | × 1,5 | `mining.lunar` |
| `mars` | `rocket`, `dimension.mars` | 6 carburants | 4 carburants | 3 000 | oxygène | 600 000 L | × 0,6 | `mining.martian` |
| `planete_glacee` | `rocket`, `dimension.planete_glacee`, `rocket.upgrade_module` | 10 carburants | 6 carburants | 2 000 | oxygène + froid | 1 000 000 L | × 0,3 | `mining.cryogenic` |

`planete_glacee` porte en plus `"module": "sovereignfrontier:module_cryogenique"`. Avec un **Propulseur avancé** dans
la soute et la clé `propulsion.advanced`, chaque quantité est multipliée par `rocketPropulsorFactor` (0,75), arrondie
à l'inférieur, au moins 1 : Lune 2 ou 9 bouteilles / 1 ou 3 au retour, Mars 4 / 3, planète glacée 7 / 4.

## Dimensions — JSON de datapack, sans code de génération
Trois dimensions `sovereignfrontier:lune`, `:mars` et `:planete_glacee`. Chacune est composée de :

- `dimension/<nom>.json` : `{"type": "sovereignfrontier:<nom>", "generator": {"type": "minecraft:noise", "settings":
  "sovereignfrontier:<nom>", "biome_source": {"type": "minecraft:fixed", "biome": "sovereignfrontier:<nom>"}}}` ;
- `dimension_type/<nom>.json`, avec les valeurs **imposées** par le contrôle (`check_space.py`) : `bed_works` et
  `respawn_anchor_works` faux, `has_skylight` vrai, `has_ceiling` faux, `natural` faux, `has_raids` faux, `min_y` 0 et
  `height` 256 (multiples de 16), `logical_height` ≤ `height`, `effects` = `sovereignfrontier:<nom>` (champ
  facultatif `fixed_time` toléré) ;
- `worldgen/noise_settings/<nom>.json` : relief 2D (bruit `minecraft:surface`, `y_scale` 0, donc sans surplomb),
  blocs vanilla, `sea_level` 0, ni aquifères, ni filons, ni grottes ; `noise.min_y` et `noise.height` **identiques**
  à ceux du type ; `default_block` = la roche hôte du minerai ;
- `worldgen/biome/<nom>.json` : un biome par planète (source `minecraft:fixed`), 11 listes de décoration dont la
  seule non vide est l'étape 6 (minerai), couleurs de ciel et de brouillard.

| Planète | Surface / roche | Précipitations | Créatures |
|---|---|---|---|
| Lune | gravier / andésite | non | aucune |
| Mars | sable rouge / terre cuite rouge | non | aucune |
| Planète glacée | bloc de neige / glace compactée | neige (température −0,7) | vagabonds (`minecraft:stray`) |

Hauteur 0 à 255, surface vers y = 80. Jour, nuit et météo sont ceux de l'Overworld (niveaux dérivés). Le fichier
`SpaceDimensionEffects.PLANETS` (client) doit lister chaque `<nom>` : sans cela le client retomberait sur les
effets de l'Overworld, ce que `check_space.py` refuse.

**Minerais** (un par planète), placés par une `configured_feature` `minecraft:ore` (cible `block_match` sur la roche
hôte, `size` de 1 à 64) et une `placed_feature` (`count`, `in_square`, `height_range`, `biome`), référencée à l'étape 6
du biome :

| Minerai | Hôte | Filons par chunk | Taille | Altitude | Outil minimal | Butin |
|---|---|---|---|---|---|---|
| `minerai_titane` (Lune) | andésite | 12 | 8 | 8 à 96 | pioche en fer | `titane_brut` (Fortune) ; fusion au four ou au haut fourneau → `lingot_titane` |
| `minerai_soufre` (Mars) | terre cuite rouge | 14 | 10 | 8 à 110 | pioche en pierre | 2 à 4 `soufre` (Fortune) |
| `minerai_cryogenique` (planète glacée) | glace compactée | 6 | 5 | 8 à 70 | pioche en diamant | `cristal_cryogenique` (Fortune) |

Le toucher de soie rend le bloc, qui **reste verrouillé** (voir « Verrous »).

> **Paramètres expérimentaux.** Des dimensions ajoutées par datapack rendent la durée de vie du registre
> « expérimentale » aux yeux de Minecraft : en solo, le monde affiche une fois un avertissement de confirmation ; sur
> un serveur dédié, rien. Avant de retirer une planète ou le mod d'un monde où l'on est déjà allé, ramenez les
> joueurs sur Terre (`/sfadmin space rescue <joueur>`, voir « Redémarrage »).

## Accès et verrouillage par la recherche
Un déblocage verrouille **l'effet**, pas seulement la recette : un objet donné ou volé ne contourne pas la recherche.
Les clés sont des constantes de `research/UnlockKeys.java`.

| Palier | Déblocage | Effet |
|---|---|---|
| `industrie_8` Aérospatiale | `station.aerospace`, `rocket` | onglet Aérospatial de la Station d'ingénierie ; coque, moteur et fusée ; tout vol exige `rocket` |
| `ressources_12` Raffinerie | `refinery` | recette de la Raffinerie (onglet Machines) |
| `industrie_9` Carburant de fusée | `rocket.fuel` | la Raffinerie produit du carburant (sur le territoire de la nation) |
| `technologie_2` Programme lunaire | `dimension.lune` | vol vers la Lune, revendication sur la Lune, recette de la bouteille d'oxygène |
| `technologie_3` Systèmes d'oxygène | `oxygen.systems` | Diffuseur d'oxygène : zones oxygénées |
| `ressources_13` Extraction lunaire | `mining.lunar` | casser le minerai de titane |
| `technologie_4` Programme martien | `dimension.mars` | vol vers Mars |
| `technologie_5` Propulsion avancée | `propulsion.advanced` | Propulseur avancé : carburant × 0,75 |
| `ressources_14` Extraction martienne | `mining.martian` | casser le minerai de soufre |
| `industrie_11` Module de fusée | `rocket.upgrade_module` | condition de la 3ᵉ destination ; ouvre le contrat de mission du module |
| `technologie_6` Thermorégulation | `armor.gas_heated`, `boiler.gas` | Plastron chauffant, Chaudière à gaz |
| `ressources_15` Ingénierie cryogénique | `mining.cryogenic` | casser le minerai cryogénique |
| `industrie_12` Chauffage nucléaire | `heater.nuclear` | Réchauffeur nucléaire |
| `technologie_7` Programme glaciaire | `dimension.planete_glacee` | vol vers la planète glacée |

### Verrous
- **Garde de voyage** (`SpaceEvents`, sur `EntityTravelToDimensionEvent`). Toute entité qui entre dans une planète,
  d'un autre mod comprise, doit porter un **permis de voyage** (ensemble d'UUID en mémoire, posé par le vol en fusée
  juste avant `changeDimension` et retiré juste après) ou être un joueur de niveau 2 ou plus (`/tp`, `/execute in`,
  journalisé). Le retour vers une autre dimension n'est jamais gardé. Une planète dont le JSON est rejeté reste
  gardée : les trois dimensions du mod comptent toujours comme planètes pour cette règle.
- **Revendication.** `/claim` sur une planète exige `dimension.<planète>` (message « Revendication impossible sur
  Lune : terminez d'abord la recherche « Accès à la Lune ». ») ; même coût exponentiel, contiguïté par dimension.
- **Fondation.** `/nation create` est refusé sur toute planète : la capitale doit rester accessible sans fusée.
- **Minage.** Casser un bloc de `#sovereignfrontier:minerais/<planète>` exige la clé `mining.*` de la nation du
  joueur, **dans toute dimension**. Créatif et `/sfadmin bypass` exemptés. Les minerais spatiaux sont retirés de
  toute liste de blocs détruits par une explosion.
- **Lits et ancres de réapparition.** Utilisation annulée sur une planète, avec un message, sans explosion.
- **Bordure souple.** Toutes les 20 ticks, un joueur au-delà du rayon est ramené à `rayon − 16`, à la surface
  (Créatif et spectateur exemptés).
- **Protection de la nation.** Voir « Fusée ».

## Fusée
- **Bloc** `fusee` (bloc-entité, pas d'entité de vol) : résistance 1 200, ne se pousse pas, pioche en fer. À la pose,
  la nation du poseur devient propriétaire (sans nation, la fusée est **inerte**).
- **Soute** : 9 cases (coffre vanilla d'une rangée, `GENERIC_9x1`), ouvertes par clic droit aux membres de la nation
  propriétaire ; aucun accès par entonnoir ; scellée pendant un compte à rebours. Carburant, propulseur et module y
  sont détectés ; le reste est du fret et voyage avec la fusée. Cassée, la fusée lâche sa soute.
- **Fiche et session.** Clic droit accroupi : fiche dans le chat (propriétaire, carburant de la soute, destinations ✔/✘
  avec leur coût ou la raison du refus, boutons **[Lune] [Mars] [Planète glacée] [Terre]**) et ouverture d'une
  **session de 60 s** qui désigne la fusée pour `/rocket`. Les commandes revalident côté serveur la session, la
  distance (6 blocs), la permission et la présence du bloc.
- **Topologie en étoile** : Overworld → planète, ou planète → `terre` ; jamais depuis le Nether ou l'End, jamais entre
  planètes.
- **Contrôles du lancement**, dans l'ordre (le premier échec est renvoyé, sans effet) ; ils sont refaits à chaque
  contrôle du compte à rebours et au transfert :
  1. fusée présente et propriétaire (« inerte » sans nation) ;
  2. pilote membre de la nation propriétaire avec la permission `construire`, hors délai de vol ;
  3. trajet permis depuis la dimension de la fusée ;
  4. planète publiée et son niveau présent ; clés `requires` de la nation (aller seulement) ; `module` en soute ;
  5. départ terrestre : le chunk de la fusée appartient à sa nation ;
  6. ciel dégagé : aucun bloc au-dessus de la fusée ;
  7. carburant : une option du trajet satisfaite par les objets intacts de la soute (propulseur appliqué) ;
  8. site d'atterrissage légal.
- **Compte à rebours** : `rocketCountdownTicks` (200 ticks). Le bloc passe à `launching`, un ticket de chunk est posé
  sur l'arrivée, les membres de la nation à `rocketBoardRadius` blocs reçoivent « [Embarquer] » (`/rocket board`). Une
  barre d'action annonce « Décollage dans N s — destination : … » chaque seconde. Annulé si le pilote s'éloigne de
  plus de `rocketPilotLeash` blocs, se déconnecte, meurt ou subit des dégâts, si la fusée est cassée, ou par
  `/rocket abort` ; un membre d'équipage éloigné est simplement débarqué.
- **Transfert** : au premier tick qui suit la fin du compte à rebours où le chunk d'arrivée est chargé (100 ticks
  d'attente au plus, puis chargement synchrone), tout ou rien dans le même tick : revalidation complète,
  prélèvement du carburant sur une copie de la soute, pose de la fusée à l'arrivée **avant** que la soute d'origine
  soit vidée et invalidée, retrait du bloc d'origine sans butin, puis `changeDimension` de l'équipage (permis de
  voyage). Ensuite : délai de vol de chaque voyageur, acclimatation, journal de nation.
- **Équipage** : le pilote plus les membres qui embarquent, `rocketCrewMax` (4) au plus, à `rocketBoardRadius` (4)
  blocs ; un joueur n'est que dans un équipage à la fois ; seuls les joueurs voyagent.
- **Délai** : `rocketCooldownSeconds` (60 s) entre deux vols **par joueur** (timestamp epoch persisté dans
  l'attachement `space` : casser et reposer la fusée ne le remet pas à zéro).
- **Atterrissage sur une planète** : au dernier point de décollage de cette fusée sur cette planète (`pads`), sinon
  aux (x, z) du départ, ramenés dans le disque de rayon `radius − 32`. Jamais dans le territoire d'une autre nation :
  recherche en spirale sur `rocketLandingSearchChunks` (4) chunks (81 lectures d'index au plus, aucun chargement),
  sinon refus « Zone d'atterrissage indisponible : occupée par une autre nation, ou sans sol. ».
- **Retour sur Terre** : sans condition de recherche. Au départ terrestre (`home`) si son chunk est à la nation ou
  libre, sinon au premier Trésor ou coffre national de la nation dans l'Overworld, sinon au point d'apparition du
  monde (`ReturnPoints`).
- **Protection** : explosions sans effet (`#minecraft:wither_immune`, `#minecraft:dragon_immune`, résistance 1 200) ;
  cassable seulement par un membre de la nation propriétaire, par un bâtisseur (`construire`) de la nation qui
  possède le chunk, ou par tous si la nation propriétaire n'existe plus ; la soute n'est ouverte qu'aux membres.
- **Journal de nation** : `rocket_flight` (pilote, départ, destination, équipage) et `rocket_landing` (premier
  atterrissage) ; annonce au serveur : « La nation Aurore s'est posée sur Lune pour la première fois ! ».

### Carburant et Raffinerie
- **Carburant raffiné** `carburant_fusee` (pile de 16) : produit **uniquement** par la Raffinerie, aucune recette
  d'ingénierie. 3 bouteilles de gaz pleines + 1 poudre de blaze + 200 En/t pendant `refineryCycleTicks` (200 ticks)
  donnent 1 carburant et rendent 3 bouteilles vides ; les ingrédients ne sont prélevés qu'à la fin du cycle ; la
  machine s'arrête (sans rien perdre) si une sortie est pleine. Elle ne marche que sur le territoire d'une nation
  qui possède `rocket.fuel`.
- **Avant le carburant** : la Lune s'ouvre (`technologie_2`) avant `rocket.fuel` (`industrie_9`) ; ses vols acceptent
  donc la bouteille de gaz pleine. Un aller-retour lunaire au gaz brut coûte 16 bouteilles (1 600 L).
- Le carburant est prélevé **au tick du transfert**, après revalidation : un vol annulé ne coûte rien.

## Environnement : oxygène et froid
Toutes les `20` ticks et par joueur en survie ou en aventure (Créatif et spectateur exemptés), en O(1)
(`Environment`) :

- **Oxygène** (`environment.oxygen`) : hors d'une zone oxygénée, 1 s de réserve est consommée par seconde ; à 0, une
  `bouteille_oxygene` intacte est prise (inventaire, puis main secondaire) pour `oxygenBottleSeconds` (120 s) et
  une `bouteille_gaz` vide est rendue ; sans bouteille, dégâts `sovereignfrontier:asphyxie` (`oxygenDamage` = 2,0,
  ignorent l'armure, sans recul) chaque seconde. Avertissement à `oxygenWarnSeconds` (20 s) et à la dernière
  bouteille.
- **Acclimatation** : `spaceGraceSeconds` (10 s) sans consommation ni froid après une arrivée en fusée ; à la
  connexion seulement si la précédente acclimatation date d'au moins `spaceLoginGraceCooldownMinutes` (5 min).
- **Froid** (`environment.cold`, planète glacée) : un compteur gagne 1 par seconde hors chaleur et perd 2 à l'abri ;
  au-delà de `coldThresholdSeconds` (30 s), dégâts `sovereignfrontier:gel` (`coldDamage` = 1,0, ignorent l'armure)
  toutes les `coldDamageIntervalSeconds` (2 s). Le `ticksFrozen` vanilla n'est pas utilisé (une pièce en cuir
  l'annulerait).
- **Chaleur** : une zone de chaleur couvre le joueur, ou il porte le **Plastron chauffant** (nation avec
  `armor.gas_heated`) qui brûle 1 bouteille de gaz pleine de l'inventaire par `heatedChestplateBottleSeconds` (300 s)
  d'exposition et rend la bouteille vide.
- À la réapparition, oxygène, froid et charge du plastron repartent de 0.

### Zones et machines
Les zones sont indexées par chunk (`ZoneIndex`, en mémoire) ; une machine active en rafraîchit la durée de vie (40
ticks) toutes les 20 ticks. Une machine qui s'arrête, perd sa clé ou son territoire, ou dont le chunk se décharge,
laisse expirer sa zone.

| Machine | Clé | Énergie | Cases | Zone |
|---|---|---|---|---|
| Raffinerie (`raffinerie`) | `refinery` (machine), `rocket.fuel` (procédé) | consommatrice : 200 En/t pendant le cycle (capacité 50 000, entrée 400 En/t) | gaz plein, poudre de blaze (entrée) ; carburant, bouteilles vides (sortie) | — |
| Diffuseur d'oxygène (`diffuseur_oxygene`) | `oxygen.systems` | consommateur : 20 En/t (`diffuserEnergyPerTick`) | — | `(2r+1)²` chunks centrés sur lui (`diffuserRadiusChunks` = 1, soit 3 × 3), **limités aux chunks de sa nation**, ± 32 blocs en hauteur ; tout joueur y respire, quelle que soit sa nation |
| Chaudière à gaz (`chaudiere_gaz`) | `boiler.gas` | aucune | bouteille pleine (entrée), bouteille vide (sortie) | brûle 1 bouteille pleine par `boilerBottleTicks` (2 400) ; rayon 50, ± 32 |
| Réchauffeur nucléaire (`rechauffeur_nucleaire`) | `heater.nuclear` | aucune (ne produit pas d'énergie) | combustible enrichi | 1 combustible par `nuclearHeaterFuelTicks` (12 000) ; rayon 200, ± 96 |

Les machines ne fonctionnent que sur le territoire d'une nation qui possède leur clé (une lecture d'index). La zone
d'un diffuseur s'arrête à la frontière ; la chaleur d'une chaudière ou d'un réchauffeur rayonne ensuite sans tenir
compte des frontières. Sur une planète, la production solaire ignore la pluie terrestre et est multipliée par
`solar_multiplier`.

## Redémarrage et cas limites
- **Joueur déconnecté sur une planète** : il reste sur place ; à la connexion, acclimatation de 10 s si la précédente
  date d'au moins 5 min ; les zones se reconstruisent dans les 20 ticks par les machines.
- **Repère de planète et secours.** `SpaceRules` écrit dans les données persistantes du joueur
  (`getPersistentData()`) un repère `sovereignfrontier:planete` (identifiant de la dimension) toutes les 20 ticks et
  à la déconnexion, tant qu'il est dans une dimension de planète ; il l'efface dès qu'il est observé ailleurs. À la
  connexion, si le repère existe, que le joueur est dans l'Overworld et que le niveau désigné par le repère n'existe
  plus sur le serveur (datapack retiré, JSON invalide…), le joueur est ramené sur Terre (point de retour, voir
  « Fusée ») avec le message « Votre planète n'existe plus sur ce serveur : vous avez été ramené(e) sur Terre. » et
  une ligne d'avertissement au journal. `SpaceState.lastPlanet`, écrit par le vol, n'est pas lu par ce mécanisme.
- **Planète rejetée par un `/reload`** (JSON invalide) alors que des joueurs y sont : ils restent sur place, sans
  règle d'oxygène ni de froid (plus de définition) et sans `/rocket launch terre` ; `/sfadmin space rescue <joueur>`
  les ramène.
- **Joueur exclu de sa nation ou recherche révoquée sur une planète** : il peut rester ; le retour ne demande aucune
  recherche. Sans fusée de sa nation : mort (réapparition sur Terre) ou `/sfadmin space rescue`.
- **Arrêt pendant un compte à rebours** : le vol est annulé, rien n'est consommé. **Arrêt pendant un vol** :
  impossible, le transfert tient en un tick.

## Configuration — section `[space]`
`<monde>/serverconfig/sovereignfrontier-server.toml`, rechargée à chaud (lue à l'exécution seulement).

| Clé | Défaut | Plage |
|---|---|---|
| `rocketCountdownTicks` | 200 | 60..1200 |
| `rocketCrewMax` | 4 | 1..10 |
| `rocketBoardRadius` / `rocketPilotLeash` | 4 / 6 | 2..16 / 3..32 |
| `rocketCooldownSeconds` | 60 | 0..86 400 |
| `rocketLandingSearchChunks` | 4 | 0..8 |
| `rocketPropulsorFactor` | 0,75 | 0,1..1,0 |
| `refineryEnergyPerTick` / `refineryCycleTicks` | 200 / 200 | 1..100 000 / 20..72 000 |
| `oxygenBottleSeconds` | 120 | 10..3600 |
| `oxygenDamage` / `oxygenWarnSeconds` | 2,0 / 20 | 0..40 / 0..600 |
| `spaceGraceSeconds` / `spaceLoginGraceCooldownMinutes` | 10 / 5 | 0..120 / 0..1440 |
| `diffuserEnergyPerTick` / `diffuserRadiusChunks` / `diffuserHalfHeight` | 20 / 1 / 32 | 0..100 000 / 0..2 / 4..128 |
| `coldThresholdSeconds` / `coldDamage` / `coldDamageIntervalSeconds` | 30 / 1,0 / 2 | 1..600 / 0..40 / 1..60 |
| `boilerRadius` / `boilerBottleTicks` / `boilerHalfHeight` | 50 / 2400 / 32 | 8..128 / 200..72 000 / 4..128 |
| `nuclearHeaterRadius` / `nuclearHeaterFuelTicks` / `nuclearHeaterHalfHeight` | 200 / 12 000 / 96 | 16..256 / 200..720 000 / 4..128 |
| `heatedChestplateBottleSeconds` | 300 | 10..3600 |

## Données persistantes
| Où | Contenu |
|---|---|
| Bloc-entité `fusee` | `owner` (UUID de la nation), `home` (`dim`, `pos` : dernier départ terrestre), `pads` (dernier décollage par dimension), `Items` (soute). Le compte à rebours n'est pas persisté. |
| Attachement joueur `sovereignfrontier:space` (persistant, copié à la mort) | `oxygen`, `cold`, `heat` (secondes), `grace_until`, `last_login_grace`, `next_flight` (epoch ms), `return_point`, `last_planet`. |
| Données persistantes du joueur | repère `sovereignfrontier:planete` (secours à la connexion). |
| NBT de la nation, clé `space` | `landed` (premier atterrissage par dimension, epoch ms), `flights` (nombre de vols). |
| `MachineBlockEntity` | aucun champ nouveau (la progression est persistée dans le NBT, clé `progress`) : la Raffinerie utilise la progression, la Chaudière la combustion, le Réchauffeur le combustible. |
| `GasData` (par dimension) | format inchangé (`extracted`) ; la réserve lue est celle de la planète, sinon `gasPerDimension`. |
| En mémoire | zones (`ZoneIndex`), sessions de fusée (60 s), comptes à rebours, équipages, permis de voyage. Tout est vidé à l'arrêt. |

## Commandes
| Commande | Effet |
|---|---|
| `/rocket` · `/rocket launch <destination>` | Fiche de la fusée de session · lancer (`lune`, `mars`, `planete_glacee`, `terre` ; Tab propose les destinations permises). |
| `/rocket board` · `/rocket abort` | Embarquer dans le décollage en cours (membre, 4 blocs au plus) · annuler le décollage (pilote). |
| `/oxygen` | État spatial : planète, oxygène, froid, zone, acclimatation, point de retour. |
| `/sfadmin space info` · `/sfadmin space rescue <joueur>` | Une ligne par planète publiée (dimension, joueurs, rayon, extraction) · ramener un joueur sur Terre. |
| `/sfadmin rocket info` · `/sfadmin rocket cooldown <joueur> reset` | Données de la fusée de session · remise à zéro du délai de vol. |
| `/sfadmin oxygen <joueur> <secondes>` · `/sfadmin cold <joueur> <secondes>` | Fixer la réserve d'oxygène ou le compteur de froid (0..3600). |

## Objets interdits
Le **Module cryogénique** est le seul composant final du J5 (`#sovereignfrontier:restreint/fusee_finale`, donc aussi
dans `#restreint` et `#interdit_caisses`). Il ne se fabrique pas et ne sort d'aucune caisse, d'aucun marchand,
d'aucune entreprise ni de `/daily` : il vient **uniquement** du contrat de mission
`mission_module_cryogenique` (exemption des contrats limitée à ce tag, voir `docs/economie.md`). Le poser dans la
soute n'est pas un craft : l'objet reste entier et retombe au sol si la fusée est cassée. Les machines et objets du J5
soumis à recherche (`fusee`, `raffinerie`, `diffuseur_oxygene`, `chaudiere_gaz`, `rechauffeur_nucleaire`,
`propulseur_avance`, `plastron_chauffant`) sont dans `#sovereignfrontier:hors_economie`. Sont autorisés en caisse : `coque_fusee`,
`moteur_fusee`, `bouteille_oxygene`, `carburant_fusee`.

## Validation — `tools/check_space.py`
```bash
python3 tools/check_space.py              # vérification complète (code de sortie 1 en cas d'erreur)
python3 tools/check_space.py --self-test  # tests intégrés sur un dépôt temporaire
```
1. **Planètes** : JSON strict (clé en double refusée), champs autorisés, types et bornes du tableau « Planètes »,
   nom de fichier, objets `sovereignfrontier:` enregistrés dans `registry/ModItems.java` ou `ModBlocks.java`.
2. **Clés de déblocage** (`requires`, `mining.unlock`) présentes dans le catalogue de `docs/recherche.md`.
3. **Tags** : `mining.blocks` est un tag de blocs existant et non vide ; `module` appartient à
   `#sovereignfrontier:restreint/fusee_finale`.
4. **Ensemble** : au plus 8 planètes, une dimension par planète, `minecraft:overworld` interdit.
5. **Traductions** : `sovereignfrontier.planet.<nom>` de chaque planète.
6. **Dimensions** (planètes dont la dimension est dans l'espace de noms du mod) : fichiers `dimension/`,
   `dimension_type/`, `worldgen/noise_settings/` et `worldgen/biome/` présents et décodables comme dans le jeu ;
   valeurs imposées du type (lits, ancres, ciel, plafond), `effects` enregistré côté client, `min_y`/`height`
   identiques entre type et bruit, biome complet (11 étapes, couleurs, créatures) ; pour chaque minerai du tag
   `mining.blocks` : `configured_feature` qui cible la roche par défaut et donne le minerai, `placed_feature` qui la
   référence, biome qui la place à l'étape 6 ; recettes de fusion du titane.

Autres contrôles qui couvrent le J5 :
- `tools/check_research.py` : le catalogue de `docs/recherche.md` porte une colonne « jalon » ; toute clé dont le
  jalon est au plus `WIRED_UP_TO` (`J5`) doit être consommée par un champ `unlock` d'une recette d'ingénierie, un champ
  `requires` ou `mining.unlock` d'une planète, ou un littéral du code Java. Un matériau spatial (titane, soufre,
  cristal) n'apparaît dans le coût d'un palier que si ce palier exige (transitivement) le palier d'extraction
  correspondant.
- `tools/check_economy.py` : tags d'interdiction (dont `fusee_finale`), recettes d'ingénierie, contrats (exemption
  limitée à `#restreint/fusee_finale`), caisse spatiale sans entrée provisoire.
- GameTests (serveur headless) : les dimensions n'y existent pas ; les vols et l'environnement sont testés sur une
  planète de substitution liée à `minecraft:the_end`, et les décisions sont vérifiées par des fonctions pures. La
  recette en jeu (`docs/recette/J5.md`) couvre ce que les GameTests ne peuvent pas voir.

## Ajouter une planète
1. Créer `dimension/<nom>.json`, son `dimension_type`, ses `noise_settings` et son `biome` (voir « Dimensions »), et
   ajouter `<nom>` à `SpaceDimensionEffects.PLANETS` (client).
2. Créer `planets/<nom>.json` (voir « Planètes »). Si un `mining` est déclaré : le tag de blocs, le minerai et ses
   `configured_feature`/`placed_feature`.
3. Ajouter `sovereignfrontier.planet.<nom>` dans `en_us.json` (ordre alphabétique conservé).
4. `python3 tools/check_space.py`, `python3 tools/check_lang.py`, puis **redémarrer le serveur** (les dimensions ne se
   rechargent pas par `/reload`) ; les planètes se rechargent par `/reload`.

## Reporté (hors J5)
- Graphismes des blocs, objets, ciel et HUD des planètes (lot graphique).
- Objets de palier 3 et 4 et armes maximales en récompense de contrat, donjons et boss planétaires, usage du
  cristal cryogénique, `ammo.sulfur` (J6).
- Alliés dans l'équipage, guerre sur les planètes (sabotage de zones), nations PNJ (J7).
- `/home`, `/tpa`, `/back`, `/fly` vers ou sur une planète (J8) : tout transfert passe par `changeDimension`, donc par
  la garde de voyage.
- Vols entre planètes, stations orbitales, plusieurs biomes par planète, zones scellées volumiques, réapparition sur
  une planète, permission « piloter une fusée » (pilote = `construire`).
