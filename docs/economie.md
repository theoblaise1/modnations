# Économie, récompenses et contrats — contrat de données (J4, complété au J5)

Ce document est le **contrat** entre le contenu JSON (`src/main/resources/data/sovereignfrontier/`,
modifiable par un datapack de monde) et le code : où vivent les données, format de chaque fichier,
règles de validation et interdiction absolue de certains objets. Les valeurs d'équilibrage globales
(barème du Terminal, délais, pas de prix…) sont dans la configuration serveur (`[terminal]`,
`[rewards]`, `[market]`, `[contracts]`), pas dans les JSON. La conception complète est dans
[`docs/conception/J4.md`](conception/J4.md) ; ce qui diffère du code livré est signalé ici. Le contenu du J5
(Module cryogénique, caisse spatiale, contrats spatiaux) y est intégré ; les planètes, la fusée et l'environnement
ont leur propre contrat : [`docs/espace.md`](espace.md).

`python3 tools/check_economy.py` vérifie tout ce qui suit (voir « Validation »).

## Règles du moteur
- **Chargement strict.** Chaque dossier est lu par un écouteur de rechargement (`/reload` en jeu). Un
  fichier invalide (champ inconnu, borne dépassée, objet inconnu…) est **ignoré** avec une ligne
  ERREUR en français dans le journal du serveur (« Caisse sovereignfrontier:militaire ignorée : … ») ;
  les autres fichiers restent chargés. Le contenu rejeté devient **indisponible** (il ne retombe pas sur
  l'ancienne version).
- **Publication atomique.** Les écouteurs ne construisent qu'un candidat. Les validations qui demandent
  les tags (objets interdits, cibles `MONSTER`, tags de biome non vides, écart global des pools) sont
  faites au `TagsUpdatedEvent` (`SERVER_DATA_LOAD`), puis le registre publié est remplacé d'un coup.
- **Constantes de code.** Les bornes de validation (tableaux ci-dessous) sont des constantes Java,
  **jamais** lues dans `SFConfig` : la configuration serveur n'est chargée qu'après le premier
  chargement des datapacks.
- **Instantanés.** Un contrat accepté, l'étal tiré d'un avant-poste et les compteurs de pitié ne
  dépendent pas du registre vivant : un `/reload` est sans danger à tout moment.
- **Temps.** Délais de `/daily`, contrats, fenêtre du Terminal : timestamps epoch persistés (ils
  avancent serveur éteint). Rotation des marchands et cycle d'entreprise : compteurs de ticks dans un
  SavedData.

## Où vivent les données

| Contenu | Fichier (`data/sovereignfrontier/…`) | Identifiant | Classe | Ligne du journal au démarrage |
|---|---|---|---|---|
| Caisses | `crates/<type>.json` | `commune`, `militaire`, `industrielle`, `spatiale` (= `reward/CrateKind`) | `CrateRegistry` | « N table(s) de caisse publiée(s) » |
| `/daily` | `daily/streak.json` | `sovereignfrontier:streak` seul | `DailyTable` | « table /daily publiée (N jour(s)) » |
| Marchands | `merchants/<pool>.json` | identifiant du pool (`sovereignfrontier:general`) | `MerchantRegistry` | « N pool(s) marchand(s) publié(s) […] » |
| Contrats | `contracts/<chemin>.json` | identifiant du modèle | `ContractRegistry` | « N modèle(s) de contrat publié(s) {…} » |
| Entreprises | `enterprises/<secteur>.json` | `agricole`, `minier`, `logistique`, `industriel` (= `enterprise/Sector`) | `SectorRegistry` | « N secteur(s) d'entreprise publié(s) sur 4 » |
| Ingénierie (J3, J5) | `engineering/<id>.json` | identifiant de la recette | `EngineeringRegistry` | « N recette(s) d'ingénierie chargée(s) » |
| Planètes (J5) | `planets/<nom>.json` | nom du fichier (`lune`, `mars`, `planete_glacee`) | `PlanetRegistry` | « N planète(s) publiée(s) : … » (voir `docs/espace.md`) |
| Objets interdits | `tags/item/restreint*.json`, `hors_economie.json`, `interdit_caisses.json` | tags `#sovereignfrontier:…` | `RestrictedItems` | « [Économie] Audit des recettes : … » |
| Cibles de chasse | `tags/entity_type/contrat/<nom>.json` | `#sovereignfrontier:contrat/<nom>` | `ContractRegistry` | — |
| Avant-postes | `worldgen/structure/`, `worldgen/structure_set/`, `worldgen/template_pool/avant_poste/`, `tags/worldgen/biome/has_structure/`, `tags/worldgen/structure/avant_postes.json` | `avant_poste_<variante>` (`plaines`, `desert`, `taiga`) | datapack vanilla | — |
| Butin des tonneaux | `loot_table/chests/avant_poste_<variante>.json` | table de butin vanilla | — | — |
| Gabarits | `structure/avant_poste/<variante>.nbt` | généré par `tools/gen_structures.py` | — | — |

Le nom d'un fichier de caisse ou de secteur **est** son identifiant : un autre nom est ignoré
(avertissement au journal). Les traductions des modèles de contrat et des textes de pools sont dans
`assets/sovereignfrontier/lang/en_us.json` (rédigé en français), `check_lang.py` et `check_economy.py`
vérifient leur présence.

## Objets interdits (paliers 3 et 4, fusée finale, arme maximale)

| Tag (`#sovereignfrontier:…`) | Contenu |
|---|---|
| `restreint` | regroupe `restreint/palier_3`, `restreint/palier_4`, `restreint/fusee_finale`, `restreint/arme_max`. `restreint/fusee_finale` contient `module_cryogenique` depuis le J5 (seul composant final de fusée) ; les trois autres sont vides jusqu'au J6 |
| `hors_economie` | objets vanilla de fin de jeu (netherite complète, gabarit d'amélioration, élytres, étoile du Nether, balise, pomme dorée enchantée, œuf de dragon, masse, noyau lourd), objets de Créatif (blocs de commande, barrière, blocs de structure et puzzle, bâton de débogage, lumière, cage d'apparition, bedrock), les machines J3 soumises à recherche (`generateur_thermique`, `panneau_solaire`, `cellule_energie`, `foreuse_gaz`, `controleur_reacteur`, `caisson_reacteur`, `cellule_combustible`, `dissipateur_thermique`) et les objets J5 soumis à recherche (`fusee`, `raffinerie`, `diffuseur_oxygene`, `chaudiere_gaz`, `rechauffeur_nucleaire`, `propulseur_avance`, `plastron_chauffant`) : aucune source automatique ne les donne |
| `interdit_caisses` | `#restreint` + `#hors_economie` |

**Règle absolue.** Un objet de palier 3 ou 4, un composant final de fusée ou une arme militaire
maximale ne se trouve **jamais** dans :

| Source | Tag appliqué | CI (`check_economy.py`) | Au chargement | À l'exécution |
|---|---|---|---|---|
| Craft vanilla (`recipe/`) et recette d'ingénierie (`engineering/`) | `#restreint` | erreur | audit ERREUR au démarrage du serveur et après chaque `/reload` (aucune suppression) | — |
| Caisse | `#interdit_caisses` | erreur | table rejetée **en entier** | objet non remis, ERREUR au journal |
| `/daily` (clés) | clés absentes de `#interdit_caisses` | erreur | table rejetée | clé non remise |
| Marchand (vente **et** rachat) | `#interdit_caisses` | erreur | pool rejeté en entier | achat refusé |
| Produit d'entreprise | `#interdit_caisses` + clés de caisse | erreur | secteur rejeté | objet non déposé |
| Récompense de contrat (`reward.items`) | `#interdit_caisses` **sauf `#restreint/fusee_finale`** | erreur | modèle rejeté | objet non remis, ERREUR au journal |
| Butin des tonneaux d'avant-poste | `#interdit_caisses` | erreur | — (table de butin vanilla) | — |

- Les références `#minecraft:…` sont **interdites** dans ces tags (y compris indirectement) : les
  objets vanilla y sont listés un par un, pour que le contenu ne change pas avec Minecraft.
- **Exemption des contrats (J5).** `docs/conception/J4.md` §1.4 et §9 prévoient que `reward.items` des
  contrats puisse contenir des objets `#restreint` (source légitime des objets de palier 3 et 4). Le J4
  appliquait `#interdit_caisses` aussi aux contrats ; le J5 y ajoute **une seule exemption explicite** :
  `#sovereignfrontier:restreint/fusee_finale`. La garde des contrats est donc « `#interdit_caisses` sauf
  `#restreint/fusee_finale` » (`RestrictedItems.isBannedFromContracts` au chargement, `allowInContractReward` à la
  remise). `restreint/palier_3`, `restreint/palier_4` et `restreint/arme_max` restent **interdits dans les
  contrats** jusqu'à ce que le J6 tranche ; il suffira alors d'étendre l'exemption. C'est ainsi que le Module
  cryogénique (seul membre de `restreint/fusee_finale`) est obtenu : uniquement par le contrat de mission
  `mission_module_cryogenique`, jamais par un craft, une caisse, un marchand, une entreprise ni `/daily`.
- **Procédure.** Tout nouvel objet de palier 3 ou 4, composant final de fusée ou arme militaire
  maximale doit être ajouté **dans le même commit** au sous-tag correspondant
  (`restreint/palier_3`, `restreint/palier_4`, `restreint/fusee_finale`, `restreint/arme_max`).
  Un objet de fin de jeu hors de ces catégories va dans `hors_economie`. Les clés de caisse ne se
  fabriquent jamais (aucune recette ; `check_economy.py` échoue sinon).

## Caisses — `crates/<type>.json`
```json
{
  "rolls": 3,
  "entries": [
    {"item": "minecraft:bread", "count": [6, 12], "weight": 20},
    {"money": [50, 150], "weight": 15},
    {"item": "minecraft:iron_ingot", "count": [3, 8], "weight": 10, "rarity": "peu_commun"},
    {"id": "diamants", "item": "minecraft:diamond", "count": [1, 2], "weight": 2, "rarity": "rare"},
    {"item": "minecraft:potion", "count": [1, 2], "weight": 12,
     "components": {"minecraft:potion_contents": {"potion": "minecraft:healing"}}},
    {"placeholder": {"milestone": "J6", "label": "munitions"}, "weight": 10}
  ]
}
```
- **Une entrée** contient exactement un champ parmi `item` (avec `count` et `components` facultatifs),
  `money` et `placeholder`. `count` et `money` valent `n` ou `[min, max]` (bornes incluses).
- `weight` entier de 1 à 1 000 000 (défaut 1). `rarity` : `commun` (défaut), `peu_commun`, `rare`.
- Une entrée **rare** exige un `id` (`[a-z0-9_]`, 32 caractères au plus) : c'est la clé de ses compteurs
  de pitié, et il ne doit plus changer (un identifiant inconnu est purgé à l'ouverture).
- Une entrée **provisoire** (`placeholder`) n'est jamais tirée ; l'aperçu l'affiche grisée
  « À venir (J6) : … » (`reward.placeholder.<label>` dans `en_us.json`).
- **Bornes de code** (`CrateTable`) : `rolls` de 1 à 6 ; 32 entrées au plus ; 1 à 3 entrées rares ; au
  moins une entrée ni rare ni provisoire ; `rolls` ≤ nombre d'entrées tirables ; `count` ≤ 256 ;
  `money` ≤ 1 000 000 ; aucun `minecraft:air`.
- **Tirage.** `rolls` entrées pondérées **sans remise** parmi les entrées tirables. **Pitié** par
  entrée rare, par joueur et par type : une rare manquée `cratePityThreshold` fois (20) est garantie à
  l'ouverture suivante (la 21ᵉ) ; si deux rares atteignent le seuil ensemble, la plus manquée tombe
  d'abord (égalité : ordre du JSON), l'autre à l'ouverture d'après.
- Le contenu livré est dans `crates/*.json` ; seule la caisse militaire porte encore des entrées
  provisoires (J6 : munitions, trousse de soins, gilet balistique léger).
- **Caisse spatiale (J5).** Ses trois entrées provisoires (pièces de fusée, oxygène, carburant de fusée) sont
  remplacées par du contenu réel : `coque_fusee` × 1 (poids 4), `moteur_fusee` × 1 (poids 2), `bouteille_oxygene`
  2 à 6 (poids 8) et `carburant_fusee` 1 à 2 (poids 8), en plus des bouteilles de gaz, du verre, des blocs de
  fer, des circuits, des fusées d'artifice, des membranes de phantom et de l'argent. Entrées rares inchangées
  (`combustible`, `gaz_massif`, `pactole`). Ces quatre objets ne figurent pas dans `#interdit_caisses` (pièces non
  finales et consommables) ; le module cryogénique, lui, ne peut pas en sortir (`check_economy.py` en CI,
  `CrateRegistry` au chargement : table rejetée en entier).

## `/daily` — `daily/streak.json`
```json
{"days": [
  {"money": 100, "keys": [{"one_of": ["commune"]}]},
  {"money": 250, "keys": [{"one_of": ["commune"]}, {"one_of": ["industrielle", "militaire"]}]}
]}
```
- `days` : 1 à 31 jours ; le jour de récompense vaut `min(série, nombre de jours)` : **le dernier jour
  se répète** (plateau). Le fichier livré en compte 7.
- Chaque jour : `money` entier de 0 à 1 000 000 et `keys` de 1 à 2 choix `{"one_of": [types]}`
  (liste non vide, sans doublon, types de `CrateKind`). Une clé est tirée uniformément dans son
  `one_of`. Aucun autre champ.
- Série : `série = (dernier /daily ≤ dailyStreakResetHours) ? série + 1 : 1` ; délai entre deux
  `/daily` : `dailyCooldownHours` (24 h). Seul l'identifiant `sovereignfrontier:streak` est lu.

## Marchands — `merchants/<pool>.json`
```json
{
  "variant": "general",
  "rolls": 6,
  "offers": [
    {"item": "minecraft:bread", "count": 6, "price": 18, "stock": 16, "weight": 10, "guaranteed": true},
    {"item": "minecraft:wheat", "count": 16, "payout": 12, "demand": 32, "weight": 10, "guaranteed": true},
    {"item": "minecraft:oak_log", "count": 16, "price": 40, "payout": 16, "stock": 8, "demand": 16, "weight": 6}
  ]
}
```
- `variant` (`[a-z0-9_]`, 32 caractères au plus) nomme le marchand (`outpost.merchant.<variant>` dans
  `en_us.json`, repli sur la variante). `rolls` : offres **non garanties** tirées à chaque rotation.
- Une offre est un **lot** de `count` objets (1 à 99, au plus une pile) : `price` (le marchand
  **vend** le lot), `payout` (il le **rachète**) — au moins l'un des deux, de 1 à 1 000 000 ;
  `stock` (lots à vendre par rotation, défaut 16) et `demand` (lots rachetés, défaut 32) de 1 à
  10 000 ; `weight` de 1 à 10 000 (défaut 1) ; `guaranteed` (défaut `false`).
- **Bornes de code** (`MerchantPool`, `MarketRules`) : 1 à 24 offres ; objets uniques dans un pool ;
  `rolls` ≤ nombre d'offres non garanties ; l'étal ne peut pas être toujours vide ; aucun objet de
  `#interdit_caisses` (vente ou rachat) ; champs inconnus refusés par la CI.
- **Tarification** (`outpost/PriceMath`, par avant-poste et par objet ; pas = `merchantPressureStep`,
  20 lots) :
  - le joueur **achète** un lot : `ceil(price × min(2,0 ; 1,3^(B/20)))`, puis `B += 1` ;
  - le joueur **vend** un lot : `floor(payout × max(0,25 ; 0,7^(S/20)))`, plafonné à
    `floor(0,8 × prix courant)` si le pool vend aussi l'objet, puis `S += 1` ;
  - 20 lots vendus donnent donc ×0,70, 40 lots ×0,49 (plancher ×0,25 vers 78 lots) ; à chaque
    rotation `B` et `S` sont multipliés par `merchantPressureDecay` (0,5, au plus 16 fois).
- **Règle statique d'écart** (`MarketRules.MAX_PAYOUT_RATIO = 0,8`, vérifiée au chargement et en CI) :
  pour chaque objet, sur **tous** les pools, `rachat/unité ≤ 0,8 × prix minimal/unité`. Un pool qui la
  viole est rejeté en entier. Comme `B` ne fait que monter les prix et `S` que baisser les rachats,
  aucun aller-retour n'est jamais gagnant, dans un avant-poste comme entre avant-postes.
- **Arbitrage par l'artisanat** (CI seulement) : pour les paires connues lingot ↔ bloc, pépite ↔ lingot
  et rondin → planches, un dérivé racheté ≤ 0,8 × le coût de ses ingrédients vendus.
- **Rotation.** `R = ticks / merchantRotationTicks` (24 000). À chaque accès, si la rotation d'un
  avant-poste a changé : pression amortie, offres garanties + `rolls` offres pondérées sans remise
  (graine déterministe), stock et demande pleins. Un `/reload` ne modifie pas l'étal en cours.
- Pools livrés : `general`, `prospecteur` et `trappeur` (6 tirages chacun). Le pool d'un avant-poste est le champ
  `pool` du NBT de son comptoir (écrit par le gabarit ou par `/sfadmin outpost create <pool>`).

### Avant-postes (génération du monde)
Trois variantes (`plaines`, `desert`, `taiga`), choisies par biome (`tags/worldgen/biome/has_structure/`),
placées par `worldgen/structure_set/avant_postes.json` (espacement 28 chunks, séparation 10, à au moins
4 chunks d'un village). Un gabarit de 11 × 7 × 11 contient un comptoir (ancre incassable), une caisse
commune et un tonneau à butin ; le marchand apparaît au premier tick du comptoir. Les gabarits `.nbt`
sont produits par `tools/gen_structures.py` (jamais à la main). Le tag
`#sovereignfrontier:avant_postes` sert à `/locate structure` et à la garde de revendication.

Protections (code, désactivables par `protectOutposts` pour la boîte seulement) : les 3 × 3 chunks autour
du comptoir ne sont pas revendicables ; dans la boîte (comptoir ±7 en X/Z, −2 à +7 en Y), les joueurs en
survie sans `/sfadmin bypass` ne cassent ni ne posent, et les explosions ne détruisent rien.

## Contrats — `contracts/<chemin>.json`
```json
{"kind": "deliver", "weight": 10, "requires": ["sovereignfrontier:ressources_3"],
 "min_population": 0, "duration_hours": 48,
 "objective": {"item": "minecraft:iron_ingot", "amount": {"min": 16, "max": 48, "step": 8}},
 "reward": {"money": 150, "money_per_unit": 5, "points": 4, "points_per_unit": 0.15,
            "keys": [{"crate": "industrielle", "count": 1, "chance": 0.5}],
            "items": [{"item": "minecraft:experience_bottle", "count": 8}]}}
```
- `kind` : `deliver`, `kill` ou `explore`. `weight` de 1 à 10 000 (défaut 1) ; `requires` : paliers de
  recherche à terminer ; `min_population` de 0 à 10 000 ; `duration_hours` de 1 à 720 (obligatoire).
- **Objectif** (champs inconnus refusés) :

| `kind` | `objective` |
|---|---|
| `deliver` | `item`, `amount {min, max, step}` (1 à 10 000, `step` défaut 1) |
| `kill` | `entity` (type ou `#tag`), `amount {min, max, step}` ; chaque cible doit être de catégorie `MONSTER` (vérifié au chargement) |
| `explore` | **exactement une** cible : `biome` (identifiant ou `#tag`, avec `min_distance` de 0 à 1 000 000), `waypoint {min, max, radius}` (point tiré à l'acceptation ; rayon 1 à 1 024), `outposts {min, max}` (1 à 8 avant-postes distincts à 16 blocs au plus d'une ancre) ou `dimension` (présente dans le registre `LEVEL_STEM` du serveur **ou** déclarée par une planète publiée : les modèles spatiaux restent chargés sur le serveur de GameTests, qui n'a pas les planètes) |

- **Récompense** (au moins un élément non vide) : `money` (0 à 1 000 000), `money_per_unit`,
  `points` (0 à 1 000), `points_per_unit`, `keys` (au plus 4 : `crate`, `count` de 1 à 64, `chance` dans
  ]0 ; 1]), `items` (au plus 8 : `item`, `count` de 1 à 64 ; ni doublon, ni clé de caisse, ni objet de
  `#interdit_caisses` **hors `#restreint/fusee_finale`** : un modèle qui en contient un autre est rejeté au
  chargement avec « récompense interdite … seul #sovereignfrontier:restreint/fusee_finale est permis dans un
  contrat »).
- **Plafonds** : `money_per_unit × max × 2,5 ≤ 100 000` et `points + points_per_unit × max × 2,5 ≤ 1 000`
  (exploration : sans facteur de taille). **Règle CI seulement** : pour un contrat de livraison,
  `money_per_unit ≤ 0,5 ×` le prix unitaire marchand minimal du même objet (pas d'arbitrage
  marchand → contrat).
- **Instanciation** à la génération du tableau :
  - `quantité = arrondiAuPas(U(min..max, pas) × min(contractSizeCap ; 1 + contractSizePerExtraMember × (membres − 1)))`
    (exploration : non mise à l'échelle) ;
  - `argent = round(money × (1 + contractMoneyPerTier × paliers terminés)) + round(money_per_unit × quantité)`
    (la part par unité n'est jamais mise à l'échelle) ;
  - `points = points + floor(points_per_unit × quantité)` ;
  - chaque clé est **tirée à la génération** selon sa `chance` et affichée telle quelle.
- **Réussite** : `floor(argent × contractContributorShare)` est réparti au prorata des contributions
  entre les contributeurs encore membres, le reste va à la trésorerie ; les points vont aux points de
  recherche de la nation ; clés et objets vont au plus gros contributeur en ligne, sinon au coffre
  national (s'il est plein : au joueur qui termine).
- **Nom affiché** : `sovereignfrontier.contract.template.<chemin>` dans `en_us.json` (`/` devient `.`) ;
  obligatoire (`check_economy.py`). Les tags d'entités utilisés par un modèle ont une traduction
  `sovereignfrontier.contract.tag.<ns>.<chemin>`.
- **Chasse** : seuls les monstres apparus naturellement comptent (`getSpawnType()` parmi `NATURAL`,
  `CHUNK_GENERATION`, `STRUCTURE`, `PATROL`, `EVENT`, `JOCKEY`), tués directement par un vrai joueur
  membre d'une nation. Cages, œufs, commandes, reproduction et faux joueurs sont ignorés.

Modèles livrés (27) :

| Type | Modèles |
|---|---|
| Livraison (13) | `livraison_ble`, `livraison_pain`, `livraison_bois`, `livraison_charbon`, `livraison_fer` (`ressources_3`), `livraison_cuivre` (pop. 2), `livraison_redstone` (`industrie_2`), `livraison_circuits` (`technologie_1`), `livraison_gaz` (`ressources_8`) ; **J5** : `livraison_titane` (`ressources_13`), `livraison_soufre` (`ressources_14`), `livraison_cristaux` (`ressources_15` et `technologie_7`), `mission_module_cryogenique` |
| Chasse (6) | `chasse_zombies`, `chasse_squelettes` (`#sovereignfrontier:contrat/squelettes`), `chasse_araignees` (`#sovereignfrontier:contrat/araignees`), `chasse_creepers` (pop. 2), `chasse_endermen` (`militaire_3`), `chasse_blazes` (`militaire_5`, 8 fioles d'expérience en plus) |
| Exploration (8) | `exploration_jungle`, `exploration_badlands` (pop. 2), `exploration_lointaine` (point à 1 000–2 000 blocs), `exploration_avant_postes` (2 à 3 avant-postes), `exploration_nether` (pop. 3) ; **J5** : `exploration_lune` (`technologie_2`), `exploration_mars` (`technologie_4`), `exploration_planete_glacee` (`technologie_7`) |

Les points de recherche gagnés ici paient les paliers 6 et suivants (voir `docs/recherche.md`).

### Contrats spatiaux (J5)
- **Expéditions** (`exploration_lune`, `exploration_mars`, `exploration_planete_glacee`) : cible
  `dimension` ; récompense 600, 900 et 1 500 cr, 15, 25 et 40 points, une clé spatiale à coup sûr (et une
  clé industrielle à 50 % pour la planète glacée) ; prérequis `technologie_2`, `technologie_4`, `technologie_7`.
  Elles se valident en arrivant dans la dimension, donc en fusée.
- **Livraisons de matériaux** : lingot de titane (16 à 48, pas 8), soufre (32 à 96, pas 16), cristal cryogénique
  (8 à 24, pas 4) ; argent et points au prorata de la quantité ; plafonds du J4 respectés.
- **Mission du module** `mission_module_cryogenique` : livrer 64 à 128 soufres (pas de 16, mis à l'échelle par le nombre
  de membres) ; récompense 2 000 cr, 30 points et **1 `module_cryogenique`** ; exige `industrie_11`, `ressources_14`
  et 12 habitants effectifs ; durée 168 h, poids 50. Le module va au plus gros contributeur en ligne, sinon au
  coffre national (chemin des objets de contrat du J4). C'est la seule source du composant final : il n'existe aucune
  recette, entrée de caisse, offre de marchand, produit d'entreprise ni clé `/daily` qui le donne.
- Aucun marchand n'achète ni ne vend titane, soufre ou cristal (pas de conversion illimitée en argent).

## Entreprises — `enterprises/<secteur>.json`
```json
{
  "requires": [],
  "domain": "ressources",
  "bonus_per_tier": 0.04,
  "money_per_worker": 1,
  "products": [
    {"item": "minecraft:cobblestone", "per_worker": 2.0},
    {"item": "minecraft:diamond", "per_worker": 0.005, "requires": ["sovereignfrontier:ressources_11"]}
  ]
}
```
- Le nom du fichier est un secteur de `enterprise/Sector` ; les quatre sont attendus (un secteur absent
  ou rejeté est « indisponible »). Champs inconnus refusés (au niveau du fichier et des produits).
- `requires` : paliers qui débloquent le secteur ; `domain` : domaine de recherche (`civil`, `militaire`,
  `ressources`, `industrie`, `technologie`) dont chaque palier terminé ajoute `bonus_per_tier`
  (de 0 à 0,2) au multiplicateur `mult = 1 + bonus_per_tier × paliers terminés` ;
  `money_per_worker` : entier de 0 à 1 000 000.
- `products` : 8 au plus, objets uniques ; `per_worker` dans ]0 ; 64] (unités par habitant et par cycle,
  fractions conservées d'un cycle à l'autre) ; `requires` facultatif par produit. Un secteur sans argent
  ni produit est refusé. Aucun produit de `#interdit_caisses`, aucune clé de caisse.
- **Cycle** (`incomeIntervalTicks`, avec le revenu passif, dans le même `tickIncome`) :
  - argent : `round(habitants × money_per_worker × mult × (1 + income_multiplier))`, versé à la trésorerie ;
  - objets : `floor(habitants × per_worker × mult + fraction)` au coffre national, le reste de la
    fraction attend le cycle suivant ; ce qui ne rentre pas est perdu et annoncé ;
  - les habitants affectés **quittent** le revenu passif (`libres × incomePerInhabitant`).

| Secteur | Déblocage | Domaine (bonus par palier) | Argent/hab./cycle | Produits par habitant et par cycle |
|---|---|---|---|---|
| `agricole` | — | ressources (0,04) | 1 | blé 1,0 · carotte 0,4 · pomme de terre 0,4 · betterave 0,2 · poudre d'os 0,3 · cuir 0,1 (`ressources_2`) |
| `minier` | — | ressources (0,04) | 1 | pierre taillée 2,0 · charbon 0,4 · cuivre brut 0,3 · fer brut 0,2 · or brut 0,05 (`ressources_3`) · redstone 0,15 et lapis 0,1 (`ressources_5`) · diamant 0,005 (`ressources_11`) |
| `logistique` | `civil_3` | civil (0,03) | 8 | — |
| `industriel` | `industrie_1` | industrie (0,05) | 2 | verre 0,5 · lingot de cuivre 0,25 · lingot de fer 0,2 · redstone 0,1 · lingot d'or 0,03 (`industrie_3`) · circuit 0,04 (`technologie_1`) |

## Recettes d'ingénierie — `engineering/<id>.json` (J3, J5)
`{"tab": "machines", "unlock": "energy.cables", "ingredients": [{"item": …, "count": …}], "result": {"item": …, "count": …}}`.
`tab` : `machines`, `armement` ou `aerospatial` ; `unlock` : clé de déblocage de recherche
(facultative) ; ingrédients sans doublon ; résultat au plus une pile. Au J4, `check_economy.py` y
refuse tout résultat de `#restreint` et toute clé de caisse. Une recette n'est utilisable que si la nation a la clé de
l'onglet (`station.machines`, `station.armament`, `station.aerospace`) **et** celle de la recette. Le J5 y ajoute les
recettes de l'onglet Aérospatial et celle de la Raffinerie (liste dans `docs/espace.md` et `docs/recherche.md`) ;
aucune ne produit `module_cryogenique` ni `carburant_fusee`.

**Écart avec les autres dossiers** : contrairement aux caisses, contrats, marchands et entreprises, `engineering/`
n'est pas publié au `TagsUpdatedEvent` : `EngineeringRegistry` remplace son registre dès que l'écouteur a lu les
fichiers, et son codec **ignore les champs inconnus** (seul `check_economy.py` les refuse). La garde `#restreint`
s'applique donc par l'audit des recettes au démarrage et après chaque `/reload`, pas par un rejet au chargement.

## Barèmes de référence (configuration par défaut)
- **Terminal bancaire** : `terminalHourlyRates` = 240, 180, 135, 100, 75, 55, 40, 30, 20 cr par heure
  **active** de la fenêtre de 24 h (855 cr en 8 h, puis 20 cr/h) ; épargne plafonnée à
  `terminalMaxPending` (3 000 cr). Actif = un signe d'activité (regard ≥ 10°, marche au sol ≥ 1 bloc,
  casse ou pose à une position nouvelle, chat, commande, ouverture de conteneur, artisanat) depuis
  moins de `terminalAfkSeconds` (180 s). Attaquer ou utiliser un objet ne compte jamais.
- **`/daily`** : 100, 150, 200, 250, 300, 400, 500 cr du jour 1 au jour 7 (et plus), avec une clé
  commune chaque jour, une deuxième clé du jour 3 au jour 7.
- **Tableau de contrats** : offres `min(contractMaxOffers ; contractBaseOffers + ceil(membres / 2))`,
  contrats en cours `min(contractMaxActive ; contractBaseActive + ceil(membres / 3))` ; rafraîchi toutes
  les `contractRefreshHours` (24 h) ; généré seulement pour une nation d'au moins
  `contractMinNationAgeHours` (12 h) ; au plus 2 offres du même type ; 10 contrats terminés gardés.
- **Permission** : `economie` (« Gérer l'économie ») pour accepter, abandonner un contrat et affecter des
  habitants ; livrer et consulter sont ouverts à tout membre. Par défaut : Ministre et Officier ; les
  nations créées avant le J4 l'obtiennent par migration (`permVersion`).

## Validation — `tools/check_economy.py`
```bash
python3 tools/check_economy.py              # vérification complète (code de sortie 1 en cas d'erreur)
python3 tools/check_economy.py --self-test  # tests intégrés sur un dépôt temporaire
```
1. **Tags d'objets** : JSON strict (clés en double refusées), format `{"values": [...]}`, résolution
   récursive sans cycle, tags `#sovereignfrontier:` existants, objets `sovereignfrontier:` enregistrés
   dans `registry/ModItems.java` ou `ModBlocks.java`.
2. **Tags d'interdiction** : les sept tags existent, `restreint` inclut ses quatre sous-tags,
   `interdit_caisses` inclut `#restreint` et `#hors_economie`, aucune référence à un tag d'un autre
   espace de noms.
3. **Recettes** (`recipe/` de tous les espaces de noms et `engineering/`) : objets connus, aucun résultat
   de `#restreint`, aucune recette de clé de caisse.
4. **Modules de contenu** (`tools/economy_checks/`) : `merchants` (pools, écart global, artisanat, butin
   des tonneaux), `crates`, `daily`, `contracts` (dont nom affiché, plafonds, récompense hors `#interdit_caisses`
   sauf `#restreint/fusee_finale`), `enterprises`.

Les avertissements (par exemple un tag de chasse vanille non vérifiable) ne font pas échouer. La CI
(`.github/workflows/build.yml`) exécute l'auto-test puis la vérification. Ces contrôles sont **plus
stricts** que le chargement en jeu sur un point : ils refusent aussi les champs inconnus des entrées de
caisse.

## Ajouter du contenu
1. **Offre de marchand** : ajouter l'objet au pool voulu, en respectant `rachat/u ≤ 0,8 × prix minimal/u`
   de tous les pools ; `python3 tools/check_economy.py`.
2. **Modèle de contrat** : créer `contracts/<chemin>.json`, ajouter `sovereignfrontier.contract.template.<chemin>`
   dans `en_us.json` (ordre alphabétique conservé), puis `check_economy.py` et `check_lang.py`. Pour une
   chasse à plusieurs espèces, créer un tag `tags/entity_type/contrat/<nom>.json` (monstres seulement) et
   sa traduction `sovereignfrontier.contract.tag.sovereignfrontier.contrat.<nom>`.
3. **Entrée de caisse** : respecter les bornes ; une nouvelle rare reçoit un `id` stable.
4. **Nouvel objet restreint** : l'ajouter au sous-tag de `restreint/` (voir « Objets interdits »).
5. `/reload` en jeu : les fichiers invalides sont ignorés avec un message explicite dans le journal.

## Reporté
- Objets de palier 3 et 4 et armes maximales en récompense de contrat (J6 : il suffira d'étendre l'exemption
  de `RestrictedItems.isBannedFromContracts`), clés lâchées par les boss et les victoires de guerre (J6, J7) : les
  points d'entrée `CrateKeys.give` et `CrateKeys.giveToNation` existent.
- Grade Souverain : `RankHooks.terminalMultiplier` renvoie 1,0 jusqu'au J8 (`terminalSouverainMultiplier`
  n'est pas encore lue).
- Griefs des avant-postes (pillage d'un conteneur à butin non encore ouvert dans la boîte) : enregistrés
  par nation, sans effet de jeu avant le J7 (`/sfadmin outpost info`).
- `storage.expansion_1` et `storage.expansion_2` (voir `docs/recherche.md`).
