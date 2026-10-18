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

## Reprise du travail (état au 2026-10-18)

### Où on en est
- **Branches** : travail sur `claude/sovereign-frontier-mod-w9ukee` (dernier commit : `5a19c95`,
  poussé). `main` est à `ea2d8d0` : la PR #1 a été fusionnée le 2026-10-16. Ne pas ouvrir de PR
  sans demande explicite.
- **Jalons** : J1, J2 et J3 sont **livrés** dans le code. **J3 attend la validation en jeu**
  (`docs/recette/J3.md`). **J4 ne démarre pas** tant que cette validation n'est pas faite.
- **Recettes à faire** : `docs/recette/J1.md`, `J2.md`, `J3.md`, et `revue-multijoueur.md`
  (correctifs de la revue, deux clients requis).
- **Guide** : `docs/guide.md` (joueurs et opérateur, J1–J3). À mettre à jour à chaque jalon.
- **Tests** : `./gradlew build runGameTestServer` passe avec **48 GameTests** sur un monde neuf.
  Le monde `runs/gameTestServer` persiste entre deux runs et peut faire échouer un test au second
  lancement (état laissé par le run précédent). Avant chaque vérification :
  `rm -rf runs/gameTestServer`.
- **CI** : `.github/workflows/build.yml` relance les contrôles, le build, les GameTests et publie le
  `.jar` à chaque push. Vérifier le résultat sur le commit poussé avant de considérer un travail fini.

### Points ouverts (par priorité)
1. **Validation en jeu de J3**, puis recette multijoueur à deux clients. Tant que ce n'est pas fait,
   rien n'est vérifié en jeu : les tests couvrent la logique, pas l'interaction réelle.
2. **Interfaces (style NationsGlory)** : mises de côté à la demande de l'utilisateur. Aucune capture
   de référence n'est disponible à ce jour : l'utilisateur doit en fournir. Le premier jet partiel
   (widgets `client/ui/`, écran national, recherche, ingénierie) n'a **pas** été commité : il a été
   sauvegardé en patch dans le scratchpad de session, qui peut disparaître. Repartir du code actuel
   s'il manque.
3. **`economie-5`** : les soldes personnels (`EconomyData`) et les trésors nationaux (`NationData`)
   sont dans deux fichiers distincts. Un crash entre deux écritures peut perdre un transfert. La
   correction (regrouper les soldes dans `NationData`) change le format de sauvegarde : décision de
   l'utilisateur avant de la faire.
4. **Trésors et coffres posés avant le commit `4a1554d`** ne sont pas référencés comme cœurs de
   nation : leur parcelle peut encore être abandonnée. Une migration serait nécessaire.
5. **Journal** : le limiteur d'actions (5/s) borne le flot, mais un membre peut toujours remplir le
   journal de 50 entrées (pas de regroupement des dépôts).
6. **Monde de test persistant** : tâche suggérée (réinitialiser le monde à chaque run), non lancée.
7. **Hébergement Aternos** : NeoForge figure dans le catalogue ; l'envoi d'un `.jar` de mod
   personnalisé n'est pas confirmé. Pour jouer, préférer un serveur local (voir `docs/guide.md`).

### Procédure de reprise
1. `git status`, puis `git log --oneline -5` : vérifier qu'on est sur la branche de travail et à jour.
2. Lire `README.md` (feuille de route), `docs/guide.md`, puis la recette du jalon en cours.
3. Demander à l'utilisateur si J3 est validée avant tout travail sur J4.
4. Vérifier : `python3 tools/check_sides.py && python3 tools/check_lang.py &&
   python3 tools/check_research.py && python3 tools/gen_textures.py --check &&
   python3 tools/gen_structures.py --check`, puis `rm -rf runs/gameTestServer && ./gradlew build
   runGameTestServer`.

### Conventions de travail
- **Un seul Gradle à la fois** : ne jamais lancer deux commandes Gradle en parallèle dans le même
  dépôt (verrou et conflits de build). Les sous-agents respectent la même règle.
- **Sous-agents** : ne commitent pas et ne poussent pas. Ils modifient seulement les fichiers listés
  dans leur mission ; le fichier de travail va dans le scratchpad, jamais dans le dépôt.
- **Push** : sur la branche de travail. Vers `main` uniquement sur demande explicite, et seulement
  si les tests passent ou si l'échec n'est pas essentiel au lancement ou à la jouabilité.
- **Protocole réseau** : `ModPayloads.PROTOCOL_VERSION` doit être incrémenté à chaque changement de
  format d'un paquet (valeur actuelle : `3`).
- **Mixins** : les cibles package-private (ex. `Silverfish$SilverfishWakeUpFriendsGoal`) se déclarent
  par chaîne (`targets = "…"`). Les déclarer dans `sovereignfrontier.mixins.json`.
- **Vidéos et captures** : l'agent ne peut pas regarder une vidéo ; seules les captures fournies
  par l'utilisateur servent de référence visuelle.
