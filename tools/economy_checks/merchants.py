# Propriété : WP1
"""Contrôle des pools marchands (``data/<ns>/merchants/*.json``) et du butin des avant-postes.

Règles (docs/conception/J4.md §1.5, §1.11, §2.3, §3, §6), mêmes constantes que le code Java :
  * format strict : ``variant`` (a-z, 0-9, _), ``rolls`` ≥ 0, ``offers`` (1 à 24) ; chaque offre a ``item`` et
    ``count`` (1..99), au moins ``price`` ou ``payout`` (1..1 000 000), ``stock`` et ``demand`` (1..10 000),
    ``weight`` (1..10 000), ``guaranteed`` (booléen) ; aucun champ inconnu ;
  * objets uniques par pool ; ``rolls`` ≤ offres non garanties ; au moins une offre à l'étal ;
  * aucun objet de ``#sovereignfrontier:interdit_caisses`` (ventes et rachats) ;
  * écart global : pour chaque objet, sur tous les pools, rachat/u maximal ≤ 0,8 × prix/u minimal ;
  * arbitrage par l'artisanat (paires connues lingot ↔ bloc, pépite ↔ lingot, rondin → planches…) : un dérivé
    racheté ≤ 0,8 × le coût de ses ingrédients vendus, dans les deux sens pour les paires réversibles ;
  * butin des tonneaux d'avant-poste (``loot_table/chests/avant_poste_*.json``) : objets connus, aucun objet de
    ``#interdit_caisses``.
L'absence de pool n'est qu'un avertissement (le dépôt de l'auto-test du socle n'en contient pas).
"""

from __future__ import annotations

import re
from pathlib import Path

from .common import MAX_PAYOUT_RATIO, POOL_MAX_OFFERS, Context, is_int, show

MAX_BASE_VALUE = 1_000_000
MAX_QUANTITY = 10_000
MAX_COUNT = 99
OFFER_FIELDS = {"item", "count", "price", "payout", "stock", "demand", "weight", "guaranteed"}
POOL_FIELDS = {"variant", "rolls", "offers"}
VARIANT_RE = re.compile(r"^[a-z0-9_]{1,32}$")
EXPECTED_POOLS = ("general", "prospecteur", "trappeur")

# Recettes réversibles (un bloc ↔ n objets) et décompositions à sens unique (ingrédient → n dérivés).
REVERSIBLE: list[tuple[str, str, int]] = [
    ("minecraft:iron_block", "minecraft:iron_ingot", 9),
    ("minecraft:gold_block", "minecraft:gold_ingot", 9),
    ("minecraft:copper_block", "minecraft:copper_ingot", 9),
    ("minecraft:diamond_block", "minecraft:diamond", 9),
    ("minecraft:emerald_block", "minecraft:emerald", 9),
    ("minecraft:coal_block", "minecraft:coal", 9),
    ("minecraft:redstone_block", "minecraft:redstone", 9),
    ("minecraft:lapis_block", "minecraft:lapis_lazuli", 9),
    ("minecraft:raw_iron_block", "minecraft:raw_iron", 9),
    ("minecraft:raw_copper_block", "minecraft:raw_copper", 9),
    ("minecraft:raw_gold_block", "minecraft:raw_gold", 9),
    ("minecraft:hay_block", "minecraft:wheat", 9),
    ("minecraft:bone_block", "minecraft:bone_meal", 9),
    ("minecraft:iron_ingot", "minecraft:iron_nugget", 9),
    ("minecraft:gold_ingot", "minecraft:gold_nugget", 9),
]
ONE_WAY: list[tuple[str, str, int]] = [
    (f"minecraft:{wood}_log", f"minecraft:{wood}_planks", 4)
    for wood in ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry")
] + [("minecraft:bone", "minecraft:bone_meal", 3)]


def _check_offer(ctx: Context, subject: str, i: int, offer: object) -> dict | None:
    where = f"offers[{i}]"
    if not isinstance(offer, dict):
        ctx.error(subject, f"{where} : objet attendu, reçu {show(offer)}")
        return None
    unknown = sorted(set(offer) - OFFER_FIELDS)
    if unknown:
        ctx.error(subject, f"{where} : champ(s) inconnu(s) : {', '.join(unknown)}")
    ok = True
    item = offer.get("item")
    if not ctx.check_item_id(item, subject, f"{where}.item"):
        ok = False
    elif item == "minecraft:air":
        ctx.error(subject, f"{where}.item : objet vide")
        ok = False
    count = offer.get("count")
    if not is_int(count) or not 1 <= count <= MAX_COUNT:
        ctx.error(subject, f"{where}.count : entier de 1 à {MAX_COUNT} attendu, reçu {show(count)}")
        ok = False
    for key in ("price", "payout"):
        if key in offer and (not is_int(offer[key]) or not 1 <= offer[key] <= MAX_BASE_VALUE):
            ctx.error(subject, f"{where}.{key} : entier de 1 à {MAX_BASE_VALUE} attendu, reçu {show(offer[key])}")
            ok = False
    if "price" not in offer and "payout" not in offer:
        ctx.error(subject, f"{where} : ni « price » ni « payout » ({show(item)})")
        ok = False
    for key in ("stock", "demand", "weight"):
        if key in offer and (not is_int(offer[key]) or not 1 <= offer[key] <= MAX_QUANTITY):
            ctx.error(subject, f"{where}.{key} : entier de 1 à {MAX_QUANTITY} attendu, reçu {show(offer[key])}")
            ok = False
    if "guaranteed" in offer and not isinstance(offer["guaranteed"], bool):
        ctx.error(subject, f"{where}.guaranteed : booléen attendu")
        ok = False
    if ok and ctx.banned_from_loot(item):
        ctx.error(subject, f"{where} : « {item} » appartient à #sovereignfrontier:interdit_caisses")
    return offer if ok else None


def _check_pool(ctx: Context, path: Path) -> list[dict]:
    """Offres valides d'un pool (liste vide si le pool est rejeté)."""
    subject = ctx.rel(path)
    data = ctx.load(path)
    if data is None:
        return []
    if not isinstance(data, dict):
        ctx.error(subject, "objet {\"variant\", \"rolls\", \"offers\"} attendu")
        return []
    unknown = sorted(set(data) - POOL_FIELDS)
    if unknown:
        ctx.error(subject, f"champ(s) inconnu(s) : {', '.join(unknown)}")
    variant = data.get("variant")
    if not isinstance(variant, str) or not VARIANT_RE.match(variant):
        ctx.error(subject, f"« variant » invalide (a-z, 0-9, _) : {show(variant)}")
    rolls = data.get("rolls")
    if not is_int(rolls) or rolls < 0:
        ctx.error(subject, f"« rolls » : entier ≥ 0 attendu, reçu {show(rolls)}")
        rolls = 0
    offers = data.get("offers")
    if not isinstance(offers, list) or not 1 <= len(offers) <= POOL_MAX_OFFERS:
        ctx.error(subject, f"« offers » : liste de 1 à {POOL_MAX_OFFERS} offres attendue")
        return []
    checked = [_check_offer(ctx, subject, i, raw) for i, raw in enumerate(offers)]
    valid = [o for o in checked if o is not None]
    seen: set[str] = set()
    for o in valid:
        if o["item"] in seen:
            ctx.error(subject, f"objet en double : « {o['item']} »")
        seen.add(o["item"])
    guaranteed = sum(1 for o in valid if o.get("guaranteed", False))
    optional = len(valid) - guaranteed
    if rolls > optional:
        ctx.error(subject, f"« rolls » ({rolls}) dépasse le nombre d'offres non garanties ({optional})")
    if guaranteed + rolls < 1:
        ctx.error(subject, "étal toujours vide : aucune offre garantie et « rolls » nul")
    if len(valid) != len(offers):
        return []
    return [dict(o, _pool=subject) for o in valid]


def _unit(offer: dict, key: str) -> float:
    return offer[key] / offer["count"]


def _check_spread(ctx: Context, offers: list[dict]) -> None:
    cheapest: dict[str, dict] = {}
    for o in offers:
        if "price" in o and (o["item"] not in cheapest or _unit(o, "price") < _unit(cheapest[o["item"]], "price")):
            cheapest[o["item"]] = o
    for o in offers:
        low = cheapest.get(o["item"]) if "payout" in o else None
        if low is not None and o["payout"] * low["count"] > MAX_PAYOUT_RATIO * low["price"] * o["count"] + 1e-9:
            ctx.error(o["_pool"], f"écart global : rachat de « {o['item']} » à {_unit(o, 'payout'):.3f} cr/u, au-delà de "
                                  f"{MAX_PAYOUT_RATIO} × le prix minimal {_unit(low, 'price'):.3f} cr/u ({low['_pool']})")


def _check_crafting(ctx: Context, offers: list[dict]) -> None:
    """Acheter des ingrédients au marchand, fabriquer le dérivé et le lui revendre ne doit jamais rapporter."""
    min_price: dict[str, tuple[float, str]] = {}
    max_payout: dict[str, tuple[float, str]] = {}
    for o in offers:
        if "price" in o and (o["item"] not in min_price or _unit(o, "price") < min_price[o["item"]][0]):
            min_price[o["item"]] = (_unit(o, "price"), o["_pool"])
        if "payout" in o and (o["item"] not in max_payout or _unit(o, "payout") > max_payout[o["item"]][0]):
            max_payout[o["item"]] = (_unit(o, "payout"), o["_pool"])
    # (ingrédient acheté, n ingrédients par dérivé, dérivé revendu) : dérivé/u ≤ 0,8 × n × ingrédient/u, et inversement.
    routes: list[tuple[str, float, str]] = []
    for block, part, n in REVERSIBLE:
        routes.append((part, n, block))           # n objets → 1 bloc
        routes.append((block, 1.0 / n, part))     # 1 bloc → n objets
    for source, derived, n in ONE_WAY:
        routes.append((source, 1.0 / n, derived))  # 1 source → n dérivés
    for ingredient, per_unit, derived in routes:
        if ingredient in min_price and derived in max_payout:
            cost, cost_pool = min_price[ingredient]
            gain, gain_pool = max_payout[derived]
            if gain > MAX_PAYOUT_RATIO * per_unit * cost + 1e-9:
                ctx.error(gain_pool, f"arbitrage par l'artisanat : « {derived} » racheté {gain:.3f} cr/u, mais fabriqué avec "
                                     f"{per_unit:g} × « {ingredient} » vendu {cost:.3f} cr/u ({cost_pool})")


def _loot_items(node: object) -> list[str]:
    out: list[str] = []
    if isinstance(node, dict):
        if node.get("type") == "minecraft:item" and isinstance(node.get("name"), str):
            out.append(node["name"])
        for v in node.values():
            out.extend(_loot_items(v))
    elif isinstance(node, list):
        for v in node:
            out.extend(_loot_items(v))
    return out


def _check_outpost_loot(ctx: Context) -> int:
    count = 0
    for path in ctx.data_files("loot_table"):
        if not path.stem.startswith("avant_poste_"):
            continue
        data = ctx.load(path)
        if data is None:
            continue
        count += 1
        subject = ctx.rel(path)
        for i, item in enumerate(_loot_items(data)):
            if ctx.check_item_id(item, subject, f"entrée {i}") and ctx.banned_from_loot(item):
                ctx.error(subject, f"« {item} » appartient à #sovereignfrontier:interdit_caisses")
    return count


def check(ctx: Context) -> list[tuple[str, str]]:
    before = len(ctx.errors)
    files = ctx.data_files("merchants", namespace=None)
    if not files:
        ctx.warn("merchants", "aucun pool marchand (data/<ns>/merchants/*.json)")
    offers: list[dict] = []
    stems = set()
    for path in files:
        stems.add(path.stem)
        offers.extend(_check_pool(ctx, path))
    if files:
        for name in EXPECTED_POOLS:
            if name not in stems:
                ctx.warn("merchants", f"pool « {name} » absent : les gabarits d'avant-poste le référencent")
    _check_spread(ctx, offers)
    _check_crafting(ctx, offers)
    _check_outpost_loot(ctx)
    # Les aides du contexte ajoutent leurs erreurs à ctx.errors : on les rend au pilote, qui les y remet.
    found = ctx.errors[before:]
    del ctx.errors[before:]
    return found
