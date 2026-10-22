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
