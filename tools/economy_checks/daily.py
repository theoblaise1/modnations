# Propriété : WP2
"""Contrôle de la table /daily (data/sovereignfrontier/daily/streak.json), miroir de reward/DailyTable.java.

  * seul ``streak.json`` est lu (tout autre fichier du dossier : avertissement, il serait ignoré) ;
  * ``days`` : 1 à 31 jours (le dernier se répète) ;
  * chaque jour : ``money`` entier de 0 à 1 000 000 et ``keys`` de 1 à 2 choix ``{"one_of": [types]}`` ;
  * ``one_of`` : liste non vide, sans doublon, de types de ``CrateKind`` dont la clé n'est pas interdite dans les
    sources automatiques (``#sovereignfrontier:interdit_caisses``).
"""

from __future__ import annotations

from .common import DAILY_KEYS, NAMESPACE, NS_DATA_REL, Context, is_int, show

FILE = "streak"
MAX_DAYS = 31
MAX_MONEY = 1_000_000


def _check_day(ctx: Context, day: object, subject: str, where: str) -> None:
    if not isinstance(day, dict):
        ctx.error(subject, f"{where} : objet {{money, keys}} attendu, reçu {show(day)}")
        return
    unknown = sorted(set(day) - {"money", "keys"})
    if unknown:
        ctx.error(subject, f"{where} : champ(s) inconnu(s) : {', '.join(unknown)}")
    money = day.get("money")
    if not is_int(money) or not 0 <= money <= MAX_MONEY:
        ctx.error(subject, f"{where}.money : entier de 0 à {MAX_MONEY} attendu, reçu {show(money)}")
    keys = day.get("keys")
    lo, hi = DAILY_KEYS
    if not isinstance(keys, list) or not lo <= len(keys) <= hi:
        ctx.error(subject, f"{where}.keys : liste de {lo} à {hi} clé(s) attendue, reçu {show(keys)}")
        return
    for j, choice in enumerate(keys):
        here = f"{where}.keys[{j}]"
        if not isinstance(choice, dict) or set(choice) != {"one_of"}:
            ctx.error(subject, f"{here} : objet {{\"one_of\": [types]}} attendu, reçu {show(choice)}")
            continue
        kinds = choice["one_of"]
        if not isinstance(kinds, list) or not kinds:
            ctx.error(subject, f"{here}.one_of : liste non vide de types de caisse attendue")
            continue
        if len(set(map(show, kinds))) != len(kinds):
            ctx.error(subject, f"{here}.one_of : type en double {show(kinds)}")
        for kind in kinds:
            if not isinstance(kind, str) or (ctx.crate_kinds and kind not in ctx.crate_kinds):
                ctx.error(subject, f"{here}.one_of : type de caisse inconnu {show(kind)} "
                          f"({', '.join(ctx.crate_kinds)})")
            elif ctx.banned_from_loot(f"{NAMESPACE}:cle_{kind}"):
                ctx.error(subject, f"{here}.one_of : la clé « {NAMESPACE}:cle_{kind} » est interdite dans les "
                          f"sources automatiques (#{NAMESPACE}:interdit_caisses)")


def check(ctx: Context) -> list[tuple[str, str]]:
    """Les erreurs sont écrites dans ``ctx.errors`` : la liste renvoyée est vide."""
    folder = ctx.root / NS_DATA_REL / "daily"
    if not folder.is_dir():
        # Dépôt sans table /daily (auto-test du pilote) : rien à vérifier, mais /daily serait indisponible.
        ctx.warn(f"data/{NAMESPACE}/daily/", "dossier absent : /daily serait indisponible")
        return []
    found = False
    for path in ctx.data_files("daily"):
        subject = ctx.rel(path)
        if path.relative_to(folder).with_suffix("").as_posix() != FILE:
            ctx.warn(subject, f"fichier ignoré par le jeu : seul {NAMESPACE}:{FILE} est lu")
            continue
        data = ctx.load(path)
        if data is None:
            continue
        found = True
        if not isinstance(data, dict) or set(data) != {"days"}:
            ctx.error(subject, "objet {\"days\": [...]} attendu (aucun autre champ)")
            continue
        days = data["days"]
        if not isinstance(days, list) or not 1 <= len(days) <= MAX_DAYS:
            ctx.error(subject, f"« days » : liste de 1 à {MAX_DAYS} jours attendue")
            continue
        for i, day in enumerate(days):
            _check_day(ctx, day, subject, f"days[{i}]")
    if not found:
        ctx.error(f"data/{NAMESPACE}/daily/", f"table manquante : {FILE}.json (/daily indisponible)")
    return []
