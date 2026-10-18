"""Utilitaires partagés par ``tools/check_economy.py`` et les modules ``economy_checks/*`` (socle WP0).

Le dépôt est lu à partir d'une racine (le vrai dépôt, ou un dépôt temporaire en ``--self-test``) :
  * identifiants enregistrés : ``registry/ModBlocks.java`` et ``registry/ModItems.java`` (noms littéraux
    de ``.register…("nom", …)``) ;
  * énumérations : ``reward/CrateKind.java`` et ``enterprise/Sector.java`` ;
  * paliers de recherche : noms des fichiers ``data/sovereignfrontier/research/*.json`` ;
  * tags d'objets : ``data/<ns>/tags/item/**.json``, résolus récursivement (les tags d'un autre espace
    de noms, vanilla par exemple, restent opaques).
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path

NAMESPACE = "sovereignfrontier"
DATA_REL = Path("src/main/resources/data")
NS_DATA_REL = DATA_REL / NAMESPACE
JAVA_REL = Path("src/main/java/io/github/theoblaise1") / NAMESPACE
REGISTRY_FILES = (JAVA_REL / "registry" / "ModBlocks.java", JAVA_REL / "registry" / "ModItems.java")
CRATE_KIND_REL = JAVA_REL / "reward" / "CrateKind.java"
SECTOR_REL = JAVA_REL / "enterprise" / "Sector.java"
RESEARCH_REL = NS_DATA_REL / "research"

# ---------------------------------------------------------------------------
# Constantes de validation (docs/conception/J4.md §1.11), identiques aux constantes Java : jamais lues
# dans la configuration du serveur (non chargée au premier chargement des datapacks).
# ---------------------------------------------------------------------------
MAX_PAYOUT_RATIO = 0.8          # rachat/u ≤ 0,8 × prix/u minimal, tous pools confondus
CRATE_ROLLS = (1, 6)            # tirages par ouverture
CRATE_MAX_ENTRIES = 32
CRATE_RARES = (1, 3)            # entrées rares (avec « id ») par table
POOL_MAX_OFFERS = 24
DAILY_KEYS = (1, 2)             # clés par jour de /daily
PER_WORKER_MAX = 64.0           # per_worker dans ]0 ; 64]
BONUS_PER_TIER_MAX = 0.2
CONTRACT_POINTS_MAX = 1000
CONTRACT_MONEY_CAP = 100_000    # money_per_unit × max × 2,5
CONTRACT_SIZE_CAP = 2.5
CONTRACT_DELIVER_RATIO = 0.5    # règle CI : money_per_unit ≤ 0,5 × prix marchand unitaire minimal

# Tags d'interdiction (sans espace de noms) et inclusions directes obligatoires.
RESTRICTED = f"{NAMESPACE}:restreint"
OFF_ECONOMY = f"{NAMESPACE}:hors_economie"
BANNED_FROM_LOOT = f"{NAMESPACE}:interdit_caisses"
RESTRICTED_SUBTAGS = tuple(f"{NAMESPACE}:restreint/{s}" for s in ("palier_3", "palier_4", "fusee_finale", "arme_max"))
ECONOMY_TAGS = (RESTRICTED, *RESTRICTED_SUBTAGS, OFF_ECONOMY, BANNED_FROM_LOOT)
REQUIRED_INCLUDES = {
    RESTRICTED: RESTRICTED_SUBTAGS,
    BANNED_FROM_LOOT: (RESTRICTED, OFF_ECONOMY),
}

ID_RE = re.compile(r"^([a-z0-9_.-]+):([a-z0-9_./-]+)$")


# ---------------------------------------------------------------------------
# Lecture
# ---------------------------------------------------------------------------

def _no_duplicate_keys(pairs: list[tuple[str, object]]) -> dict:
    obj: dict = {}
    for k, v in pairs:
        if k in obj:
            raise ValueError(f"clé « {k} » en double dans un même objet")
        obj[k] = v
    return obj


def _reject_constant(name: str) -> None:
    raise ValueError(f"valeur non standard « {name} »")


def load_json(path: Path) -> object:
    """JSON strict : clés en double et NaN/Infinity refusés."""
    return json.loads(path.read_text(encoding="utf-8"),
                      object_pairs_hook=_no_duplicate_keys, parse_constant=_reject_constant)


def read_registered(root: Path) -> set[str] | None:
    """
    Noms enregistrés par ModBlocks/ModItems (« caisse_commune », « cle_commune »…), ou None si illisibles :
    premier argument littéral de ``.register…("nom", …)`` ou d'une aide locale ``static DeferredBlock<…> aide(String
    id, …)`` (ex. ``machine("generateur_thermique", …)``).
    """
    names: set[str] = set()
    found = False
    for rel in REGISTRY_FILES:
        path = root / rel
        if path.is_file():
            found = True
            src = path.read_text(encoding="utf-8")
            names.update(re.findall(r'\.register\w*\(\s*"([a-z0-9_./-]+)"', src))
            helpers = re.findall(r'static\s+Deferred(?:Block|Item)<[^>]*>\s+(\w+)\(\s*String\s+\w+', src)
            for helper in helpers:
                names.update(re.findall(rf'\b{helper}\(\s*"([a-z0-9_./-]+)"', src))
    return names if found and names else None


def read_enum_ids(path: Path) -> list[str]:
    """Identifiants « NOM("id") » d'une énumération à un argument chaîne ; liste vide si le fichier manque."""
    if not path.is_file():
        return []
    return re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', path.read_text(encoding="utf-8"), re.M)


def is_int(v: object) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def is_number(v: object) -> bool:
    return (is_int(v) or isinstance(v, float)) and math.isfinite(v)


def show(v: object) -> str:
    return json.dumps(v, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Contexte partagé
# ---------------------------------------------------------------------------

@dataclass
class Context:
    root: Path
    registered: set[str] | None = None
    crate_kinds: list[str] = field(default_factory=list)
    sectors: list[str] = field(default_factory=list)
    research_ids: set[str] = field(default_factory=set)
    # Tags d'objets du dépôt : identifiant « ns:chemin » → valeurs brutes, puis objets résolus.
    raw_tags: dict[str, list] = field(default_factory=dict)
    tags: dict[str, frozenset[str]] = field(default_factory=dict)
    # Références de tags d'autres espaces de noms atteintes depuis chaque tag (opaques ici).
    external_refs: dict[str, frozenset[str]] = field(default_factory=dict)
    errors: list[tuple[str, str]] = field(default_factory=list)
    warnings: list[tuple[str, str]] = field(default_factory=list)
    files: int = 0

    def error(self, subject: str, msg: str) -> None:
        self.errors.append((subject, msg))

    def warn(self, subject: str, msg: str) -> None:
        self.warnings.append((subject, msg))

    def rel(self, path: Path) -> str:
        try:
            return path.relative_to(self.root).as_posix()
        except ValueError:
            return path.as_posix()

    def data_files(self, subdir: str, namespace: str | None = NAMESPACE) -> list[Path]:
        """Fichiers JSON de ``data/<ns>/<subdir>/`` (tous les espaces de noms si ``namespace`` vaut None)."""
        base = self.root / DATA_REL
        namespaces = [base / namespace] if namespace else sorted(p for p in base.glob("*") if p.is_dir())
        out: list[Path] = []
        for ns in namespaces:
            folder = ns / subdir
            if folder.is_dir():
                out.extend(sorted(folder.rglob("*.json")))
        return out

    def load(self, path: Path) -> object | None:
        """Charge un JSON ; None (et une erreur) s'il est invalide."""
        self.files += 1
        try:
            return load_json(path)
        except (ValueError, OSError) as e:
            self.error(self.rel(path), f"JSON invalide : {e}")
            return None

    def key_items(self) -> set[str]:
        """Clés de caisse (« sovereignfrontier:cle_<type> ») : aucune source automatique ni recette."""
        if self.crate_kinds:
            return {f"{NAMESPACE}:cle_{k}" for k in self.crate_kinds}
        return {f"{NAMESPACE}:{n}" for n in (self.registered or set()) if n.startswith("cle_")}

    def restricted(self, item: str) -> bool:
        return item in self.tags.get(RESTRICTED, frozenset())

    def banned_from_loot(self, item: str) -> bool:
        return item in self.tags.get(BANNED_FROM_LOOT, frozenset())

    def check_item_id(self, item: object, subject: str, where: str) -> bool:
        """Identifiant d'objet bien formé et, pour ``sovereignfrontier:``, enregistré ; erreur sinon."""
        if not isinstance(item, str) or not ID_RE.match(item):
            self.error(subject, f"{where} : identifiant d'objet invalide {show(item)}")
            return False
        ns, name = item.split(":", 1)
        if ns == NAMESPACE and self.registered is not None and name not in self.registered:
            self.error(subject, f"{where} : objet « {item} » non enregistré (registry/ModItems, ModBlocks)")
            return False
        return True

    def check_tag_ref(self, tag: object, subject: str, where: str) -> bool:
        """Référence de tag d'objets (sans « # ») : bien formée et, pour ``sovereignfrontier:``, existante."""
        if not isinstance(tag, str) or not ID_RE.match(tag):
            self.error(subject, f"{where} : tag invalide {show(tag)}")
            return False
        if tag.startswith(NAMESPACE + ":") and tag not in self.raw_tags:
            self.error(subject, f"{where} : tag inconnu « #{tag} »")
            return False
        return True


def build_context(root: Path) -> Context:
    ctx = Context(root=root)
    ctx.registered = read_registered(root)
    if ctx.registered is None:
        ctx.warn("", "registre introuvable (registry/ModBlocks.java, ModItems.java) : identifiants non vérifiés")
    ctx.crate_kinds = read_enum_ids(root / CRATE_KIND_REL)
    ctx.sectors = read_enum_ids(root / SECTOR_REL)
    research = root / RESEARCH_REL
    if research.is_dir():
        ctx.research_ids = {f"{NAMESPACE}:{p.stem}" for p in research.glob("*.json")}
    load_tags(ctx)
    return ctx


# ---------------------------------------------------------------------------
# Tags d'objets
# ---------------------------------------------------------------------------

def load_tags(ctx: Context) -> None:
    """Lit les tags d'objets du dépôt puis les résout récursivement (cycles et références inconnues signalés)."""
    base = ctx.root / DATA_REL
    for ns_dir in sorted(p for p in base.glob("*") if p.is_dir()) if base.is_dir() else []:
        folder = ns_dir / "tags" / "item"
        for path in sorted(folder.rglob("*.json")) if folder.is_dir() else []:
            tag_id = f"{ns_dir.name}:{path.relative_to(folder).with_suffix('').as_posix()}"
            data = ctx.load(path)
            if data is None:
                continue
            subject = ctx.rel(path)
            if not isinstance(data, dict) or not isinstance(data.get("values"), list):
                ctx.error(subject, "objet {\"values\": [...]} attendu")
                continue
            unknown = sorted(set(data) - {"values", "replace"})
            if unknown:
                ctx.error(subject, f"champ(s) inconnu(s) : {', '.join(unknown)}")
            if "replace" in data and not isinstance(data["replace"], bool):
                ctx.error(subject, "« replace » doit être un booléen")
            ctx.raw_tags[tag_id] = data["values"]

    done: dict[str, frozenset[str]] = {}
    external: dict[str, frozenset[str]] = {}

    def resolve(tag: str, stack: list[str]) -> tuple[frozenset[str], frozenset[str]]:
        if tag in done:
            return done[tag], external[tag]
        if tag in stack:
            cycle = " → ".join(stack[stack.index(tag):] + [tag])
            ctx.error(f"#{tag}", f"cycle de tags : {cycle}")
            return frozenset(), frozenset()
        stack.append(tag)
        items: set[str] = set()
        ext: set[str] = set()
        subject = f"#{tag}"
        for i, entry in enumerate(ctx.raw_tags[tag]):
            value = entry.get("id") if isinstance(entry, dict) else entry
            if isinstance(entry, dict) and (set(entry) - {"id", "required"} or "id" not in entry):
                ctx.error(subject, f"values[{i}] : objet {{\"id\", \"required\"}} attendu, reçu {show(entry)}")
                continue
            if not isinstance(value, str):
                ctx.error(subject, f"values[{i}] : chaîne attendue, reçu {show(entry)}")
                continue
            if value.startswith("#"):
                ref = value[1:]
                if not ID_RE.match(ref):
                    ctx.error(subject, f"values[{i}] : tag invalide {show(value)}")
                elif ref in ctx.raw_tags:
                    sub_items, sub_ext = resolve(ref, stack)
                    items |= sub_items
                    ext |= sub_ext
                elif ref.startswith(NAMESPACE + ":"):
                    ctx.error(subject, f"values[{i}] : tag inconnu « {value} »")
                else:
                    ext.add(ref)
            elif ctx.check_item_id(value, subject, f"values[{i}]"):
                items.add(value)
        stack.pop()
        done[tag] = frozenset(items)
        external[tag] = frozenset(ext)
        return done[tag], external[tag]

    for tag in ctx.raw_tags:
        resolve(tag, [])
    ctx.tags = done
    ctx.external_refs = external
