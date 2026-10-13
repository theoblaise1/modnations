# Sovereign Frontier — consignes pour contributeurs (humains et agents)

Lire d'abord `README.md` (boucle de jeu, feuille de route, architecture). Jalons livrés et
recettes en jeu : `docs/recette/J<n>.md`.

## Méthode
- Travail par **jalons** (J1…J8). Ne pas commencer un jalon avant validation en jeu du précédent.
- Commits petits et thématiques, messages en français : `feat(Jn): …`, `fix(Jn): …`,
  `test(Jn): …`, `assets: …`, `ci: …`, `docs: …`.
- Avant tout commit : `python3 tools/check_sides.py && python3 tools/check_lang.py &&
  python3 tools/gen_textures.py --check && python3 tools/gen_structures.py --check &&
  ./gradlew build runGameTestServer`.

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
- Aucun objet de palier 3/4, composant de fusée final ou arme militaire max dans un craft ou une caisse.
