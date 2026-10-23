# Arbre de recherche — plan de référence (J2)

Ce document est le **contrat** entre le contenu (`data/sovereignfrontier/research/*.json`) et le
code : noms, récompenses, prérequis croisés et population minimale de chacun des 65 paliers.
Les coûts (objets, trésorerie, points de recherche) sont fixés dans les JSON et vérifiés par
`tools/check_research.py`.

## Règles du moteur
- 5 domaines ; dans chaque domaine le palier `n` exige implicitement le palier `n-1`.
- Une seule recherche active par nation, tous domaines confondus (permission `rechercher`).
- Lancement : objets **consommés dans le coffre national**, coût débité de la trésorerie
  et des points de recherche (gagnés par les contrats nationaux, voir « Points de recherche »).
- Durée : `120 s × palier^1,35`, divisée par `1 + bonus research_speed`. Fin = horodatage epoch :
  la recherche avance serveur éteint et se termine au premier contrôle (toutes les 20 ticks).
- Annulation : objets perdus, `researchCancelRefund` (50 % par défaut) du coût en trésorerie rendu ;
  les points de recherche ne sont **jamais** rendus.

## Points de recherche (J4)
- Règle imposée par `tools/check_research.py` : `cost.points = 5 × (palier − 5)` à partir du
  palier 6, `0` (champ absent) pour les paliers 1 à 5. Soit 5, 10, 15… points : 40 paliers payants,
  **1 035 points** pour l'arbre complet (civil 275, militaire 330, ressources 275, industrie 140,
  technologie 15).
- Seule source : les contrats nationaux (`data/sovereignfrontier/contracts/`, environ 3 à 20 points
  par contrat réussi), versés à la nation. L'arbre complet demande 3 à 5 semaines à une nation active.
- Les paliers déjà terminés avant le J4 ne sont pas facturés rétroactivement.

## Bonus passifs (`"type": "bonus"`) — effet immédiat
| stat | effet |
|---|---|
| `population_multiplier` | habitants effectifs = bâtiments × (1 + somme) |
| `income_multiplier` | revenu de population × (1 + somme) |
| `crop_yield` | s'ajoute au bonus de rendement des cultures du territoire |
| `claim_cost_multiplier` | coût de claim × (1 + somme), somme bornée à −0,5 |
| `max_members` | membres supplémentaires |
| `research_speed` | durée des recherches ÷ (1 + somme) |

## Déblocages (`"type": "unlock"`) — consommés par les jalons
La clé est un identifiant pointé ; son nom affiché est `sovereignfrontier.unlock.<clé>`. La colonne « jalon » du
catalogue est lue par `tools/check_research.py` : toute clé dont le jalon est au plus `WIRED_UP_TO` (actuellement
`J7`) doit être consommée par le code ou les données (voir « Validation » ci-dessous).

**Implémentées au J3** (recettes `data/sovereignfrontier/engineering/`) : `station.machines`,
`energy.cables`, `generator.thermal`, `generator.solar`, `machines.automation`, `drill.gas`,
`reactor.nuclear`, `reactor.enriched_fuel`, `circuits`.

**Implémentées au J5** (les 15 déblocages du jalon plus `refinery`, longtemps marquée J3 sans jamais avoir été
câblée) :
- onglet et machines : `station.aerospace` (onglet Aérospatial de la Station d'ingénierie), `rocket` (coque, moteur,
  fusée ; tout vol), `refinery` (recette de la Raffinerie, onglet Machines), `rocket.fuel` (la Raffinerie produit du
  carburant de fusée), `oxygen.systems` (Diffuseur d'oxygène), `boiler.gas` (Chaudière à gaz), `armor.gas_heated`
  (Plastron chauffant), `heater.nuclear` (Réchauffeur nucléaire), `propulsion.advanced` (Propulseur avancé :
  carburant × 0,75) ;
- destinations : `dimension.lune`, `dimension.mars`, `dimension.planete_glacee` (vol, revendication de territoire sur
  la planète ; la Lune ouvre aussi la recette de la bouteille d'oxygène), `rocket.upgrade_module` (condition de la
  3ᵉ destination : le Module cryogénique en soute ; ouvre le contrat de mission qui le donne) ;
- extraction : `mining.lunar`, `mining.martian`, `mining.cryogenic` (casser le minerai de titane, de soufre, cryogénique
  — verrou valable dans toute dimension).

Ces clés **verrouillent l'effet**, pas seulement la recette : une machine J5 ne fonctionne que sur le territoire d'une
nation qui possède sa clé, un propulseur ou un plastron n'agit que pour une nation qui possède la sienne. Le détail
(planètes, fusée, environnement) est dans [`docs/espace.md`](espace.md).

**Restent à venir** (sans effet pour l'instant) : `tools.*`, `drill.survey`, `drill.mechanical`, `drill.deep`,
`machines.alloy`, `storage.expansion_*` (colonne « reporté »), `defense.*`
(reporté : défenses anti-PNJ, hors J7). Les déblocages J6 (`station.armament`, `ammo.*`, `weapon.*`,
`armor.ballistic_*`) sont livrés. Les déblocages J7 sont câblés : `diplomacy.embassy` (requis pour proposer un traité) et
`missile.silo`, `missile.guided`, `missile.heavy_warhead` (silo, missile guidé et ogive lourde).

| clé | effet | jalon |
|---|---|---|
| `storage.expansion_1`, `storage.expansion_2` | agrandissement du stockage national (reporté : le déblocage reste sans effet) | reporté |
| `diplomacy.embassy` | ambassades et traités | J7 |
| `tools.reinforced`, `tools.precision` | outils de minage avancés (Station d'ingénierie) | reporté |
| `drill.gas` | foreuse à gaz | J3 |
| `drill.survey`, `drill.mechanical`, `drill.deep` | prospection, foreuses mécanique et profonde | reporté |
| `refinery` | Raffinerie (machine fabriquée à la Station d'ingénierie, onglet Machines) | J5 |
| `mining.lunar`, `mining.martian`, `mining.cryogenic` | extraction sur Lune / Mars / planète glacée : casser le minerai de titane / de soufre / cryogénique | J5 |
| `station.machines` | onglet Machines de la Station d'ingénierie | J3 |
| `energy.cables` | câbles d'énergie | J3 |
| `generator.thermal`, `generator.solar` | générateurs thermique et solaire | J3 |
| `machines.automation` | machines d'automatisation | J3 |
| `machines.alloy` | machines d'alliage | reporté |
| `reactor.nuclear`, `reactor.enriched_fuel` | réacteur nucléaire 5×5×5, combustible enrichi | J3 |
| `station.aerospace`, `rocket` | onglet Aérospatial ; coque, moteur et fusée (tout vol exige `rocket`) | J5 |
| `rocket.fuel`, `rocket.upgrade_module` | carburant de fusée (procédé de la Raffinerie), module pour la 3e destination (ouvre le contrat de mission du Module cryogénique) | J5 |
| `heater.nuclear` | Réchauffeur nucléaire (zone de chaleur de rayon 200, planète glacée ; ne produit pas d'énergie) | J5 |
| `circuits` | circuits électroniques | J3 |
| `dimension.lune`, `dimension.mars`, `dimension.planete_glacee` | accès aux dimensions : vol en fusée et revendication de territoire | J5 |
| `oxygen.systems` | Diffuseur d'oxygène : zones oxygénées | J5 |
| `propulsion.advanced` | Propulseur avancé (carburant des vols × 0,75) | J5 |
| `armor.gas_heated`, `boiler.gas` | Plastron chauffant (armure chauffée au gaz), Chaudière à gaz (zone de chaleur de rayon 50) | J5 |
| `station.armament` | onglet Armement de la Station d'ingénierie | J6 |
| `ammo.basic`, `ammo.piercing`, `ammo.sulfur` | munitions | J6 |
| `weapon.pistol`, `weapon.rifle`, `weapon.sniper`, `weapon.explosives` | armes | J6 |
| `armor.ballistic_light`, `armor.ballistic_heavy` | armures balistiques | J6 |
| `defense.walls`, `defense.turret`, `defense.shield` | défenses anti-PNJ (effet en J7 : cible = PNJ hostiles, absents au J6) | reporté |
| `missile.silo`, `missile.guided`, `missile.heavy_warhead` | silo à missiles et ogives | J7 |

## Paliers
`req` = prérequis croisés (en plus du palier précédent du domaine) ; `pop` = population minimale.

### Civil (15) — population, rendement, stockage
| # | nom | récompenses | req | pop |
|---|---|---|---|---|
| 1 | Registre d'état civil | population_multiplier +0,05 | — | 0 |
| 2 | Cadastre | claim_cost_multiplier −0,05 | — | 1 |
| 3 | Administration municipale | income_multiplier +0,10 | — | 2 |
| 4 | Aqueducs | population_multiplier +0,05 | — | 3 |
| 5 | Entrepôts publics | unlock storage.expansion_1 | — | 4 |
| 6 | Recensement | max_members +2 | — | 5 |
| 7 | Banque nationale | income_multiplier +0,10 | industrie_2 | 6 |
| 8 | Urbanisme | claim_cost_multiplier −0,05 ; population_multiplier +0,05 | — | 7 |
| 9 | Université | research_speed +0,10 | industrie_4 | 8 |
| 10 | Grands entrepôts | unlock storage.expansion_2 | — | 9 |
| 11 | Services publics | income_multiplier +0,15 | — | 10 |
| 12 | Corps diplomatique | unlock diplomacy.embassy | militaire_6 | 11 |
| 13 | Mégapoles | population_multiplier +0,10 ; max_members +2 | — | 12 |
| 14 | Académie des sciences | research_speed +0,15 | technologie_3 | 13 |
| 15 | Âge d'or | income_multiplier +0,20 ; population_multiplier +0,10 | — | 15 |

### Ressources (15) — agriculture, outils de minage, forage
| # | nom | récompenses | req | pop |
|---|---|---|---|---|
| 1 | Rotation des cultures | crop_yield +0,02 | — | 0 |
| 2 | Engrais naturels | crop_yield +0,03 | — | 0 |
| 3 | Outils renforcés | unlock tools.reinforced | — | 1 |
| 4 | Irrigation | crop_yield +0,03 | — | 2 |
| 5 | Prospection | unlock drill.survey | — | 3 |
| 6 | Foreuse mécanique | unlock drill.mechanical | industrie_2 | 4 |
| 7 | Serres | crop_yield +0,05 | — | 5 |
| 8 | Forage de gaz | unlock drill.gas | industrie_4 | 6 |
| 9 | Outils de précision | unlock tools.precision | — | 7 |
| 10 | Agriculture intensive | crop_yield +0,05 | — | 8 |
| 11 | Forage profond | unlock drill.deep | industrie_7 | 9 |
| 12 | Raffinerie | unlock refinery | industrie_8 | 10 |
| 13 | Extraction lunaire | unlock mining.lunar | technologie_2 | 11 |
| 14 | Extraction martienne | unlock mining.martian | technologie_4 | 12 |
| 15 | Ingénierie cryogénique | unlock mining.cryogenic | technologie_6 | 13 |

### Industrie (12) — machines, énergie, nucléaire, prérequis spatial
| # | nom | récompenses | req | pop |
|---|---|---|---|---|
| 1 | Atelier mécanique | unlock station.machines | ressources_1 | 1 |
| 2 | Réseau électrique | unlock energy.cables | — | 2 |
| 3 | Générateur thermique | unlock generator.thermal | — | 3 |
| 4 | Énergie solaire | unlock generator.solar | civil_3 | 4 |
| 5 | Automatisation | unlock machines.automation | — | 5 |
| 6 | Métallurgie avancée | unlock machines.alloy | ressources_6 | 6 |
| 7 | Fission nucléaire | unlock reactor.nuclear | ressources_8 | 8 |
| 8 | Aérospatiale | unlock station.aerospace ; unlock rocket | technologie_1 | 9 |
| 9 | Carburant de fusée | unlock rocket.fuel | ressources_12 | 10 |
| 10 | Combustible enrichi | unlock reactor.enriched_fuel | — | 11 |
| 11 | Module d'amélioration de fusée | unlock rocket.upgrade_module | technologie_5 | 12 |
| 12 | Chauffage nucléaire | unlock heater.nuclear | technologie_6 | 13 |

### Technologie (7) — accès à la planète glacée
| # | nom | récompenses | req | pop |
|---|---|---|---|---|
| 1 | Électronique | unlock circuits | industrie_5 | 6 |
| 2 | Programme lunaire | unlock dimension.lune | industrie_8 | 9 |
| 3 | Systèmes d'oxygène | unlock oxygen.systems | — | 10 |
| 4 | Programme martien | unlock dimension.mars | industrie_9 | 11 |
| 5 | Propulsion avancée | unlock propulsion.advanced | ressources_13 | 12 |
| 6 | Thermorégulation | unlock armor.gas_heated ; unlock boiler.gas | ressources_14 | 13 |
| 7 | Programme glaciaire | unlock dimension.planete_glacee | industrie_12, civil_12, militaire_10 | 15 |

### Militaire (16) — armes à feu, armures balistiques, défenses anti-PNJ
| # | nom | récompenses | req | pop |
|---|---|---|---|---|
| 1 | Arsenal | unlock station.armament | ressources_1 | 1 |
| 2 | Poudre noire | unlock ammo.basic | — | 1 |
| 3 | Armes de poing | unlock weapon.pistol | — | 2 |
| 4 | Gilet pare-balles | unlock armor.ballistic_light | — | 3 |
| 5 | Fusils | unlock weapon.rifle | industrie_2 | 4 |
| 6 | Fortifications | unlock defense.walls | — | 5 |
| 7 | Munitions perforantes | unlock ammo.piercing | — | 6 |
| 8 | Tourelles automatiques | unlock defense.turret | industrie_5 | 7 |
| 9 | Armure balistique lourde | unlock armor.ballistic_heavy | — | 8 |
| 10 | Tir de précision | unlock weapon.sniper | — | 9 |
| 11 | Explosifs | unlock weapon.explosives | — | 10 |
| 12 | Munitions au soufre | unlock ammo.sulfur | technologie_4, ressources_14 | 11 |
| 13 | Silo à missiles | unlock missile.silo | industrie_7 | 12 |
| 14 | Guidage de missiles | unlock missile.guided | — | 13 |
| 15 | Bouclier défensif | unlock defense.shield | — | 14 |
| 16 | Ogives lourdes | unlock missile.heavy_warhead | — | 15 |

Graphe vérifié sans cycle : les prérequis croisés pointent toujours vers un palier atteignable
sans repasser par le palier demandeur (par ex. `industrie_8 → technologie_1 → industrie_5`).

## Barème des coûts (J2)
- Trésorerie ≈ `200 × palier^1,5` (arrondi à 50) ; ×1,2 militaire, ×1,5 industrie, ×4 technologie.
- 1 à 4 types d'objets **vanilla** (ou blocs du mod), quantités croissantes : matériaux communs
  aux premiers paliers, fer/redstone/or ensuite, diamants/obsidienne/objets du Nether en fin d'arbre.
- Les jalons J3 à J6 remplacent une partie des coûts par les ressources qu'ils introduisent
  (minerais lunaires, soufre de Mars, gaz…).
- **Matériaux spatiaux (J5).** Sept paliers remplacent un objet vanilla par un matériau spatial, uniquement dans des
  paliers qui exigent (transitivement) l'extraction correspondante (contrôlé par `check_research.py`, contrôle 9) ;
  trésorerie et points inchangés, sans effet rétroactif sur les paliers déjà terminés :

  | Palier | Remplacé | Par |
  |---|---|---|
  | `technologie_5` Propulsion avancée | charge de feu × 32 | lingot de titane × 24 |
  | `industrie_11` Module de fusée | membrane de phantom × 16 | lingot de titane × 32 |
  | `ressources_14` Extraction martienne | bloc de quartz × 32 | lingot de titane × 32 |
  | `ressources_15` Ingénierie cryogénique | œil de l'Ender × 8 | soufre × 64 |
  | `technologie_6` Thermorégulation | cuir × 32 | soufre × 48 |
  | `industrie_12` Chauffage nucléaire | bloc de magma × 48 | soufre × 64 |
  | `technologie_7` Programme glaciaire | glace bleue × 32 | lingot de titane × 64 |

  Le cristal cryogénique n'entre dans aucun coût de recherche (il sert aux contrats de livraison, voir
  `docs/economie.md`). Aucun marchand n'achète ni ne vend ces matériaux.

## Validation — `tools/check_research.py`
```bash
python3 tools/check_research.py              # vérification complète (code de sortie 1 en cas d'erreur)
python3 tools/check_research.py --self-test  # tests intégrés sur un arbre temporaire
```
Schéma des 65 paliers, séquences, prérequis sans cycle, conformité à ce plan (récompenses, prérequis croisés,
population), barème des coûts et des points, objets connus, traductions, puis (J5) **câblage des déblocages** :
chaque clé de jalon au plus `WIRED_UP_TO` est consommée par un champ `unlock` d'une recette d'ingénierie, un champ
`requires` ou `mining.unlock` d'une planète, ou un littéral du code Java ; un matériau spatial n'apparaît dans le coût
d'un palier que si ce palier exige le palier d'extraction correspondant (`mining.lunar` pour le titane,
`mining.martian` pour le soufre, `mining.cryogenic` pour le cristal).
