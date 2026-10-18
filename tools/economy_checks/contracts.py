# Propriété : WP3
"""Contrôle des modèles de contrat (``data/<ns>/contracts/*.json``), mêmes règles que le chargement en jeu
(``contract/ContractTemplate`` et ``ContractRegistry``, docs/conception/J4.md §1.6, §1.11, §2.3) :

  * format strict : champs connus seulement ; ``kind`` (deliver, kill, explore) ; ``weight`` 1..10 000 ;
    ``requires`` = paliers de recherche existants ; ``min_population`` 0..10 000 ; ``duration_hours`` 1..720
    (obligatoire) ; ``objective`` conforme au type, ``reward`` non vide ;
  * livraison : ``item`` connu (pas ``minecraft:air``), ``amount {min, max, step}`` (1..10 000) ;
  * chasse : ``entity`` = type ou ``#tag`` ; tag du mod existant (``tags/entity_type/``) ; toute entité vanilla visée
    (directement ou par un tag du mod) doit être un monstre (``MobCategory.MONSTER``, liste 1.21.1 ci-dessous) ;
    un tag vanilla n'est pas vérifiable ici (avertissement : ``#minecraft:skeletons`` contient le cheval squelette) ;
  * exploration : exactement une cible parmi ``biome`` (+ ``min_distance``), ``waypoint {min, max, radius}``,
    ``outposts {min, max}`` (1..8) et ``dimension`` ; tag de biome du mod existant ;
  * récompense : ``money`` 0..1 000 000, ``points`` 0..1000, au plus 4 clés (type de caisse connu, 1..64, chance
    dans ]0 ; 1]), au plus 8 objets (connus, uniques, 1..64, ni clé de caisse, ni ``#interdit_caisses``) ;
  * plafonds : ``money_per_unit × max × 2,5 ≤ 100 000`` et ``points + points_per_unit × max × 2,5 ≤ 1000``
    (exploration : ``max`` sans facteur de taille) ;
  * règle CI seulement : ``money_per_unit ≤ 0,5 ×`` le prix unitaire marchand minimal du même objet (livraison) ;
  * nom affiché : ``sovereignfrontier.contract.template.<chemin>`` présent dans ``en_us.json``.
L'absence de modèle n'est qu'un avertissement (le dépôt de l'auto-test du socle n'en contient pas).
"""

from __future__ import annotations

from pathlib import Path

from .common import (
    BANNED_FROM_LOOT, CONTRACT_DELIVER_RATIO, CONTRACT_MONEY_CAP, CONTRACT_POINTS_MAX, CONTRACT_SIZE_CAP, DATA_REL,
    ID_RE, NAMESPACE, Context, is_int, is_number, load_json, show,
)

LANG_REL = Path("src/main/resources/assets") / NAMESPACE / "lang" / "en_us.json"

KINDS = ("deliver", "kill", "explore")
FIELDS = {"kind", "weight", "requires", "min_population", "duration_hours", "objective", "reward"}
OBJECTIVE_FIELDS = {
    "deliver": {"item", "amount"},
    "kill": {"entity", "amount"},
    "explore": {"biome", "min_distance", "waypoint", "outposts", "dimension"},
}
AMOUNT_FIELDS = {"min", "max", "step"}
WAYPOINT_FIELDS = {"min", "max", "radius"}
OUTPOSTS_FIELDS = {"min", "max"}
REWARD_FIELDS = {"money", "money_per_unit", "points", "points_per_unit", "keys", "items"}
KEY_FIELDS = {"crate", "count", "chance"}
ITEM_FIELDS = {"item", "count"}
EXPLORE_TARGETS = ("biome", "waypoint", "outposts", "dimension")

# Constantes de ContractTemplate (jamais lues dans la configuration du serveur).
WEIGHT_MAX = 10_000
DURATION_HOURS_MAX = 720
MIN_POPULATION_MAX = 10_000
AMOUNT_MAX = 10_000
DISTANCE_MAX = 1_000_000
WAYPOINT_MAX = 100_000
RADIUS_MAX = 1_024
OUTPOSTS_MAX = 8
MONEY_MAX = 1_000_000
MAX_KEYS = 4
KEY_COUNT_MAX = 64
MAX_ITEMS = 8
ITEM_COUNT_MAX = 64

# Entités vanilla de la catégorie MONSTER (MC 1.21.1, EntityType.java).
VANILLA_MONSTERS = frozenset(f"minecraft:{n}" for n in (
    "blaze", "bogged", "breeze", "cave_spider", "creeper", "drowned", "elder_guardian", "ender_dragon", "enderman",
    "endermite", "evoker", "ghast", "giant", "guardian", "hoglin", "husk", "illusioner", "magma_cube", "phantom",
    "piglin", "piglin_brute", "pillager", "ravager", "shulker", "silverfish", "skeleton", "slime", "spider", "stray",
    "vex", "vindicator", "warden", "witch", "wither", "wither_skeleton", "zoglin", "zombie", "zombie_villager",
    "zombified_piglin",
))


class _Checker:
    def __init__(self, ctx: Context, subject: str):
        self.ctx = ctx
        self.subject = subject

    def error(self, msg: str) -> None:
        self.ctx.error(self.subject, msg)

    def fields(self, obj: object, allowed: set[str], where: str) -> dict | None:
        if not isinstance(obj, dict):
            self.error(f"{where} : objet JSON attendu, reçu {show(obj)}")
            return None
        unknown = sorted(set(obj) - allowed)
        if unknown:
            self.error(f"{where} : champ(s) inconnu(s) : {', '.join(unknown)} (le jeu rejette le fichier)")
        return obj

    def int_field(self, obj: dict, key: str, lo: int, hi: int, where: str, default: int | None = None) -> int | None:
        if key not in obj:
            if default is None:
                self.error(f"{where}.{key} : champ obligatoire absent")
            return default
        v = obj[key]
        if not is_int(v) or not lo <= v <= hi:
            self.error(f"{where}.{key} : entier de {lo} à {hi} attendu, reçu {show(v)}")
            return None
        return v

    def number(self, obj: dict, key: str, lo: float, hi: float, where: str, default: float = 0.0) -> float | None:
        if key not in obj:
            return default
        v = obj[key]
        if not is_number(v) or not lo <= v <= hi:
            self.error(f"{where}.{key} : nombre de {lo:g} à {hi:g} attendu, reçu {show(v)}")
            return None
        return float(v)

    def ident(self, value: object, where: str, allow_tag: bool = False) -> tuple[str, bool] | None:
        if not isinstance(value, str):
            self.error(f"{where} : identifiant attendu, reçu {show(value)}")
            return None
        tag = allow_tag and value.startswith("#")
        raw = value[1:] if tag else value
        if not ID_RE.match(raw):
            self.error(f"{where} : identifiant invalide {show(value)}")
            return None
        return raw, tag


def _tag_file(ctx: Context, registry: str, tag: str) -> Path:
    ns, path = tag.split(":", 1)
    return ctx.root / DATA_REL / ns / "tags" / registry / f"{path}.json"


def _entity_tag_values(ctx: Context, tag: str, chk: _Checker, where: str, seen: set[str]) -> set[str] | None:
    """Entités d'un tag du mod (résolution récursive) ; None si le tag n'existe pas."""
    path = _tag_file(ctx, "entity_type", tag)
    if not path.is_file():
        return None
    try:
        data = load_json(path)
    except (ValueError, OSError) as e:
        chk.error(f"{where} : tag « #{tag} » illisible : {e}")
        return set()
    values = data.get("values") if isinstance(data, dict) else None
    if not isinstance(values, list):
        chk.error(f"{where} : tag « #{tag} » : objet {{\"values\": [...]}} attendu")
        return set()
    out: set[str] = set()
    for v in values:
        v = v.get("id") if isinstance(v, dict) else v
        if not isinstance(v, str):
            continue
        if v.startswith("#"):
            ref = v[1:]
            if ref in seen:
                chk.error(f"{where} : cycle de tags d'entités via « #{ref} »")
                continue
            if ref.startswith(NAMESPACE + ":"):
                sub = _entity_tag_values(ctx, ref, chk, where, seen | {ref})
                if sub is None:
                    chk.error(f"{where} : tag d'entités inconnu « #{ref} » (dans « #{tag} »)")
                else:
                    out |= sub
            else:
                ctx.warn(chk.subject, f"{where} : tag vanilla « #{ref} » non vérifiable ici (catégorie MONSTER contrôlée au chargement)")
        else:
            out.add(v)
    return out


def _check_amount(chk: _Checker, obj: object, where: str) -> int | None:
    amount = chk.fields(obj, AMOUNT_FIELDS, where)
    if amount is None:
        return None
    lo = chk.int_field(amount, "min", 1, AMOUNT_MAX, where)
    hi = chk.int_field(amount, "max", lo or 1, AMOUNT_MAX, where)
    chk.int_field(amount, "step", 1, AMOUNT_MAX, where, default=1)
    return hi


def _check_objective(ctx: Context, chk: _Checker, kind: str, objective: object,
                     min_prices: dict[str, tuple[float, str]], reward: dict) -> tuple[float | None, bool]:
    """Vérifie l'objectif ; renvoie (quantité maximale, mise à l'échelle par le nombre de membres)."""
    obj = chk.fields(objective, OBJECTIVE_FIELDS[kind], "objective")
    if obj is None:
        return None, False
    if kind == "deliver":
        item = obj.get("item")
        if "item" not in obj:
            chk.error("objective.item : champ obligatoire absent")
        elif ctx.check_item_id(item, chk.subject, "objective.item") and item == "minecraft:air":
            chk.error("objective.item : « minecraft:air » interdit")
        if "amount" not in obj:
            chk.error("objective.amount : champ obligatoire absent")
            return None, True
        top = _check_amount(chk, obj["amount"], "objective.amount")
        mpu = reward.get("money_per_unit", 0)
        if isinstance(item, str) and item in min_prices and is_number(mpu):
            price, pool = min_prices[item]
            if mpu > CONTRACT_DELIVER_RATIO * price + 1e-9:
                chk.error(f"reward.money_per_unit = {mpu:g} : au-delà de {CONTRACT_DELIVER_RATIO:g} × le prix marchand "
                          f"minimal de « {item} » ({price:g} cr/u, {pool}) : arbitrage marchand → contrat")
        return top, True
    if kind == "kill":
        target = chk.ident(obj.get("entity"), "objective.entity", allow_tag=True) if "entity" in obj else None
        if "entity" not in obj:
            chk.error("objective.entity : champ obligatoire absent")
        elif target is not None:
            ident, tag = target
            if tag and ident.startswith(NAMESPACE + ":"):
                values = _entity_tag_values(ctx, ident, chk, "objective.entity", {ident})
                if values is None:
                    chk.error(f"objective.entity : tag d'entités inconnu « #{ident} » "
                              f"(data/{NAMESPACE}/tags/entity_type/{ident.split(':', 1)[1]}.json)")
                elif not values:
                    chk.error(f"objective.entity : tag « #{ident} » vide")
                else:
                    for v in sorted(values):
                        if v.startswith("minecraft:") and v not in VANILLA_MONSTERS:
                            chk.error(f"objective.entity : « {v} » (dans « #{ident} ») hors de la catégorie MONSTER")
            elif tag:
                ctx.warn(chk.subject, f"objective.entity : tag vanilla « #{ident} » non vérifiable ici ; préférer un tag "
                                      f"du mod (#{NAMESPACE}:contrat/…) dont chaque entité est un monstre")
            elif ident.startswith("minecraft:") and ident not in VANILLA_MONSTERS:
                chk.error(f"objective.entity : « {ident} » hors de la catégorie MONSTER")
        if "amount" not in obj:
            chk.error("objective.amount : champ obligatoire absent")
            return None, True
        return _check_amount(chk, obj["amount"], "objective.amount"), True

    targets = [k for k in EXPLORE_TARGETS if k in obj]
    if len(targets) != 1:
        chk.error(f"objective : exactement une cible d'exploration attendue ({', '.join(EXPLORE_TARGETS)}), "
                  f"{len(targets)} trouvée(s)")
        return None, False
    if "min_distance" in obj and "biome" not in obj:
        chk.error("objective.min_distance : réservé à la cible « biome »")
    target = targets[0]
    if target == "biome":
        ident = chk.ident(obj["biome"], "objective.biome", allow_tag=True)
        if ident is not None and ident[1] and ident[0].startswith(NAMESPACE + ":") \
                and not _tag_file(ctx, "worldgen/biome", ident[0]).is_file():
            chk.error(f"objective.biome : tag de biome inconnu « #{ident[0]} »")
        chk.int_field(obj, "min_distance", 0, DISTANCE_MAX, "objective", default=0)
        return 1, False
    if target == "waypoint":
        w = chk.fields(obj["waypoint"], WAYPOINT_FIELDS, "objective.waypoint")
        if w is not None:
            lo = chk.int_field(w, "min", 1, WAYPOINT_MAX, "objective.waypoint")
            chk.int_field(w, "max", lo or 1, WAYPOINT_MAX, "objective.waypoint")
            chk.int_field(w, "radius", 1, RADIUS_MAX, "objective.waypoint")
        return 1, False
    if target == "outposts":
        o = chk.fields(obj["outposts"], OUTPOSTS_FIELDS, "objective.outposts")
        if o is None:
            return None, False
        lo = chk.int_field(o, "min", 1, OUTPOSTS_MAX, "objective.outposts")
        return chk.int_field(o, "max", lo or 1, OUTPOSTS_MAX, "objective.outposts"), False
    chk.ident(obj["dimension"], "objective.dimension")
    return 1, False


def _check_reward(ctx: Context, chk: _Checker, reward: object) -> dict:
    r = chk.fields(reward, REWARD_FIELDS, "reward")
    if r is None:
        return {}
    chk.int_field(r, "money", 0, MONEY_MAX, "reward", default=0)
    chk.number(r, "money_per_unit", 0.0, CONTRACT_MONEY_CAP, "reward")
    chk.int_field(r, "points", 0, CONTRACT_POINTS_MAX, "reward", default=0)
    chk.number(r, "points_per_unit", 0.0, CONTRACT_POINTS_MAX, "reward")
    keys = r.get("keys", [])
    if not isinstance(keys, list):
        chk.error("reward.keys : liste attendue")
        keys = []
    if len(keys) > MAX_KEYS:
        chk.error(f"reward.keys : {len(keys)} entrées (maximum {MAX_KEYS})")
    for i, key in enumerate(keys):
        where = f"reward.keys[{i}]"
        k = chk.fields(key, KEY_FIELDS, where)
        if k is None:
            continue
        crate = k.get("crate")
        if crate not in ctx.crate_kinds:
            chk.error(f"{where}.crate : type de caisse inconnu {show(crate)} ({', '.join(ctx.crate_kinds)})")
        chk.int_field(k, "count", 1, KEY_COUNT_MAX, where, default=1)
        chance = k.get("chance", 1.0)
        if not is_number(chance) or not 0.0 < chance <= 1.0:
            chk.error(f"{where}.chance : nombre dans ]0 ; 1] attendu, reçu {show(chance)}")
    items = r.get("items", [])
    if not isinstance(items, list):
        chk.error("reward.items : liste attendue")
        items = []
    if len(items) > MAX_ITEMS:
        chk.error(f"reward.items : {len(items)} entrées (maximum {MAX_ITEMS})")
    seen: set[str] = set()
    for i, entry in enumerate(items):
        where = f"reward.items[{i}]"
        it = chk.fields(entry, ITEM_FIELDS, where)
        if it is None:
            continue
        item = it.get("item")
        if "item" not in it:
            chk.error(f"{where}.item : champ obligatoire absent")
        elif ctx.check_item_id(item, chk.subject, f"{where}.item"):
            if item == "minecraft:air":
                chk.error(f"{where}.item : « minecraft:air » interdit")
            if item in seen:
                chk.error(f"{where}.item : objet en double « {item} »")
            seen.add(item)
            if item in ctx.key_items():
                chk.error(f"{where}.item : clé de caisse « {item} » : utiliser « keys »")
            if ctx.banned_from_loot(item):
                chk.error(f"{where}.item : objet interdit « {item} » (#{BANNED_FROM_LOOT}) dans une récompense de contrat")
        chk.int_field(it, "count", 1, ITEM_COUNT_MAX, where, default=1)
    empty = all(not r.get(k) for k in ("money", "money_per_unit", "points", "points_per_unit")) and not keys and not items
    if empty:
        chk.error("reward : récompense vide (ni argent, ni points, ni clés, ni objets)")
    return r


def _merchant_min_prices(ctx: Context) -> dict[str, tuple[float, str]]:
    """Prix unitaire marchand minimal par objet, tous pools confondus (offres avec « price »)."""
    out: dict[str, tuple[float, str]] = {}
    for path in ctx.data_files("merchants", namespace=None):
        try:
            data = load_json(path)
        except (ValueError, OSError):
            continue  # signalé par economy_checks/merchants.py
        for offer in data.get("offers", []) if isinstance(data, dict) else []:
            if not isinstance(offer, dict):
                continue
            item, price, count = offer.get("item"), offer.get("price"), offer.get("count")
            if isinstance(item, str) and is_int(price) and is_int(count) and count > 0:
                unit = price / count
                if item not in out or unit < out[item][0]:
                    out[item] = (unit, ctx.rel(path))
    return out


def _lang(ctx: Context) -> dict | None:
    path = ctx.root / LANG_REL
    if not path.is_file():
        return None
    try:
        data = load_json(path)
    except (ValueError, OSError):
        return None
    return data if isinstance(data, dict) else None


def check(ctx: Context) -> list[tuple[str, str]]:
    before = len(ctx.errors)
    files = ctx.data_files("contracts", namespace=None)
    if not files:
        ctx.warn("contracts", "aucun modèle de contrat (data/<ns>/contracts/*.json)")
    if not ctx.research_ids:
        ctx.warn("research/", "aucun palier de recherche : « requires » non vérifié")
    min_prices = _merchant_min_prices(ctx)
    lang = _lang(ctx)
    kinds_found: set[str] = set()
    for path in files:
        subject = ctx.rel(path)
        chk = _Checker(ctx, subject)
        data = ctx.load(path)
        if data is None:
            continue
        root = chk.fields(data, FIELDS, "modèle")
        if root is None:
            continue
        kind = root.get("kind")
        if kind not in KINDS:
            chk.error(f"kind : type inconnu {show(kind)} ({', '.join(KINDS)})")
            continue
        kinds_found.add(kind)
        chk.int_field(root, "weight", 1, WEIGHT_MAX, "modèle", default=1)
        requires = root.get("requires", [])
        if not isinstance(requires, list):
            chk.error("requires : liste d'identifiants de palier attendue")
            requires = []
        for i, req in enumerate(requires):
            if not isinstance(req, str) or not ID_RE.match(req):
                chk.error(f"requires[{i}] : identifiant de palier invalide {show(req)}")
            elif ctx.research_ids and req not in ctx.research_ids:
                chk.error(f"requires[{i}] : palier de recherche inconnu « {req} »")
        chk.int_field(root, "min_population", 0, MIN_POPULATION_MAX, "modèle", default=0)
        chk.int_field(root, "duration_hours", 1, DURATION_HOURS_MAX, "modèle")
        if "reward" not in root:
            chk.error("reward : champ obligatoire absent")
            reward = {}
        else:
            reward = _check_reward(ctx, chk, root["reward"])
        if "objective" not in root:
            chk.error("objective : champ obligatoire absent")
            continue
        top, scales = _check_objective(ctx, chk, kind, root["objective"], min_prices, reward)
        if top is not None:
            scaled = top * CONTRACT_SIZE_CAP if scales else top
            mpu = reward.get("money_per_unit", 0)
            if is_number(mpu) and mpu * scaled > CONTRACT_MONEY_CAP + 1e-9:
                chk.error(f"reward.money_per_unit : {mpu:g} × {scaled:g} dépasse {CONTRACT_MONEY_CAP} cr")
            pts, ppu = reward.get("points", 0), reward.get("points_per_unit", 0)
            if is_number(pts) and is_number(ppu) and pts + ppu * scaled > CONTRACT_POINTS_MAX + 1e-9:
                chk.error(f"reward.points : jusqu'à {pts + ppu * scaled:g} points (maximum {CONTRACT_POINTS_MAX})")
        if lang is not None:
            # data/<ns>/contracts/<chemin>.json → sovereignfrontier.contract.template.<chemin> (« / » → « . »).
            inner = Path(*path.relative_to(ctx.root / DATA_REL).parts[2:]).with_suffix("")
            key = f"{NAMESPACE}.contract.template." + inner.as_posix().replace("/", ".")
            if key not in lang:
                chk.error(f"nom affiché manquant : « {key} » absent de {LANG_REL.as_posix()}")
    if files:
        for kind in KINDS:
            if kind not in kinds_found:
                ctx.warn("contracts", f"aucun modèle de type « {kind} »")
    # Les aides du contexte ajoutent leurs erreurs à ctx.errors : on les rend au pilote, qui les y remet.
    found = ctx.errors[before:]
    del ctx.errors[before:]
    return found
