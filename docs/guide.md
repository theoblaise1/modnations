# Guide de Sovereign Frontier (J1–J7)

Ce guide couvre ce qui est livré jusqu'au J7 : nations, territoire, population, recherche, industrie, contrats,
caisses, fusée, armes, donjon, diplomatie, guerre, traités, silo et missiles. Les défenses anti-PNJ, les nations PNJ
et les donjons planétaires arrivent après J7.

Pour installer le mod, voir la section « Essayer le mod » du [README](../README.md).

---

## 1. Premiers pas

1. **Fonder une nation** : `/nation create <nom>`. Vous en êtes le Chef. Le chunk où vous êtes
   devient la **capitale**, offerte.
2. **Financer le Trésor** : posez un *Trésor national* sur votre territoire, puis faites un clic
   droit dessus. Vous ouvrez l'onglet Trésorerie, où vous déposez ou retirez des crédits.
3. **Revendiquer** : `/claim` sur le chunk voisin de votre territoire. Le prix augmente avec le
   nombre de chunks déjà possédés. Un chunk doit toucher votre territoire.
4. **Inviter** : `/nation invite <joueur>`. Le joueur accepte avec `/nation join <nation>` ou en
   cliquant sur **[Rejoindre]** dans le message reçu. L'invitation expire.
5. **Ouvrir l'écran national** : touche **N**. Onglets : Aperçu, Recherche, Trésor, Territoire,
   Rôles, Journal.

## 2. Rôles et permissions

Cinq rangs, du plus haut au plus bas : Chef, Ministre, Officier, Citoyen, Recrue.
Chaque permission s'accorde ou se retire par rang, dans l'onglet **Rôles** (Chef seulement)
ou avec `/nation perm <rang> <permission> <oui|non>`. `/nation perms` affiche la matrice.

Un rang ne peut exclure qu'un rang inférieur. Seul le Chef peut transmettre la direction
(`/nation transfer`) ou dissoudre la nation (`/nation disband confirm`). La dissolution est refusée
tant que le coffre national contient des objets.

## 3. Territoire et protections

- Dans le territoire d'une nation, vous ne pouvez ni casser, ni poser, ni utiliser les blocs
  sans la permission correspondante. Le feu ne se propage pas de l'autre côté d'une frontière.
- Un joueur en dehors de toute nation n'agit pas sur un territoire tenu.
- L'entrée et la sortie d'un territoire s'affichent dans la barre d'action.
- `/map` affiche la carte en texte ; l'onglet **Territoire** de l'écran national en montre une
  version colorée (clic gauche : revendiquer, clic droit : abandonner).
- **Abandonner** un chunk qui porte le Trésor ou le coffre national est refusé. Retirez le bloc
  avant d'abandonner la parcelle.

## 4. Population et revenu

- Une **maison**, un **atelier** ou une **ferme** validés ajoutent un habitant chacun. Posez un
  *Registre civil* dans le bâtiment et faites un clic droit dessus pour l'enregistrer.
- Une maison doit être une pièce close, couverte, avec un lit et une porte.
- Un atelier doit comporter un établi, un four et une porte.
- Une ferme doit compter au moins 16 terres labourées dans un rayon de 4 blocs.
- Les habitants rapportent un revenu passif à la trésorerie, à intervalles réguliers.
- Un bâtiment qui ne respecte plus ces conditions cesse d'être compté. Il est revérifié
  régulièrement.

## 5. Recherche

Les paliers de recherche sont organisés en cinq domaines. Une seule recherche est active par
nation.

| Commande | Effet |
|---|---|
| `/research` | Recherche en cours et progression. |
| `/research list [domaine]` | Paliers d'un domaine : ✔ terminé, ⌛ en cours, ● disponible, ✘ verrouillé. |
| `/research info <palier>` | Coûts, prérequis, durée, récompenses. |
| `/research start <palier>` | Lancer (permission `rechercher`). |
| `/research cancel` | Annuler : une partie du coût est rendue, les objets prélevés sont perdus. |

- Les **objets** du palier sont prélevés dans le **coffre national** au lancement. La **trésorerie**
  est débitée à la même occasion.
- La recherche avance même quand le serveur est éteint : elle est mesurée en temps réel.
- Un palier terminé débloque recettes, blocs ou bonus, selon l'arbre (`docs/recherche.md`).

## 6. Industrie

L'énergie du mod s'appelle **l'énergon (En)**. Les câbles relient les machines voisines.
Chaque générateur alimente d'abord les consommateurs, puis le stockage ; le stockage ne sert
que les consommateurs.

| Bloc | Rôle | Détail |
|---|---|---|
| **Station d'ingénierie** | Fabrication | Recettes débloquées par la recherche. Les ingrédients sont pris dans votre inventaire. Il faut rester à moins de 8 blocs. |
| **Câble d'énergie** | Transport | Un câble produit 8 unités à la fabrication. |
| **Générateur thermique** | Production | Brûle du combustible de four : 20 En/t. |
| **Panneau solaire** | Production | 8 En/t de jour, sous ciel visible. Rien la nuit, sous la pluie ou sous un bloc. |
| **Cellule d'énergie** | Stockage | 200 000 En, 400 En/t en entrée comme en sortie. |
| **Foreuse à gaz** | Consommation | Remplit des bouteilles. La réserve est **finie** par dimension. |
| **Réacteur** (contrôleur) | Production | Multibloc 5×5×5 à construire avec des caissons. Voir ci-dessous. |

**Réacteur.** Construisez une coque 5×5×5 de caissons, puis placez le contrôleur au centre d'une
face. L'intérieur contient des cellules de combustible, des dissipateurs ou de l'air. Le GUI
indique « structure valide » quand elle est correcte.

- Chaque cellule produit 120 En/t, mais la chaleur monte de 4 par cellule et par tick. Chaque
  dissipateur la fait redescendre de 3.
- À 75 % de la limite, un avertissement « ⚠ Réacteur en surchauffe » s'affiche.
- À 10 000, le réacteur **explose**, même dans un territoire tenu par une nation.
- Le bouton **Arrêter** fait redescendre la chaleur. Un combustible enrichi dure 6 000 ticks (5 min).

Paliers qui débloquent les blocs ci-dessus : `industrie_1` (station), `industrie_2` (câbles),
`industrie_3` (thermique), `industrie_4` (solaire), `industrie_5` (cellule), `ressources_8` (foreuse),
`industrie_7` (réacteur). Le détail est dans l'écran Recherche.

---

## 7. Contrats, caisses et marchands (J4)

- **Contrats nationaux.** Chaque nation a un tableau de contrats, renouvelé toutes les 24 h, ouvert 12 h après la
  fondation. La permission `économie` accepte et abandonne ; tout membre peut livrer (`/contract deliver <n>`). À la
  réussite, la moitié de la somme va aux contributeurs, et la nation gagne des points de recherche.
- **Entreprises.** Des habitants affectés (`/enterprise assign <secteur> <nombre>`) produisent de l'argent et des
  objets pour le coffre national.
- **Caisses.** Commune, militaire, industrielle et spatiale. Une clé du même type ouvre une caisse. `/crate preview
  <type>` montre le contenu et les chances. Une rare manquée 20 fois de suite est garantie à la 21ᵉ ouverture
  (`/crate pity`).
- **Récompenses personnelles.** `/daily` donne une récompense toutes les 24 h : de 100 cr au premier jour à 500 cr au
  septième, puis 500 cr ensuite. Le **terminal bancaire** (`/terminal`) encaisse l'épargne du temps de jeu actif.
- **Avant-postes.** Des marchands PNJ tiennent des avant-postes (`/trade`, à 8 blocs du comptoir au plus). Leur étal
  change toutes les 24 000 ticks.

## 8. Espace (J5)

- Trois planètes : la **Lune**, **Mars** et la **planète glacée**. On ne s'y rend qu'en fusée.
- **Fusée.** Posez-la sur le territoire de votre nation : elle lui appartient. Clic droit : soute de 9 cases. Clic droit
  accroupi, main vide : fiche de vol, avec les recherches requises et le coût de chaque destination.
- **Vol.** Clic droit accroupi, puis un bouton de destination (ou `/rocket launch <destination>`). Compte à rebours de
  10 s, annulé si le pilote subit des dégâts ou s'éloigne de plus de 6 blocs. Les membres de la nation peuvent
  embarquer (4 personnes au plus). Un délai de 60 s sépare deux vols d'un même joueur.
- **Carburant.** La **Raffinerie** transforme 3 bouteilles de gaz et 1 poudre de blaze en carburant de fusée, avec de
  l'énergie. Les vols en consomment.
- **Oxygène et froid.** Hors d'une zone oxygénée, une bouteille d'oxygène dure 120 s ; sans bouteille, vous perdez des
  PV. Sur la planète glacée, il faut aussi de la chaleur (chaudière, réchauffeur ou plastron chauffant). `/oxygen`
  affiche votre état.
- **Module cryogénique.** Il ouvre la planète glacée. Il ne se fabrique pas : il vient d'un contrat de mission.
- Les lits et les ancres de réapparition ne fonctionnent pas sur une planète.

## 9. Armes et donjon (J6)

- **Recherche.** Le domaine Militaire débloque l'onglet **Armement** de la Station d'ingénierie, puis chaque arme et
  chaque munition (voir `docs/recherche.md`). Sans nation, vous ne pouvez pas tirer.
- **Armes.** Le clic droit tire : un rayon part de vos yeux et s'arrête au premier bloc. Une munition par tir. Le
  pistolet, le fusil et le fusil de précision portent à 24, 48 et 96 blocs. L'explosif se lance au clic droit.
- **Munitions.** Basiques (de base), perforantes (plus de dégâts), au soufre (plus de dégâts, et la cible prend feu).
- **Armures.** Le gilet balistique léger et le plastron balistique lourd se portent au torse. Le plastron protège
  davantage. Ils se fabriquent à la Station.
- **Trousse de soins.** Maintenez le clic droit pendant 1 s : vous récupérez 6 PV. Elle n'a pas de recette : on la
  trouve dans la caisse militaire et dans le butin du gardien.
- **Donjon.** Une arène de pierre se trouve à la surface, dans l'Overworld (plaines, déserts, taïgas, savanes). Au
  centre, un **autel de donjon**. Fabriquez une **clé de donjon** (4 lingots de fer et 2 poudres à canon, onglet
  Armement), puis faites un clic droit sur l'autel : la clé est consommée et le gardien apparaît.
- **Gardien.** 200 PV ; il ne brûle pas au soleil. À sa mort, la nation de l'autel reçoit une **clé militaire** dans son
  coffre national, et du butin tombe au sol. Tant qu'il vit, l'autel refuse une autre clé.
- **Recharge.** Après la mort du gardien, l'autel se recharge pendant 6 heures (réglage de l'opérateur).
- **Caisse militaire.** Elle s'ouvre avec une clé militaire : munitions, trousse, gilet balistique léger, plastron
  balistique lourd.
- **Pas encore.** Les armes à feu ne touchent que les monstres : les joueurs se combattent en guerre, au corps à corps (section 10). Le fusil de précision ne se fabrique
  pas : il ne vient que du gardien.

## 10. Diplomatie et guerre (J7)

- **Déclarer.** Le Chef tape `/nation war declare <nation>`. Chaque nation doit compter au moins 3 membres, et les deux
  nations ne doivent pas déjà être en guerre ou en préparation. La déclaration ouvre une **préparation de 24 h** ; les
  deux nations sont prévenues.
- **Annuler.** Pendant la préparation seulement, le Chef de l'attaquant tape `/nation war cancel`. Aucune guerre n'a
  commencé : pas de trêve, rien n'est gagné ni perdu.
- **Guerre.** Au bout de la préparation, la guerre dure 168 h au plus. Les joueurs des deux nations peuvent se frapper au
  corps à corps. Deux membres d'une même nation ne se frappent pas. Un joueur sans nation ne peut être frappé ni frapper.
  `/nation war status` affiche l'état, le score et les échéances.
- **Score.** Chaque joueur ennemi tué pendant la guerre (au corps à corps ou par missile) rapporte 1 point. À 30 points,
  la nation gagne : une **clé militaire** tombe dans son coffre national. À la fin du délai, le plus grand score gagne ;
  à égalité, personne. Les scores repartent de zéro.
- **Trêve.** Après toute fin de guerre, 48 h de trêve : ni combat, ni nouvelle déclaration contre le même adversaire.
- **Traités.** Pendant une guerre, le Chef ou le Ministre propose la paix avec `/nation treaty propose <nation>`. Il faut
  avoir débloqué les ambassades (recherche « Corps diplomatique »). La proposition vaut 24 h. Le destinataire répond par
  `/nation treaty accept` ou `/nation treaty refuse` ; s'il reçoit plusieurs propositions, il précise la nation. Un
  traité accepté termine la guerre, suivi d'une trêve. Aucun argent ni territoire n'est échangé.

## 11. Silo et missiles (J7)

- **Débloquer.** Recherche militaire : « Silo à missiles » (palier 13), « Guidage de missiles » (14), « Ogives lourdes »
  (16). Les recettes sont à l'onglet Armement de la Station d'ingénierie : silo (8 blindages nationaux, 4 blocs de
  redstone, 2 obsidiennes), missile guidé (4 TNT, 2 poudres à canon, 1 redstone), ogive lourde (8 TNT, 2 lingots de
  netherite).
- **Poser.** Un silo par nation, sur votre territoire, dans l'Overworld. Un second silo est refusé.
- **Charger.** Clic droit sur le silo avec un missile guidé (jusqu'à 4) ou une ogive lourde (jusqu'à 2). Il faut la
  permission « Tirer un missile » (Chef par défaut).
- **Tirer.** Placez-vous à 6 blocs du silo au plus, regardez un chunk revendiqué par une nation **en guerre** avec la
  vôtre (200 blocs au plus), puis `/nation missile fire`. La capitale ne peut pas être visée. Le missile tombe 30 s
  après le tir. Chaque tir consomme un missile et une ogive ; le silo se recharge pendant 6 h.
- **Effet.** Dans un rayon de 4 blocs autour du centre du chunk visé, les blocs du territoire ennemi sont détruits, sauf
  le Trésor et le coffre national. Les joueurs ennemis proches (rayon de 6 blocs) prennent 12 de dégâts ; ceux qui
  meurent rapportent un point de score. Si la guerre se termine pendant le vol, l'impact n'a aucun effet.

## 12. Commandes de référence

| Commande | Effet |
|---|---|
| `/nation create\|invite\|join\|kick\|leave\|transfer\|disband\|info\|list\|log\|role\|perm\|perms\|gui` | Gestion de la nation. |
| `/claim` · `/unclaim` · `/map` | Revendiquer ou abandonner le chunk courant ; carte texte. |
| `/balance [joueur]` · `/pay <joueur> <montant>` · `/baltop` | Économie personnelle. |
| `/research …` | Voir la section 5. |
| `/nation war declare` · `cancel` · `status` | Déclarer, annuler ou suivre une guerre (section 10). |
| `/nation treaty propose` · `accept` · `refuse` | Proposer ou répondre à un traité de paix (section 10). |
| `/nation missile fire` | Tirer un missile depuis le silo (section 11). |

## 13. Opérateur de serveur

- Le mod doit être installé sur le **serveur et sur chaque client**.
- La configuration se trouve dans `world/serverconfig/sovereignfrontier-server.toml`. Elle est
  rechargée à chaud. Les clés principales : `startingBalance`, `claimBaseCost`, `maxMembers`,
  `incomeIntervalTicks`, `treasuryReach`. La section `[energy]` règle les machines et le réacteur, et la section `[war]` règle la guerre, les traités et les
  missiles (section 10 et 11). Pour un essai rapide, `preparationHours` descend à 1 (minimum) et `killsToWin` à 5.
- Pour tester rapidement, en opérateur :
  - `/sfadmin money give <joueur> <montant>` : crédite un joueur.
  - `/sfadmin treasury <nation> <montant>` : ajoute à la trésorerie.
  - `/sfadmin research <nation> complete <palier>` : termine un palier (`Tab` complète les noms).
  - `/sfadmin bypass` : contourne la protection du territoire. Le mode s'éteint à la déconnexion.
  - `/sfadmin war end <nation> <nation>` : termine une guerre en cours entre deux nations, sans score.
- Les données sont dans le dossier du monde. Sauvegardez-le avant une mise à jour.

## 14. Questions fréquentes

- **« Recette verrouillée : recherche … requise »** : la nation n'a pas encore terminé le palier
  nommé dans le message. Consultez `/research info <palier>`.
- **« Il manque N × … dans votre inventaire »** : ajoutez les ingrédients, puis refabriquez.
- **« Ouvrez une Station d'ingénierie… »** : revenez à moins de 8 blocs de la station.
- **« Ce territoire porte le Trésor ou le coffre national »** : l'abandon est refusé tant que le
  bloc est là. Retirez-le, puis réessayez.
- **Un joueur ne peut pas poser de bloc chez un voisin** : c'est la protection du territoire. Seuls
  les membres de la nation propriétaire, selon leurs permissions, peuvent agir sur ce chunk. Pour construire, rejoignez la
  nation ou revendiquez une parcelle libre.

- **« Une trêve est en vigueur avec … »** : la guerre vient de se terminer. Aucune déclaration n'est possible avant la fin de
  la trêve (`/nation war status` indique l'échéance).
- **« Votre rang ne peut pas tirer de missile. »** : seul un rang doté de la permission « Tirer un missile » charge ou
  tire (Chef par défaut).
- **« Cible hors guerre … »** : un missile ne vise que le territoire d'une nation avec laquelle vous êtes en guerre.

## 15. Limites connues

- Les défenses anti-PNJ, les nations PNJ, les armes à feu contre les joueurs, la prise de territoire après guerre et les
  donjons planétaires ne sont pas livrés au J7.
- Un Trésor posé **avant** cette version n'est pas enregistré comme cœur : sa parcelle peut
  encore être abandonnée.
- Un crash survenant entre deux écritures peut perdre un transfert d'argent entre un portefeuille
  et la trésorerie. Ce point n'est pas encore corrigé.
