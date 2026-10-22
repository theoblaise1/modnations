# Propriété : WP2
"""Contrôle des tables de caisse (data/sovereignfrontier/crates/<type>.json), miroir de reward/CrateTable.java.

  * un fichier par type de ``CrateKind`` (nom du fichier = identifiant), les quatre présents ;
  * ``rolls`` entier de 1 à 6, ``entries`` : 32 entrées au plus, champs connus seulement ;
  * chaque entrée contient exactement un champ parmi ``item`` (avec ``count`` et ``components`` facultatifs),
    ``money`` et ``placeholder: {milestone, label}`` ; ``count`` et ``money`` valent ``n`` ou ``[min, max]`` ;
  * ``weight`` entier de 1 à 1 000 000, ``rarity`` parmi commun / peu_commun / rare ;
  * entrée rare : ``id`` obligatoire, jamais provisoire ; identifiants uniques ; 1 à 3 rares par table ;
  * au moins une entrée ni rare ni provisoire, et au moins ``rolls`` entrées tirables ;
  * objets connus, jamais dans ``#sovereignfrontier:interdit_caisses`` (paliers 3/4, fusée finale, arme maximale,
    fin de jeu vanilla, Créatif, machines soumises à recherche).
"""

from __future__ import annotations

import re

from .common import (
    CRATE_MAX_ENTRIES, CRATE_RARES, CRATE_ROLLS, NAMESPACE, NS_DATA_REL, Context, is_int, show,
)

ENTRY_FIELDS = {"id", "item", "count", "components", "money", "placeholder", "weight", "rarity"}
RARITIES = ("commun", "peu_commun", "rare")
MAX_WEIGHT = 1_000_000
MAX_COUNT = 256
MAX_MONEY = 1_000_000
ID_RE = re.compile(r"^[a-z0-9_]{1,32}$")
LABEL_RE = re.compile(r"^[a-z0-9_]{1,48}$")
MILESTONE_RE = re.compile(r"^J[0-9]{1,2}$")


def _range(ctx: Context, value: object, lo: int, hi: int, subject: str, where: str) -> bool:
    """``n`` ou ``[min, max]`` (entiers, min ≤ max) dans [lo, hi]."""
    if is_int(value):
        bounds = [value, value]
    elif isinstance(value, list) and len(value) == 2 and all(is_int(v) for v in value):
        bounds = value
    else:
        ctx.error(subject, f"{where} : n ou [min, max] entiers attendu, reçu {show(value)}")
        return False
    if bounds[0] > bounds[1]:
        ctx.error(subject, f"{where} : plage {show(value)} avec min > max")
        return False
    if bounds[0] < lo or bounds[1] > hi:
        ctx.error(subject, f"{where} : plage {show(value)} hors de [{lo}, {hi}]")
        return False
    return True


def _check_entry(ctx: Context, entry: object, subject: str, where: str) -> tuple[str, bool, bool] | None:
    """Vérifie une entrée ; renvoie (id, rare, provisoire) ou None si elle est invalide."""
    if not isinstance(entry, dict):
        ctx.error(subject, f"{where} : objet attendu, reçu {show(entry)}")
        return None
    ok = True
    unknown = sorted(set(entry) - ENTRY_FIELDS)
    if unknown:
        ctx.error(subject, f"{where} : champ(s) inconnu(s) : {', '.join(unknown)}")
        ok = False
    contents = [k for k in ("item", "money", "placeholder") if k in entry]
    if len(contents) != 1:
        ctx.error(subject, f"{where} : exactement un champ parmi « item », « money » et « placeholder » attendu"
                  f" ({', '.join(contents) or 'aucun'})")
        return None
    kind = contents[0]
    if kind != "item" and ("count" in entry or "components" in entry):
        ctx.error(subject, f"{where} : « count » et « components » n'existent que pour une entrée « item »")
        ok = False

    rarity = entry.get("rarity", "commun")
    if rarity not in RARITIES:
        ctx.error(subject, f"{where} : rareté {show(rarity)} inconnue ({', '.join(RARITIES)})")
        ok = False
    weight = entry.get("weight", 1)
    if not is_int(weight) or not 1 <= weight <= MAX_WEIGHT:
        ctx.error(subject, f"{where} : « weight » entier de 1 à {MAX_WEIGHT} attendu, reçu {show(weight)}")
        ok = False
    entry_id = entry.get("id")
    if entry_id is not None and (not isinstance(entry_id, str) or not ID_RE.match(entry_id)):
        ctx.error(subject, f"{where} : « id » {show(entry_id)} invalide ([a-z0-9_], 32 caractères au plus)")
        ok = False
    rare = rarity == "rare"
    if rare and entry_id is None:
        ctx.error(subject, f"{where} : une entrée rare doit avoir un « id » (compteur de pitié)")
        ok = False

    if kind == "item":
        item = entry["item"]
        if ctx.check_item_id(item, subject, f"{where}.item"):
            if item == "minecraft:air":
                ctx.error(subject, f"{where} : objet « minecraft:air » interdit")
                ok = False
            elif ctx.banned_from_loot(item):
                ctx.error(subject, f"{where} : objet interdit « {item} » (#{NAMESPACE}:interdit_caisses) : "
                          "jamais dans une caisse")
                ok = False
        else:
            ok = False
        if "count" in entry and not _range(ctx, entry["count"], 1, MAX_COUNT, subject, f"{where}.count"):
            ok = False
        if "components" in entry and not isinstance(entry["components"], dict):
            ctx.error(subject, f"{where} : « components » doit être un objet (DataComponentPatch)")
            ok = False
    elif kind == "money":
        if not _range(ctx, entry["money"], 1, MAX_MONEY, subject, f"{where}.money"):
            ok = False
    else:
        ph = entry["placeholder"]
        if not isinstance(ph, dict) or set(ph) != {"milestone", "label"}:
            ctx.error(subject, f"{where} : « placeholder » {{milestone, label}} attendu, reçu {show(ph)}")
            ok = False
        else:
            if not isinstance(ph["milestone"], str) or not MILESTONE_RE.match(ph["milestone"]):
                ctx.error(subject, f"{where} : jalon {show(ph['milestone'])} invalide (J1 à J99)")
                ok = False
            if not isinstance(ph["label"], str) or not LABEL_RE.match(ph["label"]):
                ctx.error(subject, f"{where} : libellé {show(ph['label'])} invalide ([a-z0-9_], 48 caractères au plus)")
                ok = False
        if rare:
            ctx.error(subject, f"{where} : une entrée provisoire ne peut pas être rare (elle n'est jamais tirée)")
            ok = False
    return (entry_id or "", rare, kind == "placeholder") if ok else None


def _check_table(ctx: Context, data: object, subject: str) -> None:
    if not isinstance(data, dict):
        ctx.error(subject, "objet {\"rolls\", \"entries\"} attendu")
        return
    unknown = sorted(set(data) - {"rolls", "entries"})
    if unknown:
        ctx.error(subject, f"champ(s) inconnu(s) : {', '.join(unknown)}")
    rolls = data.get("rolls")
    lo, hi = CRATE_ROLLS
    rolls_ok = is_int(rolls) and lo <= rolls <= hi
    if not rolls_ok:
        ctx.error(subject, f"« rolls » entier de {lo} à {hi} attendu, reçu {show(rolls)}")
    entries = data.get("entries")
    if not isinstance(entries, list) or not entries:
        ctx.error(subject, "« entries » : liste non vide attendue")
        return
    if len(entries) > CRATE_MAX_ENTRIES:
        ctx.error(subject, f"{len(entries)} entrées (au plus {CRATE_MAX_ENTRIES})")
    seen: set[str] = set()
    rares = plain = eligible = 0
    valid = True
    for i, entry in enumerate(entries):
        result = _check_entry(ctx, entry, subject, f"entries[{i}]")
        if result is None:
            valid = False
            continue
        entry_id, rare, placeholder = result
        if entry_id:
            if entry_id in seen:
                ctx.error(subject, f"entries[{i}] : identifiant « {entry_id} » en double")
            seen.add(entry_id)
        rares += rare
        if not placeholder:
            eligible += 1
            plain += not rare
    if not valid:
        return
    rlo, rhi = CRATE_RARES
    if not rlo <= rares <= rhi:
        ctx.error(subject, f"{rares} entrée(s) rare(s) (de {rlo} à {rhi} attendues)")
    if plain == 0:
        ctx.error(subject, "aucune entrée ni rare ni provisoire")
    if rolls_ok and rolls > eligible:
        ctx.error(subject, f"{rolls} tirages pour {eligible} entrée(s) tirable(s)")


def check(ctx: Context) -> list[tuple[str, str]]:
    """Les erreurs sont écrites dans ``ctx.errors`` (comme les aides communes) : la liste renvoyée est vide."""
    folder = ctx.root / NS_DATA_REL / "crates"
    if not folder.is_dir():
        # Dépôt sans contenu de caisses (auto-test du pilote) : rien à vérifier, mais le jeu n'aurait aucune caisse.
        ctx.warn(f"data/{NAMESPACE}/crates/", "dossier absent : toutes les caisses seraient indisponibles")
        return []
    found: set[str] = set()
    for path in ctx.data_files("crates"):
        subject = ctx.rel(path)
        rel = path.relative_to(folder).with_suffix("").as_posix()
        if ctx.crate_kinds and rel not in ctx.crate_kinds:
            ctx.error(subject, f"nom de fichier « {rel} » : identifiant de type de caisse attendu "
                      f"({', '.join(ctx.crate_kinds)})")
            continue
        data = ctx.load(path)
        if data is None:
            continue
        found.add(rel)
        _check_table(ctx, data, subject)
    for kind in ctx.crate_kinds:
        if kind not in found:
            ctx.error(f"data/{NAMESPACE}/crates/", f"table manquante : {kind}.json (caisse {kind} indisponible)")
    return []
