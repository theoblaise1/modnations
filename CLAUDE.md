# Sovereign Frontier — consignes pour contributeurs (humains et agents)

Lire d'abord `README.md` (boucle de jeu, feuille de route, état des jalons, architecture). Recettes en jeu :
`docs/recette/J<n>.md`.

## Méthode
- Travail par **jalons** (J1…J8). Ne pas commencer un jalon avant validation en jeu du précédent.
- Un jalon = une conception (`docs/conception/J<n>.md` : décisions, formats, GameTests, lots WP0…WP5 avec les
  fichiers possédés par chaque lot), puis le développement par lots, puis la recette jouée par l'utilisateur.
  WP0 (socle) est fusionné seul en premier et possède les fichiers partagés (registres, config, paquets,
  `AdminCommand`…) ; les lots parallèles ne les modifient pas ; WP5 (intégration, documentation) est le dernier.
- Commits petits et thématiques, messages en français : `feat(Jn): …`, `fix(Jn): …`, `test(Jn): …`,
  `assets(Jn): …` (données), `chore(Jn): …` (version), `ci: …`, `docs: …`.
- Avant tout commit : `python3 tools/check_sides.py && python3 tools/check_lang.py &&
  python3 tools/check_research.py && python3 tools/check_economy.py --self-test &&
  python3 tools/check_economy.py && python3 tools/check_space.py && python3 tools/gen_textures.py --check &&
  python3 tools/gen_structures.py --check && ./gradlew build runGameTestServer`.
- Rôle des scripts de `tools/` : `check_sides` (aucun code client hors de `client/`), `check_lang` (clés de
  traduction utilisées et ordre trié de `en_us.json`), `check_research` (arbre de recherche, conformité à
  `docs/recherche.md`, câblage des déblocages), `check_economy` (tags `restreint`, caisses, marchands, contrats,
  entreprises), `check_space` (planètes et dimensions), `gen_textures` et `gen_structures` (`--check` : fichiers
  générés à jour). `check_research`, `check_economy` et `check_space` ont un `--self-test` à relancer quand on
  modifie le script.

## Règles non négociables
- MC 1.21.1, NeoForge 21.1.x, Java 21, NeoGradle. Registres centralisés dans `registry/`.
- Aucun code client hors de `client/` (classes `@OnlyIn(Dist.CLIENT)`) ; payloads serveur→client
  enregistrés avec une lambda qui délègue à `client/ClientPayloadHandler`.
- Vérifs de territoire via `NationData#ownerAt` (cache par chunk) ; jamais de parcours des nations
  ni de scan de chunks dans un événement ou par tick.
- Timers de guerre/trêve/cooldown = timestamps epoch persistés ; boucles internes = compteur de
  ticks dans un SavedData ; boucle de guerre ≤ 1 passage / 20 ticks.
- Contenu (recherche, contrats, butin, recettes, caisses) en JSON de datapack.
- Textures : `tools/gen_textures.py` ; structures : `tools/gen_structures.py`. Jamais de binaire
  écrit à la main.
- Messages joueur : clés `sovereignfrontier.*` dans `en_us.json` (rédigé en français).
- Aucun objet de palier 3/4, composant de fusée final ou arme militaire max dans un craft, une caisse,
  un marchand, une entreprise ni `/daily` : tout tel objet doit être ajouté à
  `#sovereignfrontier:restreint/<palier_3|palier_4|fusee_finale|arme_max>` (contrôlé par
  `tools/check_economy.py`).

## Conventions
- Documentation de référence entre le contenu JSON et le code : `docs/recherche.md`, `docs/economie.md`,
  `docs/espace.md`. Tout changement de format ou de règle de jeu les met à jour dans le même lot.
- Chaque sous-commande de `/sfadmin` est fournie par une méthode `registerAdmin(root)` de la classe de commande
  concernée, appelée depuis `AdminCommand`.
- Graphismes : le lot graphique (textures, modèles, états de bloc, rendus) est traité à part et reste en attente
  pour J4 et J5 (`docs/conception/J5.md` §8.7). Ne pas l'anticiper dans les lots de jeu : les blocs et objets sans
  modèle s'affichent avec le modèle manquant.

## GameTests
- Un test qui fonde une nation la dissout dans un `finally` avec `J4TestUtil.disband` ou `J5TestUtil.disband`
  (ils vident le coffre ; `NationManager.disband` refuse un coffre non vide et la nation resterait revendiquée).
  Un test qui enregistre un avant-poste le retire de même.
- Ne pas supposer l'alignement des chunks ni l'espace libre autour d'une zone de test (le serveur de GameTests
  dresse un mur de barrières autour de chaque zone).
- Le monde de test est conservé d'une exécution à l'autre : supprimer `runs/gameTestServer/world` avant la
  vérification finale.
- Les dimensions des planètes n'existent pas en GameTest : leur génération se vérifie à la recette, sur un
  serveur dédié.
- Tout bloc, tout joueur et tout témoin d'un test doit se trouver dans la zone dégagée par `floor()` : étendre ses
  bornes plutôt que supposer l'espace voisin libre (le mur de barrières peut tomber sur une colonne utilisée).
  L'œil d'un joueur de test (position + 1,62) ne doit jamais entrer dans un bloc.
- Le serveur de GameTests tire une position de départ aléatoire à chaque exécution : une panne rare ne se voit qu'en
  relançant `runGameTestServer` plusieurs fois (après avoir supprimé `runs/gameTestServer/world`). Un test qui échoue
  sur son seul **témoin** (le contrôle, pas la protection testée) trahit d'abord une géométrie de test, pas le mod.

## Reprise du travail (état au 2026-10-22)

### Où on en est
- **Branche de référence** : `claude/pensive-pasteur-nid4rh`, alignée sur `main`. Elle contient J1 à J5 et les
  correctifs de `claude/sovereign-frontier-mod-w9ukee` (guide, entonnoir, poisson d'argent). Les branches
  `claude/nifty-ptolemy-q1umi6` (jusqu'à `1f83239`) et `claude/sovereign-frontier-mod-w9ukee` (jusqu'à `00359dd`)
  y sont entièrement fusionnées : ne plus y travailler, sinon les lignes divergent de nouveau. Ne pas ouvrir de PR
  sans demande explicite.
- **Jalons** : voir l'état dans `README.md`. J5 attend la validation en jeu (`docs/recette/J5.md`) ; J6 ne démarre
  pas avant.
- **Recettes à jouer** : `docs/recette/J5.md` ; `docs/recette/revue-multijoueur.md` (deux clients requis) dont le
  statut n'est pas confirmé.
- **Guide** : `docs/guide.md` (joueurs et opérateur) couvre J1 à J3 ; J4 et J5 restent à y ajouter.
- **CI** : `.github/workflows/build.yml` relance les contrôles, le build, les GameTests et publie le `.jar` à chaque
  push. Vérifier le résultat sur le commit poussé avant de considérer un travail fini.

### Points ouverts
1. **Interfaces (style NationsGlory)** : mises de côté à la demande de l'utilisateur ; aucune capture de référence
   n'est disponible, l'utilisateur doit en fournir. Un premier jet partiel (widgets `client/ui/`) n'a **pas** été
   commité.
2. **`economie-5`** : les soldes personnels (`EconomyData`) et les trésors nationaux (`NationData`) sont dans deux
   fichiers distincts ; un arrêt brutal entre deux écritures peut perdre un transfert. Le corriger (regrouper les
   soldes dans `NationData`) change le format de sauvegarde : décision de l'utilisateur d'abord.
3. **Trésors et coffres posés avant le commit `4a1554d`** ne sont pas référencés comme cœurs de nation : leur
   parcelle peut encore être abandonnée. Une migration serait nécessaire.
4. **Journal** : le limiteur d'actions (5/s) borne le flot, mais un membre peut toujours remplir le journal de
   50 entrées (pas de regroupement des dépôts).
5. **Hébergement Aternos** : NeoForge figure au catalogue, l'envoi d'un `.jar` de mod personnalisé n'est pas
   confirmé. Pour jouer, préférer un serveur local (voir `docs/guide.md`).

### Procédure de reprise
1. `git status`, puis `git log --oneline -5` : vérifier la branche et qu'elle est à jour de `origin`.
2. Lire `README.md` (état des jalons), `docs/guide.md`, puis la recette du jalon en cours.
3. Ne pas démarrer un jalon avant la validation en jeu du précédent (voir Méthode) : la demander à l'utilisateur.
4. Lancer la vérification « Avant tout commit » (voir Méthode), après `rm -rf runs/gameTestServer/world`.

### Conventions de travail
- **Un seul Gradle à la fois** : jamais deux commandes Gradle en parallèle dans le même dépôt (verrou et conflits
  de build). Les sous-agents respectent la même règle.
- **Sous-agents** : ne commitent pas et ne poussent pas. Ils ne modifient que les fichiers listés dans leur mission ;
  le fichier de travail va dans le scratchpad, jamais dans le dépôt.
- **Push** : sur la branche de travail. Vers `main` uniquement sur demande explicite, et seulement si les tests
  passent (de préférence une fois la CI verte sur la branche de travail).
- **Protocole réseau** : `ModPayloads.PROTOCOL_VERSION` doit être incrémenté à chaque changement de format d'un
  paquet (valeur actuelle : `4`).
- **Mixins** : les cibles package-private (ex. `Silverfish$SilverfishWakeUpFriendsGoal`) se déclarent par chaîne
  (`targets = "…"`) dans `sovereignfrontier.mixins.json`.
- **Vidéos et captures** : l'agent ne peut pas regarder une vidéo ; seules les captures fournies par l'utilisateur
  servent de référence visuelle.
