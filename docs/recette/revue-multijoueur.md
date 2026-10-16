# Recette en jeu — correctifs de la revue multijoueur (J1–J2)

Prérequis : serveur dédié NeoForge 21.1.219 avec le `.jar` du commit `ea2d8d0` ou plus récent, et
**deux clients** (joueurs « Alice » et « Bob ») avec le même `.jar`. Une nation créée par Alice, Bob
membre. Pour accélérer : `incomeIntervalTicks = 200` dans la config serveur (rechargée à chaud).

Coche chaque ligne ; note le message exact si un résultat diffère.

## A. Noms de comptes et de membres
- [ ] A1. Alice et Bob ont chacun un compte. `/balance alice` (en minuscules, pseudo d'Alice tapé en majuscules différemment)
  → si un seul compte porte ce nom, il s'affiche ; s'il y en a plusieurs, message « Plusieurs comptes portent le nom… ».
- [ ] A2. `/pay <joueur> 10` : le payeur voit son solde se mettre à jour dans l'écran national sans le rouvrir.
- [ ] A3. Dans une nation contenant deux membres dont les noms ne diffèrent que par la casse : `/nation role <nom>`
  → refus « Plusieurs membres portent le nom… » (pas d'action sur le mauvais membre).

## B. Limites et rafraîchissements
- [ ] B1. Cliquer très vite plusieurs fois sur « Déposer » (Trésor) : le solde ne change que pour un nombre limité de clics
  par seconde ; aucun blocage du jeu ni message en rafale.
- [ ] B2. Après une action (dépôt, rôle, revendication), les autres membres connectés voient la mise à jour dans l'écran
  national en moins d'une seconde.
- [ ] B3. Un officier voit l'invitation dans son journal (`/nation log`) sans rouvrir l'écran.

## C. Territoire et carte
- [ ] C1. Alice revendique un chunk voisin de la nation de Bob : sur la carte de Bob (onglet Territoire), le chunk
  apparaît en couleur de la nation d'Alice en moins d'une seconde.
- [ ] C2. Alice abandonne le chunk du Trésor : refus « Ce territoire porte le Trésor ou le coffre national… ».
  Après avoir retiré le Trésor, l'abandon est accepté.
- [ ] C3. Une revendication à grande distance (`/claim` depuis une position normale) reste refusée comme avant.

## D. Protections du territoire (joueur sans droit sur la nation voisine)
- [ ] D1. Briquet ou boule de feu utilisé sur un bloc en bordure de frontière, face tournée vers le territoire voisin :
  pas de feu ni d'effet chez le voisin ; refus avec le message de protection.
- [ ] D2. Cadre ou tableau posé sur une face en bordure, en visant le territoire voisin : refus.
- [ ] D3. Bateau ou œuf d'apparition posé sur l'eau visée depuis la rive du voisin : refus.
- [ ] D4. Plaque de pression en territoire voisin : un joueur sans droit ne l'active pas (la porte ou le piston commandé
  ne bouge pas) ; un membre de la nation, si. Œuf de tortue : un non-membre ne le piétine pas.
- [ ] D5. Épée à pleine charge sur un zombie posé à côté d'une vache ou d'un villageois en territoire voisin :
  la vache et le villageois ne sont pas touchés ; le zombie, lui, peut l'être.

## E. Administration et déconnexion
- [ ] E1. Opérateur active `/sfadmin bypass`, se déconnecte, se reconnecte : le bypass est désactivé
  (ré-activer si besoin). Aucun message d'erreur.
- [ ] E2. En entrant puis en sortant d'un territoire, le message d'entrée/sortie s'affiche à chaque changement, y compris
  après un passage de portail vers une dimension où le chunk a les mêmes coordonnées.

## F. Recherche
- [ ] F1. Alice lance une recherche. Bob ouvre la confirmation d'annulation, Alice annule et relance le même palier.
  Bob valide sa confirmation restée ouverte → refus « … n'est plus la recherche en cours… ». La recherche d'Alice n'est pas annulée.
- [ ] F2. Une recherche arrive à échéance pendant que Bob clique sur « Annuler » → refus
  « Cette recherche vient de se terminer… » (pas de remboursement à moitié, objets non perdus).
- [ ] F3. Annulation réussie : tous les membres reçoivent un message dans le chat (nom du membre, palier, remboursement).
- [ ] F4. `/research` pendant une recherche en cours affiche un pourcentage entre 0 et 100.

## G. Connexion et réseau
- [ ] G1. Le serveur démarre sans erreur ; les deux clients se connectent avec le même numéro de protocole
  (un client de version antérieure à `ea2d8d0` est refusé avec un message de version).
- [ ] G2. Aucun blocage ni `lag spike` visible en rafale d'actions pour le joueur qui envoie les actions.
