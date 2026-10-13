# Sovereign Frontier

Mod Minecraft **1.21.1 / NeoForge 21.1** (Java 21) de gestion nationale et de conquête spatiale,
pensé pour **2 à 10 joueurs** sur un serveur dédié auto-hébergé (LAN ou Tailscale).
Nom de travail, sans lien avec une marque existante. Aucune ressource n'est copiée : textures et
structures sont générées par script, tous les noms sont originaux.

> État : **J1 livré** (nation, grades, trésorerie, territoire, population) ; **J2 livré** (arbre de
> recherche de 65 paliers, onglet Recherche) ; **J3 livré** (énergie, machines, réacteur, Station
> d'ingénierie). Les jalons suivants attendent la validation en jeu du précédent.

---

## Boucle de jeu (vision complète)

1. **Fonder une nation** (`/nation create`), inviter des joueurs, répartir les rôles
   (Chef, Ministre, Officier, Citoyen, Recrue) et leurs permissions.
2. Le chunk de fondation devient la **capitale** (offerte). **Financer la trésorerie** au bloc
   *Trésor national* posé sur son territoire, puis **revendiquer des chunks** contigus
   (`coût = base × 1,08^chunks déjà revendiqués`, capitale comprise). Le territoire protège les constructions,
   bloque les monstres de surface et augmente le rendement des cultures de 5 %.
3. **Peupler** : chaque bâtiment civil validé (maison, ferme, atelier) ajoute un habitant,
   qui rapporte un revenu passif à la trésorerie.
4. **Rechercher** (J2) : 5 domaines, une seule recherche active, durée réelle croissante,
   progression même hors ligne. Les paliers débloquent recettes, blocs, dimensions, bonus.
5. **Industrialiser** (J3) : énergie propre au mod, générateurs, réacteur nucléaire multibloc,
   Station d'ingénierie, forage de gaz **fini** par dimension, ce qui pousse vers l'espace.
6. **Commercer et contracter** (J4) : marchands PNJ, entreprises, contrats, `/daily`, caisses.
7. **Conquérir l'espace** (J5) : Lune, Mars, planète glacée ; fusée et carburant.
8. **Explorer et combattre** (J6) : donjons à clé, boss, armes à feu, missiles. Les objets de
   palier 3 et 4 ne se fabriquent jamais : ils proviennent des donjons et des contrats.
9. **Diplomatie et guerre** (J7) : traités, déclaration avec préparation de 24 h, score de
   guerre, protections anti-écrasement, nations PNJ.

## Feuille de route

| Jalon | Contenu | État |
|---|---|---|
| J1 | Nation multi-membres (syst. 1 + 7 hors guerre), trésorerie, territoire, population, écran national | ✅ livré |
| J2 | Arbre de recherche JSON (syst. 2) + onglet Recherche | ✅ livré |
| J3 | Énergie, câbles, générateurs, réacteur, Station d'ingénierie, forage de gaz (syst. 3) | ✅ livré, à valider en jeu |
| J4 | Économie PNJ, entreprises, contrats (syst. 5), `/daily` et caisses (syst. 10) | — |
| J5 | Dimensions Lune / Mars / planète glacée, fusée (syst. 4) | — |
| J6 | Donjons, boss, armement (syst. 6) | — |
| J7 | Diplomatie, guerre, silo à missiles, nations PNJ (syst. 8) | — |
| J8 | Grade Souverain : `/home`, `/tpa`, `/back`, `/fly`… et leurs restrictions de guerre (syst. 9) | — |

La répartition des systèmes 7 à 10 (ajoutés après le découpage initial J1–J6) est une
**proposition** à valider.

---

## Essayer le mod

Le `.jar` doit être installé **sur le serveur et sur chaque client** (blocs, écrans et paquets
réseau propres au mod). Prérequis : **Java 21**, **Minecraft 1.21.1**, **NeoForge 21.1.x**
(testé avec 21.1.219).

### 1. Récupérer le `.jar`
- **Depuis la CI** : onglet *Actions* du dépôt → dernier run « Build » vert de la branche →
  section *Artifacts* → `sovereignfrontier-<commit>` (archive zip contenant
  `sovereignfrontier-<version>.jar`). Il faut être connecté à GitHub.
- **Ou en local** : `./gradlew build` → `build/libs/sovereignfrontier-<version>.jar`.

### 2. Client (chaque joueur)
1. Télécharger l'installateur NeoForge 21.1.x (`neoforge-21.1.219-installer.jar` sur
   neoforged.net), choisir *Install client*.
2. Copier le `.jar` du mod dans `.minecraft/mods/`.
3. Lancer le profil « NeoForge 1.21.1 » du lanceur. Le mod apparaît dans *Mods* du menu principal.

### 3. Serveur dédié (auto-hébergé)
```bash
java -jar neoforge-21.1.219-installer.jar --installServer   # dans un dossier vide
cp sovereignfrontier-*.jar mods/
./run.sh            # run.bat sous Windows ; 1er lancement : passer eula=true dans eula.txt
```
Mémoire : `-Xmx4G` dans `user_jvm_args.txt` suffit pour 2 à 10 joueurs. Configuration du mod :
`world/serverconfig/sovereignfrontier-server.toml` (créée au 1er démarrage, rechargée à chaud).

### 4. Essai rapide en solo ou entre développeurs
- Sans rien installer : `./gradlew runClient` lance un client avec le mod (Solo → nouveau monde).
- En jeu, en opérateur (`/op <pseudo>` sur serveur, triches activées en solo) :
  `/nation create Aurore` → touche **N** ; `/sfadmin money give <joueur> 100000` ;
  `/sfadmin treasury Aurore 100000` (ajoute au trésor) ; `/sfadmin research Aurore complete industrie_1` (Tab
  complète les paliers). Le Créatif contient tous les blocs dans l'onglet « Sovereign Frontier ».
- Suivre ensuite les recettes en jeu `docs/recette/J1.md` à `J3.md`.

## Contenu du jalon J1

### Blocs
| Bloc | Rôle |
|---|---|
| **Trésor national** | Clic droit (sur le territoire de sa nation) : onglet Trésorerie avec dépôt / retrait. |
| **Coffre national** | Multibloc : cœur posé au centre d'un socle 3×3 de *Blindage national*. 54 cases **partagées par toute la nation** ; ouverture soumise à la permission `coffre`. |
| **Blindage national** | Socle du coffre national. |
| **Registre civil** | Placé dans un bâtiment, clic droit pour l'enregistrer : **maison** (pièce close et couverte avec lit et porte), **atelier** (établi, four, porte) ou **ferme** (≥ 16 terres labourées dans un rayon de 4). +1 habitant chacun. |

### Commandes
| Commande | Effet |
|---|---|
| `/nation create <nom>` | Fonder une nation (vous en êtes le Chef) ; le chunk courant devient la capitale. |
| `/nation invite <joueur>` · `/nation join <nation>` | Inviter (permission `inviter`) · accepter (clic sur **[Rejoindre]**). |
| `/nation kick <membre>` · `/nation leave` | Exclure un membre de rang inférieur (`exclure`) · quitter. |
| `/nation transfer <membre>` · `/nation disband confirm` | Transmettre la direction · dissoudre (Chef). |
| `/nation role <membre> <rang>` | Changer un rang (Chef). |
| `/nation perm <rang> <permission> <oui\|non>` · `/nation perms` | Modifier / afficher la matrice des permissions (aussi éditable dans l'onglet Rôles). |
| `/nation info [nation]` · `/nation list` · `/nation log` | Fiche, liste, journal des 50 dernières actions sensibles. |
| `/claim` · `/unclaim` · `/map` | Revendiquer / abandonner le chunk courant · carte ASCII. |
| `/balance [joueur]` · `/pay <joueur> <montant>` · `/baltop` | Économie personnelle. |
| `/sfadmin money\|treasury\|bypass …` | Outils opérateur (niveau 2) pour tester et modérer. |

Touche **N** : écran national (Aperçu, Science, Trésor, Carte, Rôles, Journal, Contrats).

### Recherche (J2)
| Commande | Effet |
|---|---|
| `/research` | Recherche en cours et progression. |
| `/research list [domaine]` | Paliers d'un domaine et leur état (✔ terminé, ⌛ en cours, ● disponible, ✘ verrouillé). |
| `/research info <palier>` | Fiche : coûts (✔/✘ selon le coffre national), prérequis, durée, récompenses. |
| `/research start <palier>` · `/research cancel` | Lancer (permission `rechercher`) · annuler (50 % du coût rendu). |
| `/sfadmin research <nation> complete <palier>\|finish\|points <n>` | Outils opérateur. |

Une seule recherche active par nation ; les objets sont prélevés dans le **coffre national** ;
la recherche avance même serveur éteint (horodatages réels).

### Industrie (J3)
Énergie propre au mod : **énergon (En)**. Les câbles relient les machines adjacentes ; chaque
générateur sert d'abord les consommateurs, puis le stockage ; le stockage ne sert que les consommateurs.

| Bloc | Rôle | Détail |
|---|---|---|
| Station d'ingénierie | Fabrication | Craft vanilla ; onglets Machines (J3), Armement (J6), Aérospatial (J5). Recettes JSON `data/<ns>/engineering/`, débloquées par la recherche, ingrédients pris dans l'inventaire. |
| Câble d'énergie | Transport | Réseau mis en cache par dimension (≤ 4096 câbles). |
| Générateur thermique | Production | Brûle un combustible de four : 20 En/t. |
| Panneau solaire | Production | 8 En/t de jour, ciel visible. |
| Cellule d'énergie | Stockage | 200 000 En, 400 En/t en entrée et sortie. |
| Foreuse à gaz | Consommation | 40 En/t ; remplit des bouteilles. Réserve **finie** par dimension (200 000 L). |
| Réacteur (contrôleur) | Production | Multibloc 5×5×5 : coque de caissons, intérieur de cellules de combustible, dissipateurs ou air. 120 En/t par cellule ; chaleur 4/cellule − 3/dissipateur ; fusion et explosion à 10 000. |

Carte de l'industrie (paliers → déblocages) : `industrie_1` station, `industrie_2` câbles,
`industrie_3` thermique, `industrie_4` solaire, `industrie_5` cellule, `ressources_8` foreuse et
bouteilles, `industrie_7` réacteur, `industrie_10` combustible efficace, `technologie_1` circuits.

### Permissions (identifiants de commande)
`revendiquer`, `abandonner`, `retirer`, `coffre`, `construire`, `inviter`, `exclure`,
`rechercher`, `declarer_guerre`, `signer_traite`, `tirer_missile`. Le Chef les a toutes.

### Configuration serveur
`<monde>/serverconfig/sovereignfrontier-server.toml`, rechargée à chaud :
`startingBalance`, `creationCost`, `maxMembers`, `inviteExpiryMinutes`, `claimBaseCost`,
`claimCostGrowth`, `requireContiguous`, `unclaimRefundRatio`, `surfaceSpawnYOffset`,
`cropYieldBonus`, `protectFromExplosions`, `treasuryReach`, `incomeIntervalTicks`,
`incomePerInhabitant`, `maxBuildingVolume`, `farmMinFarmland`, `buildingRecheckTicks`,
`researchCancelRefund` ; section `[energy]` : `thermalOutput`, `solarOutput`, `drillEnergyPerTick`,
`drillLitersPerCycle`, `gasPerDimension`, `reactorOutputPerCell`, `reactorHeatPerCell`,
`reactorDissipationPerSink`, `reactorMaxHeat`, `reactorFuelTicks`, `reactorExplosionPower`.

---

## Ajouter ou modifier un palier de recherche

Les paliers ne sont **pas** codés en Java : ils sont lus depuis le datapack à chaque chargement
(`/reload` en jeu), donc modifiables par un datapack de monde sans recompiler. Le plan complet
des 65 paliers et le catalogue des déblocages sont dans [`docs/recherche.md`](docs/recherche.md).

1. Créer `data/<namespace>/research/<domaine>_<palier>.json` (dans `src/main/resources/` ou dans
   un datapack). L'identifiant du palier est le chemin du fichier, ex. `sovereignfrontier:industrie_8`.
2. Renseigner :

```json
{
  "domain": "industrie",
  "tier": 8,
  "cost": {
    "treasury": 6800,
    "points": 0,
    "items": [
      { "item": "minecraft:firework_rocket", "count": 64 },
      { "item": "minecraft:diamond", "count": 8 }
    ]
  },
  "requires": ["sovereignfrontier:technologie_1"],
  "min_population": 9,
  "rewards": [
    { "type": "unlock", "key": "rocket" },
    { "type": "bonus", "stat": "crop_yield", "amount": 0.02 }
  ]
}
```

   - `domain` : `civil` (15 paliers), `militaire` (16), `ressources` (15), `industrie` (12),
     `technologie` (7). Le palier `n` exige implicitement le palier `n-1` du même domaine :
     `requires` ne liste que les prérequis **croisés**.
   - `cost.items` : objets prélevés dans le **coffre national** au lancement ; `treasury` est
     débité de la trésorerie ; `points` (points de recherche, J4) est facultatif.
   - Durée : `120 s × palier^1,35` (champ facultatif `duration_seconds` pour la forcer), divisée
     par le bonus `research_speed`.
   - `rewards` : `unlock` (clé du catalogue de `docs/recherche.md`) ou `bonus` (`stat` parmi
     `population_multiplier`, `income_multiplier`, `crop_yield`, `claim_cost_multiplier`,
     `max_members`, `research_speed`).
3. Ajouter dans `assets/sovereignfrontier/lang/en_us.json` : `sovereignfrontier.research.<chemin>`
   (nom), `sovereignfrontier.research.<chemin>.desc` (description) et, pour une nouvelle clé,
   `sovereignfrontier.unlock.<clé>`.
4. Vérifier : `python3 tools/check_research.py` (schéma, séquences, cycles, conformité au plan),
   puis `/reload` en jeu. Un fichier invalide est ignoré avec un message explicite dans le journal
   du serveur (`Palier de recherche … ignoré : …`).

---

## Développement

```bash
./gradlew build               # compile et produit build/libs/sovereignfrontier-<version>.jar
./gradlew runClient           # client de développement
./gradlew runServer           # serveur dédié de développement (dossier runs/server/)
./gradlew runGameTestServer   # serveur headless + GameTests (vérification serveur dédié)

python3 tools/gen_textures.py      # régénère les textures 16x16 (Pillow) ; --check en CI ; --preview f.png
python3 tools/gen_structures.py    # régénère les gabarits NBT ; --check en CI
python3 tools/check_sides.py       # aucune classe cliente hors du paquet client ; --self-test
python3 tools/check_lang.py        # toute clé de traduction utilisée existe
python3 tools/check_research.py    # arbre de recherche conforme à docs/recherche.md ; --self-test

# Captures automatiques de l'écran national (client sur écran virtuel, runs/client/screenshots/)
SF_SCREENSHOTS=1 LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient
```

La CI (`.github/workflows/build.yml`) exécute ces contrôles, `./gradlew build`, les GameTests
sur serveur headless, et publie le `.jar` à chaque push.

### Arborescence (`io.github.theoblaise1.sovereignfrontier`)
| Paquet | Contenu |
|---|---|
| `SovereignFrontier`, `SFConfig` | Point d'entrée commun, configuration serveur TOML. |
| `registry/` | Tous les `DeferredRegister` (blocs, objets, onglet créatif, attachments). |
| `nation/` | Modèle (`Nation`, rôles, permissions), `NationData` (SavedData), `TerritoryIndex` (cache), logique métier (`NationManager`, `CivilBuildings`). |
| `research/` | Arbre de recherche : définitions JSON, registre rechargé par datapack, état par nation, `ResearchManager`. |
| `energy/` | Tampons d'énergie, câbles, réseau (`EnergyNetworks`, cache par dimension). |
| `machine/` | Bloc et entité de machine (`MachineKind`), menu, réacteur multibloc, réserve de gaz (`GasData`). |
| `engineering/` | Station d'ingénierie, recettes JSON rechargées par datapack, fabrication côté serveur. |
| `economy/` | `EconomyData` : portefeuilles personnels. |
| `player/` | DataAttachments joueur. |
| `block/` | Blocs nationaux et menu du coffre. |
| `command/` | Commandes Brigadier et autocomplétion. |
| `event/` | Abonnés aux événements serveur (protection, ticks, connexions). |
| `network/` | Payloads (NeoForge 1.21) et construction des snapshots. |
| `client/` | **Seul** paquet autorisé à toucher au code client (`@OnlyIn(Dist.CLIENT)`) ; `client/dev/` : outil de captures (inactif sans `SF_SCREENSHOTS`). |
| `gametest/` | GameTests exécutés en CI. |

### Règles d'architecture
- **Temps** : guerres, trêves et cooldowns en **timestamps epoch** persistés (survivent aux
  redémarrages) ; systèmes périodiques internes par **compteur de ticks** dans un SavedData.
  Jamais de scan de chunks par tick.
- **Territoire** : toute vérification passe par `NationData#ownerAt` (cache par chunk, O(1)) ;
  ne jamais parcourir la liste des nations dans un gestionnaire d'événement.
- **Côtés** : le code commun ne référence jamais `net.minecraft.client` ; les gestionnaires de
  paquets serveur→client sont des lambdas qui délèguent à `client/` (résolues seulement côté client).
- **Données de contenu** en JSON de datapack ; textures et structures produites par `tools/`.
- **Messages** : clés `sovereignfrontier.*` ; `en_us.json` est rédigé en français (langue de repli
  de toutes les locales).
