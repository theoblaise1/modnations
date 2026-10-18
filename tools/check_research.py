#!/usr/bin/env python3
"""Vérifie l'arbre de recherche (jalon J2) : ``data/sovereignfrontier/research/*.json``.

Le plan de référence ``docs/recherche.md`` est le contrat : noms, récompenses, prérequis
croisés, population minimale et catalogue des clés de déblocage. Les identifiants de bonus
sont lus dans ``research/ResearchStat.java``.

Contrôles (code de sortie 1 en cas d'erreur ; les avertissements ne font pas échouer) :
  1. JSON valide (sans clé en double), champs autorisés uniquement (domain, tier,
     cost{treasury, points, items[{item, count}]}, requires, min_population,
     duration_seconds, rewards[{type, key, stat, amount}]), types corrects, nom de fichier
     « <domain>_<tier>.json » ;
  2. domaines et nombre de paliers (civil 15, militaire 16, ressources 15, industrie 12,
     technologie 7), chaque domaine complet de 1 à n, sans trou ni doublon ;
  3. « requires » : identifiants « sovereignfrontier:<domaine>_<n> » existants, jamais un
     palier inférieur du même domaine (déjà implicite) ; graphe des prérequis (implicites et
     croisés) sans cycle ;
  4. récompenses : « unlock » avec une clé du tableau « Déblocages » du plan, ou « bonus »
     avec une stat de ResearchStat et un montant non nul ;
  5. conformité au plan (récompenses, prérequis croisés, population minimale) ;
  6. objets : « minecraft:… » ou « sovereignfrontier:… » (enregistré dans registry/),
     quantité entière > 0, au plus 30 piles de 64 au total ;
  7. trésorerie : AVERTISSEMENT si l'écart au barème 200 × palier^1,5 (×1,2 militaire,
     ×1,5 industrie, ×4 technologie) dépasse 25 % ; points de recherche (J4) : exactement
     « cost.points = 5 × (palier − 5) » à partir du palier 6, 0 (ou absent) en dessous ;
  8. traductions (en_us.json) : nom de chaque palier (identique au plan), description
     « .desc », nom « sovereignfrontier.unlock.<clé> » de chaque déblocage utilisé.

Le dépôt est localisé à partir de l'emplacement du script (racine = parent de ``tools/``).

Usage :
    python3 tools/check_research.py              # vérification complète
    python3 tools/check_research.py --no-lang    # sans le point 8 (traductions)
    python3 tools/check_research.py --self-test  # tests intégrés sur un arbre temporaire
"""

from __future__ import annotations

import argparse
import json
import math
import re
import shutil
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
NAMESPACE = "sovereignfrontier"
PREFIX = NAMESPACE + "."

RESEARCH_REL = Path("src/main/resources/data") / NAMESPACE / "research"
PLAN_REL = Path("docs/recherche.md")
JAVA_REL = Path("src/main/java/io/github/theoblaise1") / NAMESPACE
STAT_REL = JAVA_REL / "research" / "ResearchStat.java"
DOMAIN_REL = JAVA_REL / "research" / "ResearchDomain.java"
REGISTRY_REL = JAVA_REL / "registry"
LANG_REL = Path("src/main/resources/assets") / NAMESPACE / "lang" / "en_us.json"

# Domaines et nombre de paliers (recoupés avec ResearchDomain.java et les titres du plan).
DOMAINS = {"civil": 15, "militaire": 16, "ressources": 15, "industrie": 12, "technologie": 7}
EXPECTED_IDS = frozenset(f"{d}_{n}" for d, c in DOMAINS.items() for n in range(1, c + 1))

# Barème de trésorerie : 200 × palier^1,5 (arrondi à 50) × facteur du domaine.
TREASURY_BASE = 200.0
TREASURY_EXPONENT = 1.5
TREASURY_FACTOR = {"militaire": 1.2, "industrie": 1.5, "technologie": 4.0}
TREASURY_TOLERANCE = 0.25
TREASURY_ROUNDING = 50

# Points de recherche (J4, gagnés par les contrats) : 5 × (palier − 5) à partir du palier 6, 0 en dessous.
POINTS_PER_TIER = 5
POINTS_FREE_TIERS = 5
POINTS_RULE = "5 × (palier − 5) à partir du palier 6, 0 en dessous"

MAX_STACKS = 30          # somme des piles (ceil(count / 64)) d'un palier
STACK_SIZE = 64
ITEM_TYPES = (1, 4)      # barème : 1 à 4 types d'objets
ITEM_NAMESPACES = ("minecraft", NAMESPACE)
INT_MAX = 2 ** 31 - 1
LONG_MAX = 2 ** 63 - 1

TOP_FIELDS = ("domain", "tier", "cost", "requires", "min_population", "duration_seconds", "rewards")
COST_FIELDS = ("treasury", "points", "items")
ITEM_FIELDS = ("item", "count")
REWARD_FIELDS = ("type", "key", "stat", "amount")

# Objets vanilla impossibles à obtenir en survie (MC 1.21.1).
UNOBTAINABLE = frozenset({
    "air", "barrier", "bedrock", "budding_amethyst", "bundle", "chain_command_block",
    "chorus_plant", "command_block", "command_block_minecart", "debug_stick", "dirt_path",
    "end_portal_frame", "farmland", "frogspawn", "jigsaw", "knowledge_book", "light",
    "petrified_oak_slab", "player_head", "reinforced_deepslate", "repeating_command_block",
    "spawner", "structure_block", "structure_void", "suspicious_gravel", "suspicious_sand",
    "trial_spawner", "vault",
})

ITEM_ID_RE = re.compile(r"^([a-z0-9_.-]+):([a-z0-9_./-]+)$")
REQUIRE_RE = re.compile(rf"^{NAMESPACE}:([a-z]+)_([1-9][0-9]*)$")
TIER_ID_RE = re.compile(r"^([a-z]+)_([1-9][0-9]*)$")
UNLOCK_KEY_RE = re.compile(r"^[a-z0-9_]+(?:\.[a-z0-9_]+)*$")
MINUS_SIGNS = ("-", "−", "–")  # tiret ASCII, signe moins typographique, demi-cadratin
NONE_CELLS = ("", "-", "—", "–")  # cellule « req » vide : « — »


# ---------------------------------------------------------------------------
# Utilitaires
# ---------------------------------------------------------------------------

def natural_key(s: str) -> list:
    """« civil_10 » après « civil_9 »."""
    return [int(t) if t.isdigit() else t for t in re.split(r"([0-9]+)", s)]


def is_int(v: object) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def is_number(v: object) -> bool:
    return (is_int(v) or isinstance(v, float)) and math.isfinite(v)


def show(v: object) -> str:
    return json.dumps(v, ensure_ascii=False)


def round_to(x: float, step: int) -> int:
    return int(math.floor(x / step + 0.5)) * step


def treasury_target(domain: str, tier: int) -> float:
    return TREASURY_BASE * tier ** TREASURY_EXPONENT * TREASURY_FACTOR.get(domain, 1.0)


def expected_points(tier: int) -> int:
    """Points de recherche exigés (J4) : 5 × (palier − 5) à partir du palier 6, 0 en dessous."""
    return POINTS_PER_TIER * (tier - POINTS_FREE_TIERS) if tier > POINTS_FREE_TIERS else 0


def plan_duration(tier: int) -> int:
    """Durée du moteur : Math.round(120 × palier^1,35)."""
    return int(math.floor(120.0 * tier ** 1.35 + 0.5))


def fmt_amount(a: float) -> str:
    """0.05 -> « +0,05 », -0.05 -> « −0,05 », 2 -> « +2 » (format du plan)."""
    a = float(a)
    s = f"{int(a):+d}" if a.is_integer() else f"{a:+.6f}".rstrip("0")
    return s.replace(".", ",").replace("-", "−")


def fmt_reward(r: tuple) -> str:
    return f"unlock {r[1]}" if r[0] == "unlock" else f"{r[1]} {fmt_amount(r[2])}"


def fmt_rewards(rewards: list[tuple]) -> str:
    return " ; ".join(fmt_reward(r) for r in sorted(rewards, key=reward_norm)) or "—"


def fmt_ids(ids) -> str:
    return ", ".join(sorted(ids, key=natural_key)) or "—"


def reward_norm(r: tuple) -> tuple:
    return r if r[0] == "unlock" else ("bonus", r[1], round(float(r[2]), 9))


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
    return json.loads(path.read_text(encoding="utf-8"),
                      object_pairs_hook=_no_duplicate_keys, parse_constant=_reject_constant)


# ---------------------------------------------------------------------------
# Résultats
# ---------------------------------------------------------------------------

@dataclass
class Report:
    errors: list[tuple[str, str]] = field(default_factory=list)
    warnings: list[tuple[str, str]] = field(default_factory=list)
    files: int = 0
    tiers: int = 0
    cross: int = 0
    unlocks: int = 0
    bonuses: int = 0
    lang_checked: bool = False

    def error(self, subject: str, msg: str) -> None:
        self.errors.append((subject, msg))

    def warn(self, subject: str, msg: str) -> None:
        self.warnings.append((subject, msg))

    @property
    def ok(self) -> bool:
        return not self.errors

    def text(self, entries: list[tuple[str, str]] | None = None) -> str:
        entries = self.errors if entries is None else entries
        return "\n".join(f"{s} : {m}" if s else m for s, m in entries)


def _format_entries(entries: list[tuple[str, str]]) -> list[str]:
    ordered = sorted(entries, key=lambda e: (e[0] != "", natural_key(e[0])))
    return [f"  - {s} : {m}" if s else f"  - {m}" for s, m in ordered]


def format_report(rep: Report) -> str:
    if rep.errors:
        out = [f"ERREUR : {len(rep.errors)} problème(s) dans l'arbre de recherche "
               f"({rep.files} fichier(s) analysé(s) dans {RESEARCH_REL.as_posix()}) :"]
        out += _format_entries(rep.errors)
    else:
        lang = ("traductions vérifiées" if rep.lang_checked
                else "traductions non vérifiées (--no-lang)")
        out = [f"OK : {rep.tiers} paliers valides ({len(DOMAINS)} domaines, {rep.cross} prérequis "
               f"croisés, {rep.unlocks} déblocages, {rep.bonuses} bonus), conformes à "
               f"{PLAN_REL.as_posix()} ; {lang}."]
    if rep.warnings:
        out.append(f"AVERTISSEMENT : {len(rep.warnings)} point(s) à vérifier :")
        out += _format_entries(rep.warnings)
    return "\n".join(out)


# ---------------------------------------------------------------------------
# Sources de référence : plan, ResearchStat, ResearchDomain, registre des objets
# ---------------------------------------------------------------------------

@dataclass
class PlanRow:
    tid: str
    domain: str
    tier: int
    name: str
    rewards: list[tuple]
    requires: frozenset[str]
    pop: int
    line: int


@dataclass
class Plan:
    rows: dict[str, PlanRow] = field(default_factory=dict)
    catalogue: set[str] = field(default_factory=set)
    documented_stats: set[str] = field(default_factory=set)
    sections: set[str] = field(default_factory=set)
    errors: list[tuple[str, str]] = field(default_factory=list)


def parse_plan_rewards(cell: str) -> tuple[list[tuple], list[str]]:
    rewards, errors = [], []
    for part in cell.split(";"):
        p = part.strip()
        if not p:
            errors.append(f"récompense vide dans « {cell} »")
            continue
        m = re.fullmatch(r"unlock\s+`?([^\s`]+)`?", p)
        if m:
            rewards.append(("unlock", m.group(1)))
            continue
        m = re.fullmatch(r"`?([a-z_]+)`?\s+([-+−–]?)\s*([0-9]+(?:[.,][0-9]+)?)", p)
        if m:
            value = float(m.group(3).replace(",", "."))
            rewards.append(("bonus", m.group(1), -value if m.group(2) in MINUS_SIGNS else value))
            continue
        errors.append(f"récompense illisible « {p} » (attendu « unlock <clé> » ou « <stat> ±<valeur> »)")
    return rewards, errors


def parse_plan_requires(cell: str) -> tuple[set[str], list[str]]:
    c = cell.strip()
    if c in NONE_CELLS:
        return set(), []
    ids, errors = set(), []
    for part in c.split(","):
        p = part.strip().strip("`")
        if p.startswith(NAMESPACE + ":"):
            p = p[len(NAMESPACE) + 1:]
        if p not in EXPECTED_IDS:
            errors.append(f"prérequis « {p} » invalide (attendu <domaine>_<n> existant)")
        elif p in ids:
            errors.append(f"prérequis « {p} » en double")
        else:
            ids.add(p)
    return ids, errors


def parse_plan(text: str) -> Plan:
    plan = Plan()
    rel = PLAN_REL.as_posix()
    section: tuple | None = None
    heading_line: dict[str, int] = {}

    def err(no: int, msg: str) -> None:
        plan.errors.append((f"{rel}:{no}", msg))

    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if line.startswith("#"):
            m = re.match(r"^(#+)\s*(.*?)\s*$", line)
            level, title = len(m.group(1)), m.group(2)
            section = None
            if level == 2 and title.startswith("Déblocages"):
                section = ("unlock",)
            elif level == 2 and title.startswith("Bonus passifs"):
                section = ("stats",)
            elif level == 3:
                dm = re.match(r"^(\w+)\s*\(([0-9]+)\)", title)
                if dm:
                    domain, count = dm.group(1).lower(), int(dm.group(2))
                    if domain not in DOMAINS:
                        err(no, f"section « {title} » : domaine inconnu (attendus : {', '.join(DOMAINS)})")
                    elif domain in heading_line:
                        err(no, f"section « {dm.group(1)} » en double (déjà ligne {heading_line[domain]})")
                    else:
                        if count != DOMAINS[domain]:
                            err(no, f"section « {dm.group(1)} ({count}) » : {DOMAINS[domain]} paliers attendus")
                        heading_line[domain] = no
                        plan.sections.add(domain)
                        section = ("domain", domain)
            continue
        if section is None or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip("|").split("|")]
        if all(re.fullmatch(r":?-+:?", c) for c in cells):
            continue  # ligne de séparation
        if section[0] in ("unlock", "stats"):
            target = plan.catalogue if section[0] == "unlock" else plan.documented_stats
            for key in re.findall(r"`([^`]+)`", cells[0]):
                if section[0] == "unlock" and not UNLOCK_KEY_RE.match(key):
                    err(no, f"clé de déblocage « {key} » mal formée")
                elif key in target:
                    err(no, f"clé « {key} » en double dans le tableau")
                target.add(key)
            continue
        domain = section[1]
        if not re.fullmatch(r"[0-9]+", cells[0]):
            continue  # ligne d'en-tête
        if len(cells) != 5:
            err(no, f"ligne de tableau à {len(cells)} colonnes (attendu : # | nom | récompenses | req | pop)")
            continue
        tier, name = int(cells[0]), cells[1]
        tid = f"{domain}_{tier}"
        rewards, problems = parse_plan_rewards(cells[2])
        requires, req_problems = parse_plan_requires(cells[3])
        problems += req_problems
        if not re.fullmatch(r"[0-9]+", cells[4]):
            problems.append(f"population « {cells[4]} » illisible")
        if not name:
            problems.append("nom vide")
        if not 1 <= tier <= DOMAINS[domain]:
            problems.append(f"palier {tier} hors limites pour {domain} (max {DOMAINS[domain]})")
        elif tid in plan.rows:
            problems.append(f"palier {tid} en double (déjà ligne {plan.rows[tid].line})")
        for p in problems:
            err(no, f"{tid} : {p}")
        if not problems:
            plan.rows[tid] = PlanRow(tid, domain, tier, name, rewards, frozenset(requires),
                                     int(cells[4]), no)

    for domain, count in DOMAINS.items():
        if domain not in heading_line:
            err(1, f"section « ### {domain.capitalize()} ({count}) » absente")
            continue
        missing = [f"{domain}_{n}" for n in range(1, count + 1) if f"{domain}_{n}" not in plan.rows]
        if missing:
            err(heading_line[domain], f"ligne(s) absente(s) ou invalide(s) : {', '.join(missing)}")
    if not plan.catalogue:
        err(1, "tableau « ## Déblocages » introuvable ou vide (clés entre backticks, 1re colonne)")
    return plan


def read_stats(root: Path) -> list[str] | None:
    path = root / STAT_REL
    if not path.is_file():
        return None
    ids = re.findall(r'^\s*[A-Z][A-Z0-9_]*\(\s*"([a-z0-9_]+)"\s*\)', path.read_text(encoding="utf-8"), re.M)
    return ids or None


def read_domains(root: Path) -> dict[str, int] | None:
    path = root / DOMAIN_REL
    if not path.is_file():
        return None
    found = re.findall(r'^\s*[A-Z][A-Z0-9_]*\(\s*"([a-z_]+)"\s*,\s*([0-9]+)\s*\)',
                       path.read_text(encoding="utf-8"), re.M)
    return {d: int(n) for d, n in found} or None


def read_registry(root: Path) -> set[str] | None:
    """Noms enregistrés par registry/*.java (registerBlock("x", …), registerItem("x", …)…)."""
    folder = root / REGISTRY_REL
    names: set[str] = set()
    for f in sorted(folder.glob("*.java")) if folder.is_dir() else []:
        names.update(re.findall(r'\.register\w*\(\s*"([a-z0-9_./-]+)"', f.read_text(encoding="utf-8")))
    return names or None


# ---------------------------------------------------------------------------
# Validation d'un fichier
# ---------------------------------------------------------------------------

@dataclass
class Context:
    catalogue: set[str] | None
    stats: list[str] | None
    registry: set[str] | None


@dataclass
class Tier:
    tid: str
    file: str
    domain: str
    tier: int
    treasury: int
    items: list[tuple[str, int]]
    requires: list[str]
    requires_ok: bool
    pop: int | None
    rewards: list[tuple]
    rewards_ok: bool


def _check_items(lst: object, ctx: Context, err) -> list[tuple[str, int]]:
    items: list[tuple[str, int]] = []
    if not isinstance(lst, list):
        err("« cost.items » doit être une liste")
        return items
    seen: set[str] = set()
    for i, entry in enumerate(lst):
        where = f"cost.items[{i}]"
        if not isinstance(entry, dict):
            err(f"{where} doit être un objet {{item, count}}")
            continue
        for k in entry:
            if k not in ITEM_FIELDS:
                err(f"{where} : champ inconnu « {k} » (autorisés : {', '.join(ITEM_FIELDS)})")
        item, count = entry.get("item"), entry.get("count")
        if "item" not in entry:
            err(f"{where} : champ obligatoire « item » absent")
        elif not isinstance(item, str):
            err(f"{where}.item doit être une chaîne (lu : {show(item)})")
        else:
            m = ITEM_ID_RE.match(item)
            if not m:
                err(f"{where} : identifiant d'objet « {item} » invalide (forme namespace:chemin)")
            elif m.group(1) not in ITEM_NAMESPACES:
                err(f"{where} : namespace « {m.group(1)} » interdit pour « {item} » "
                    f"(minecraft ou {NAMESPACE} uniquement)")
            elif m.group(1) == NAMESPACE and ctx.registry is not None and m.group(2) not in ctx.registry:
                err(f"{where} : objet « {item} » non enregistré dans {REGISTRY_REL.as_posix()}/")
            elif m.group(1) == "minecraft" and (m.group(2) in UNOBTAINABLE
                                                or m.group(2).endswith("_spawn_egg")
                                                or m.group(2).startswith("infested_")):
                err(f"{where} : « {item} » introuvable en survie")
            if item in seen:
                err(f"{where} : objet « {item} » en double (fusionner les quantités)")
            seen.add(item)
        if "count" not in entry:
            err(f"{where} : champ obligatoire « count » absent")
        elif not is_int(count) or not 1 <= count <= INT_MAX:
            err(f"{where}.count doit être un entier > 0 (lu : {show(count)})")
        elif isinstance(item, str):
            items.append((item, count))
    stacks = sum(math.ceil(c / STACK_SIZE) for _, c in items)
    if stacks > MAX_STACKS:
        err(f"objets : {stacks} piles de {STACK_SIZE} au total (maximum {MAX_STACKS})")
    return items


def _check_rewards(data: dict, ctx: Context, err) -> tuple[list[tuple], bool]:
    rewards: list[tuple] = []
    if "rewards" not in data:
        err("aucune récompense (« rewards » absent)")
        return rewards, True
    lst = data["rewards"]
    if not isinstance(lst, list):
        err("« rewards » doit être une liste")
        return rewards, False
    if not lst:
        err("aucune récompense (« rewards » vide)")
    ok = True
    for i, r in enumerate(lst):
        where = f"rewards[{i}]"
        if not isinstance(r, dict):
            err(f"{where} doit être un objet {{type, …}}")
            ok = False
            continue
        for k in r:
            if k not in REWARD_FIELDS:
                err(f"{where} : champ inconnu « {k} » (autorisés : {', '.join(REWARD_FIELDS)})")
        typ = r.get("type")
        if typ == "unlock":
            for k in ("stat", "amount"):
                if k in r:
                    err(f"{where} : champ « {k} » inutile pour un déblocage")
            key = r.get("key")
            if not isinstance(key, str) or not key.strip():
                err(f"{where} : un déblocage exige une clé « key » non vide")
                ok = False
            elif not UNLOCK_KEY_RE.match(key):
                err(f"{where} : clé de déblocage « {key} » mal formée (identifiant pointé en minuscules)")
                ok = False
            elif ctx.catalogue is not None and key not in ctx.catalogue:
                err(f"{where} : clé de déblocage « {key} » hors catalogue "
                    f"(tableau « Déblocages » de {PLAN_REL.as_posix()})")
                ok = False
            else:
                rewards.append(("unlock", key))
        elif typ == "bonus":
            if "key" in r:
                err(f"{where} : champ « key » inutile pour un bonus")
            stat, amount = r.get("stat"), r.get("amount")
            good = True
            if not isinstance(stat, str):
                err(f"{where} : un bonus exige « stat » (chaîne)")
                good = False
            elif ctx.stats is not None and stat not in ctx.stats:
                err(f"{where} : bonus inconnu « {stat} » (ResearchStat : {', '.join(ctx.stats)})")
                good = False
            if "amount" not in r:
                err(f"{where} : un bonus exige « amount »")
                good = False
            elif not is_number(amount):
                err(f"{where} : « amount » doit être un nombre (lu : {show(amount)})")
                good = False
            elif amount == 0:
                err(f"{where} : « amount » doit être non nul")
                good = False
            elif stat == "max_members" and not float(amount).is_integer():
                err(f"{where} : max_members doit être entier (arrondi à l'inférieur par le moteur)")
                good = False
            if good:
                rewards.append(("bonus", stat, float(amount)))
            else:
                ok = False
        elif "type" not in r:
            err(f"{where} : champ obligatoire « type » absent (unlock ou bonus)")
            ok = False
        else:
            err(f"{where} : type {show(typ)} inconnu (unlock ou bonus)")
            ok = False
    for key, n in Counter(r[1] for r in rewards if r[0] == "unlock").items():
        if n > 1:
            err(f"déblocage « {key} » en double dans le palier")
    for stat, n in Counter(r[1] for r in rewards if r[0] == "bonus").items():
        if n > 1:
            err(f"bonus « {stat} » en double dans le palier (additionner les montants)")
    return rewards, ok


def validate_file(path: Path, rel: str, ctx: Context, rep: Report
                  ) -> tuple[Tier | None, tuple[str, int] | None]:
    def err(msg: str) -> None:
        rep.error(rel, msg)

    def warn(msg: str) -> None:
        rep.warn(rel, msg)

    try:
        data = load_json(path)
    except UnicodeDecodeError:
        err("encodage invalide (UTF-8 attendu)")
        return None, None
    except json.JSONDecodeError as e:
        err(f"JSON invalide (ligne {e.lineno}, colonne {e.colno}) : {e.msg}")
        return None, None
    except ValueError as e:
        err(f"JSON invalide : {e}")
        return None, None
    if not isinstance(data, dict):
        err("JSON invalide : la racine doit être un objet")
        return None, None
    for k in data:
        if k not in TOP_FIELDS:
            err(f"champ inconnu « {k} » (autorisés : {', '.join(TOP_FIELDS)})")

    # --- domain / tier / nom de fichier ----------------------------------
    domain = None
    raw_domain = data.get("domain")
    if "domain" not in data:
        err("champ obligatoire « domain » absent")
    elif not isinstance(raw_domain, str):
        err(f"« domain » doit être une chaîne (lu : {show(raw_domain)})")
    elif raw_domain in DOMAINS:
        domain = raw_domain
    elif raw_domain.lower() in DOMAINS:
        err(f"domaine « {raw_domain} » : écrire « {raw_domain.lower()} » (minuscules)")
    else:
        err(f"domaine inconnu « {raw_domain} » (attendus : {', '.join(DOMAINS)})")

    tier = None
    raw_tier = data.get("tier")
    if "tier" not in data:
        err("champ obligatoire « tier » absent")
    elif not is_int(raw_tier) or raw_tier < 1:
        err(f"« tier » doit être un entier ≥ 1 (lu : {show(raw_tier)})")
    elif domain and raw_tier > DOMAINS[domain]:
        err(f"palier {raw_tier} hors limites pour {domain} (max {DOMAINS[domain]})")
    else:
        tier = raw_tier

    tid, claim = None, None
    if domain and tier:
        claim = (domain, tier)
        expected = f"{domain}_{tier}"
        if path.stem != expected:
            err(f"nom de fichier incorrect : le contenu décrit {domain} palier {tier}, "
                f"le fichier doit s'appeler « {expected}.json »")
        else:
            tid = expected

    # --- cost --------------------------------------------------------------
    treasury, items = 0, []
    cost = data.get("cost", {})
    if not isinstance(cost, dict):
        err(f"« cost » doit être un objet (lu : {show(cost)})")
        cost = {}
    for k in cost:
        if k not in COST_FIELDS:
            err(f"cost : champ inconnu « {k} » (autorisés : {', '.join(COST_FIELDS)})")
    if "treasury" in cost:
        v = cost["treasury"]
        if not is_int(v) or not 0 <= v <= LONG_MAX:
            err(f"cost.treasury doit être un entier ≥ 0 (lu : {show(v)})")
        else:
            treasury = v
    if "points" in cost:
        v = cost["points"]
        if not is_int(v) or not 0 <= v <= LONG_MAX:
            err(f"cost.points doit être un entier ≥ 0 (lu : {show(v)})")
        elif tier and v != expected_points(tier):
            err(f"cost.points = {v} : {expected_points(tier)} attendu ({POINTS_RULE})")
    elif tier and expected_points(tier):
        err(f"cost.points absent : {expected_points(tier)} attendu ({POINTS_RULE})")
    if "items" in cost:
        items = _check_items(cost["items"], ctx, err)
    if not ITEM_TYPES[0] <= len(items) <= ITEM_TYPES[1]:
        warn(f"{len(items)} type(s) d'objets (barème : {ITEM_TYPES[0]} à {ITEM_TYPES[1]})")

    # --- requires ------------------------------------------------------------
    requires: list[str] = []
    requires_ok = True
    if "requires" in data:
        lst = data["requires"]
        if not isinstance(lst, list):
            err(f"« requires » doit être une liste (lu : {show(lst)})")
            lst, requires_ok = [], False
        for i, r in enumerate(lst):
            m = REQUIRE_RE.match(r) if isinstance(r, str) else None
            if not m:
                err(f"requires[{i}] = {show(r)} : forme attendue « {NAMESPACE}:<domaine>_<n> »")
                requires_ok = False
                continue
            rid, rdomain, rtier = f"{m.group(1)}_{m.group(2)}", m.group(1), int(m.group(2))
            if rid in requires:
                err(f"prérequis « {r} » en double")
                continue
            if domain == rdomain and tier:
                if rtier == tier:
                    err(f"le palier se requiert lui-même (« {r} »)")
                    requires_ok = False
                    continue
                if rtier == tier - 1:
                    err(f"prérequis « {r} » redondant : le palier précédent du domaine est implicite")
                elif rtier < tier:
                    err(f"prérequis « {r} » redondant : les paliers inférieurs du domaine sont implicites")
            requires.append(rid)

    # --- min_population / duration_seconds -----------------------------------
    pop: int | None = 0
    if "min_population" in data:
        v = data["min_population"]
        if not is_int(v) or not 0 <= v <= INT_MAX:
            err(f"« min_population » doit être un entier ≥ 0 (lu : {show(v)})")
            pop = None
        else:
            pop = v
    if "duration_seconds" in data:
        v = data["duration_seconds"]
        if not is_int(v) or not 1 <= v <= LONG_MAX:
            err(f"« duration_seconds » doit être un entier > 0 (lu : {show(v)})")
        elif tier:
            warn(f"durée explicite {v} s : le plan prévoit 120 s × palier^1,35 (= {plan_duration(tier)} s)")

    # --- rewards ---------------------------------------------------------------
    rewards, rewards_ok = _check_rewards(data, ctx, err)

    # --- trésorerie (avertissement) ------------------------------------------
    if domain and tier:
        target = treasury_target(domain, tier)
        gap = abs(treasury - target) / target
        if gap > TREASURY_TOLERANCE:
            warn(f"trésorerie {treasury} éloignée du barème ≈ {round_to(target, TREASURY_ROUNDING)} "
                 f"(écart {gap * 100:.0f} %, tolérance {TREASURY_TOLERANCE * 100:.0f} %)")
        elif treasury % TREASURY_ROUNDING:
            warn(f"trésorerie {treasury} non arrondie à {TREASURY_ROUNDING} (barème)")

    if tid is None:
        return None, claim
    return Tier(tid, rel, domain, tier, treasury, items, requires, requires_ok, pop,
                rewards, rewards_ok), claim


# ---------------------------------------------------------------------------
# Graphe des prérequis
# ---------------------------------------------------------------------------

def find_cycles(graph: dict[str, list[str]]) -> list[list[str]]:
    """Un cycle par composante fautive au moins ; « a → b » : a exige b."""
    WHITE, GRAY, BLACK = 0, 1, 2
    color = {n: WHITE for n in graph}
    stack: list[str] = []
    cycles: list[list[str]] = []
    seen: set[frozenset[str]] = set()

    def visit(node: str) -> None:
        color[node] = GRAY
        stack.append(node)
        for nxt in graph[node]:
            if nxt not in color:
                continue
            if color[nxt] == GRAY:
                cyc = stack[stack.index(nxt):]
                if frozenset(cyc) not in seen:
                    seen.add(frozenset(cyc))
                    start = cyc.index(min(cyc, key=natural_key))
                    cyc = cyc[start:] + cyc[:start]
                    cycles.append(cyc + [cyc[0]])
            elif color[nxt] == WHITE:
                visit(nxt)
        stack.pop()
        color[node] = BLACK

    for n in sorted(graph, key=natural_key):
        if color[n] == WHITE:
            visit(n)
    return cycles


# ---------------------------------------------------------------------------
# Traductions
# ---------------------------------------------------------------------------

def check_lang(root: Path, tiers: dict[str, Tier], plan: Plan, stats: list[str] | None,
               used_unlocks: dict[str, str], rep: Report) -> None:
    path = root / LANG_REL
    subj = LANG_REL.name
    if not path.is_file():
        rep.error(subj, f"fichier de langue introuvable : {LANG_REL.as_posix()}")
        return
    try:
        lang = load_json(path)
    except ValueError as e:
        rep.error(subj, f"JSON invalide : {e}")
        return
    if not isinstance(lang, dict):
        rep.error(subj, "JSON invalide : la racine doit être un objet")
        return

    def text(key: str) -> str | None:
        v = lang.get(key)
        return v if isinstance(v, str) and v.strip() else None

    for tid in sorted(tiers, key=natural_key):
        t = tiers[tid]
        key = f"{PREFIX}research.{tid}"
        name, row = text(key), plan.rows.get(tid)
        if name is None:
            rep.error(t.file, f"traduction du nom « {key} » absente de {subj}")
        elif row and name != row.name:
            rep.error(t.file, f"traduction « {key} » = « {name} », le plan nomme ce palier « {row.name} »")
        if text(key + ".desc") is None:
            rep.error(t.file, f"traduction de la description « {key}.desc » absente de {subj}")
    for unlock, tid in sorted(used_unlocks.items()):
        key = f"{PREFIX}unlock.{unlock}"
        if text(key) is None:
            rep.error(subj, f"traduction « {key} » absente (déblocage de {tid})")
    for stat in stats or []:
        key = f"{PREFIX}research.stat.{stat}"
        if text(key) is None:
            rep.error(subj, f"traduction « {key} » absente (bonus de ResearchStat)")
    for domain in DOMAINS:
        key = f"{PREFIX}research.domain.{domain}"
        if text(key) is None:
            rep.error(subj, f"traduction « {key} » absente (nom de domaine)")
    tier_key = re.compile(rf"{re.escape(PREFIX)}research\.([a-z]+_[0-9]+)(?:\.desc)?")
    unlock_key = re.compile(rf"{re.escape(PREFIX)}unlock\.(.+)")
    for key in lang:
        m = tier_key.fullmatch(key)
        if m and m.group(1) not in EXPECTED_IDS:
            rep.warn(subj, f"traduction orpheline « {key} » (palier inexistant)")
        m = unlock_key.fullmatch(key)
        if m and plan.catalogue and m.group(1) not in plan.catalogue:
            rep.warn(subj, f"traduction orpheline « {key} » (clé hors catalogue)")


# ---------------------------------------------------------------------------
# Vérification complète
# ---------------------------------------------------------------------------

def check(root: Path, lang: bool = True) -> Report:
    root = Path(root)
    rep = Report()

    # --- Sources de référence --------------------------------------------------
    plan_path = root / PLAN_REL
    if plan_path.is_file():
        plan = parse_plan(plan_path.read_text(encoding="utf-8"))
        for subj, msg in plan.errors:
            rep.error(subj, msg)
    else:
        plan = Plan()
        rep.error("", f"plan de référence introuvable : {PLAN_REL.as_posix()}")
    stats = read_stats(root)
    if stats is None:
        rep.error("", f"aucun bonus lu dans {STAT_REL.as_posix()} (constantes NOM(\"id\"))")
    java_domains = read_domains(root)
    if java_domains is not None and java_domains != DOMAINS:
        rep.error("", f"{DOMAIN_REL.name} déclare {java_domains}, ce script attend {DOMAINS}")
    registry = read_registry(root)
    if registry is None:
        rep.warn("", f"registre introuvable ({REGISTRY_REL.as_posix()}) : "
                     f"objets « {NAMESPACE}:… » non vérifiés")
    if plan.documented_stats and stats and plan.documented_stats != set(stats):
        rep.warn(PLAN_REL.as_posix(), f"tableau « Bonus passifs » ({', '.join(sorted(plan.documented_stats))}) "
                                      f"différent de ResearchStat ({', '.join(sorted(stats))})")
    for row in plan.rows.values():
        subj = f"{PLAN_REL.as_posix()}:{row.line}"
        for r in row.rewards:
            if r[0] == "unlock" and plan.catalogue and r[1] not in plan.catalogue:
                rep.error(subj, f"{row.tid} : clé de déblocage « {r[1]} » absente du catalogue du plan")
            if r[0] == "bonus" and stats and r[1] not in stats:
                rep.error(subj, f"{row.tid} : bonus « {r[1]} » absent de ResearchStat")
        for rid in row.requires:
            if rid.rsplit("_", 1)[0] == row.domain:
                rep.error(subj, f"{row.tid} : prérequis croisé « {rid} » dans le même domaine")

    research_dir = root / RESEARCH_REL
    if not research_dir.is_dir():
        rep.error("", f"dossier introuvable : {RESEARCH_REL.as_posix()}")
        return rep
    ctx = Context(plan.catalogue or None, stats, registry)

    # --- 1. Fichiers -----------------------------------------------------------
    tiers: dict[str, Tier] = {}
    claims: dict[tuple[str, int], list[str]] = {}
    present: set[str] = set()
    paths = sorted((p for p in research_dir.rglob("*") if p.is_file()),
                   key=lambda p: natural_key(p.relative_to(research_dir).as_posix()))
    for path in paths:
        rel = path.relative_to(research_dir).as_posix()
        if path.suffix != ".json":
            rep.warn(rel, "fichier non JSON, ignoré par le jeu")
            continue
        rep.files += 1
        if path.parent != research_dir:
            rep.error(rel, f"sous-dossier interdit : l'identifiant serait « {NAMESPACE}:{rel[:-5]} »")
            continue
        present.add(path.stem)
        tier, claim = validate_file(path, rel, ctx, rep)
        if claim:
            claims.setdefault(claim, []).append(rel)
        if tier:
            tiers[tier.tid] = tier

    # --- 2. Domaines complets, sans doublon ----------------------------------
    for domain, count in DOMAINS.items():
        missing = [f"{domain}_{n}" for n in range(1, count + 1) if f"{domain}_{n}" not in present]
        if missing:
            rep.error("", f"{domain} : {count - len(missing)}/{count} paliers, "
                          f"manquant(s) : {', '.join(missing)}")
    for (domain, n), files in sorted(claims.items(), key=lambda c: natural_key(f"{c[0][0]}_{c[0][1]}")):
        if len(files) > 1:
            rep.error("", f"{domain}_{n} défini par plusieurs fichiers (doublon) : {', '.join(files)}")

    # --- 3. Prérequis : existence et cycles ----------------------------------
    graph: dict[str, list[str]] = {}
    for tid, t in tiers.items():
        edges = []
        previous = f"{t.domain}_{t.tier - 1}"
        if t.tier > 1 and previous in tiers:
            edges.append(previous)
        for rid in t.requires:
            if rid not in EXPECTED_IDS:
                rep.error(t.file, f"prérequis « {NAMESPACE}:{rid} » inexistant (domaine ou palier hors limites)")
            elif rid not in present:
                rep.error(t.file, f"prérequis « {NAMESPACE}:{rid} » introuvable ({rid}.json absent)")
            elif rid in tiers:
                edges.append(rid)
        graph[tid] = edges
    for cyc in find_cycles(graph):
        rep.error("", "cycle de prérequis : " + " → ".join(cyc) + " (« a → b » : a exige b)")

    # --- 4 bis. Déblocages : un seul palier par clé --------------------------
    used_unlocks: dict[str, str] = {}
    for tid in sorted(tiers, key=natural_key):
        for r in tiers[tid].rewards:
            if r[0] != "unlock":
                continue
            if r[1] in used_unlocks and used_unlocks[r[1]] != tid:
                rep.error(tiers[tid].file, f"clé de déblocage « {r[1]} » déjà débloquée par {used_unlocks[r[1]]}")
            else:
                used_unlocks.setdefault(r[1], tid)
    if plan.catalogue and not any(f"{d}_{n}" not in present for d, c in DOMAINS.items()
                                  for n in range(1, c + 1)):
        unused = sorted(plan.catalogue - set(used_unlocks))
        if unused:
            rep.warn(PLAN_REL.as_posix(), f"clé(s) du catalogue jamais débloquée(s) : {', '.join(unused)}")

    # --- 5. Conformité au plan -------------------------------------------------
    for tid in sorted(tiers, key=natural_key):
        t, row = tiers[tid], plan.rows.get(tid)
        if row is None:
            continue  # plan absent ou ligne invalide : déjà signalé
        if t.rewards_ok and sorted(map(reward_norm, t.rewards)) != sorted(map(reward_norm, row.rewards)):
            rep.error(t.file, f"écart au plan (récompenses) : JSON « {fmt_rewards(t.rewards)} », "
                              f"plan « {fmt_rewards(row.rewards)} »")
        if t.requires_ok and set(t.requires) != row.requires:
            rep.error(t.file, f"écart au plan (prérequis croisés) : JSON « {fmt_ids(t.requires)} », "
                              f"plan « {fmt_ids(row.requires)} »")
        if t.pop is not None and t.pop != row.pop:
            rep.error(t.file, f"écart au plan (population minimale) : JSON {t.pop}, plan {row.pop}")

    # --- 8. Traductions ------------------------------------------------------------
    if lang:
        rep.lang_checked = True
        check_lang(root, tiers, plan, stats, used_unlocks, rep)

    rep.tiers = len(tiers)
    rep.cross = sum(len(t.requires) for t in tiers.values())
    rep.unlocks = sum(1 for t in tiers.values() for r in t.rewards if r[0] == "unlock")
    rep.bonuses = sum(1 for t in tiers.values() for r in t.rewards if r[0] == "bonus")
    return rep


# ---------------------------------------------------------------------------
# Tests intégrés (--self-test)
# ---------------------------------------------------------------------------

def _write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _build_fixture(root: Path) -> None:
    """Arbre conforme généré à partir du plan et de ResearchStat.java réels."""
    for rel in (PLAN_REL, STAT_REL, DOMAIN_REL):
        src = REPO_ROOT / rel
        if src.is_file():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, root / rel)
    registry = root / REGISTRY_REL / "ModBlocks.java"
    registry.parent.mkdir(parents=True, exist_ok=True)
    registry.write_text('class ModBlocks { Object A = BLOCKS.registerBlock("registre_civil", null); }\n',
                        encoding="utf-8")
    plan = parse_plan((root / PLAN_REL).read_text(encoding="utf-8"))
    if plan.errors:
        raise RuntimeError("plan de référence invalide :\n" + "\n".join(f"{s} : {m}" for s, m in plan.errors))
    lang: dict[str, str] = {}
    for tid, row in plan.rows.items():
        items = [{"item": "minecraft:cobblestone", "count": 16 * row.tier},
                 {"item": "minecraft:iron_ingot", "count": row.tier}]
        if tid == "civil_1":
            items.append({"item": f"{NAMESPACE}:registre_civil", "count": 1})
        cost: dict = {"treasury": round_to(treasury_target(row.domain, row.tier), TREASURY_ROUNDING)}
        if expected_points(row.tier):
            cost["points"] = expected_points(row.tier)
        cost["items"] = items
        data: dict = {"domain": row.domain, "tier": row.tier, "cost": cost}
        if row.requires:
            data["requires"] = [f"{NAMESPACE}:{r}" for r in sorted(row.requires, key=natural_key)]
        if row.pop:
            data["min_population"] = row.pop
        data["rewards"] = [
            {"type": "unlock", "key": r[1]} if r[0] == "unlock" else
            {"type": "bonus", "stat": r[1], "amount": int(r[2]) if float(r[2]).is_integer() else r[2]}
            for r in row.rewards]
        _write_json(root / RESEARCH_REL / f"{tid}.json", data)
        lang[f"{PREFIX}research.{tid}"] = row.name
        lang[f"{PREFIX}research.{tid}.desc"] = f"Description : {row.name}."
    for key in plan.catalogue:
        lang[f"{PREFIX}unlock.{key}"] = key
    for stat in read_stats(root) or []:
        lang[f"{PREFIX}research.stat.{stat}"] = stat
    for domain in DOMAINS:
        lang[f"{PREFIX}research.domain.{domain}"] = domain.capitalize()
    _write_json(root / LANG_REL, dict(sorted(lang.items())))


def _edit(tid: str, fn):
    def mutate(root: Path) -> None:
        path = root / RESEARCH_REL / f"{tid}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        fn(data)
        _write_json(path, data)
    return mutate


def _edit_lang(fn):
    def mutate(root: Path) -> None:
        path = root / LANG_REL
        data = json.loads(path.read_text(encoding="utf-8"))
        fn(data)
        _write_json(path, data)
    return mutate


def _raw(name: str, content: str):
    def mutate(root: Path) -> None:
        (root / RESEARCH_REL / name).write_text(content, encoding="utf-8")
    return mutate


def _remove(tid: str):
    def mutate(root: Path) -> None:
        (root / RESEARCH_REL / f"{tid}.json").unlink()
    return mutate


def _req(tid: str) -> str:
    return f"{NAMESPACE}:{tid}"


def _self_test() -> int:
    failures: list[str] = []

    def expect(label: str, cond: bool, detail: str = "") -> None:
        print(f"  [{'ok' if cond else 'ÉCHEC'}] {label}")
        if not cond:
            failures.append(f"{label}\n{detail}")

    for rel in (PLAN_REL, STAT_REL):
        if not (REPO_ROOT / rel).is_file():
            print(f"ERREUR : {rel.as_posix()} introuvable, auto-tests impossibles")
            return 1

    cases = [
        # (libellé, mutation, fragments d'erreur attendus, fragments d'avertissement, lang)
        ("cycle de prérequis", _edit("industrie_2", lambda d: d.setdefault("requires", []).append(_req("civil_8"))),
         ["cycle de prérequis", "civil_8"], [], True),
        ("cycle via un palier supérieur du même domaine",
         _edit("civil_3", lambda d: d.setdefault("requires", []).append(_req("civil_9"))),
         ["cycle de prérequis : civil_3 → civil_9"], [], True),
        ("palier manquant", _remove("militaire_9"), ["manquant(s) : militaire_9"], [], True),
        ("stat inconnue", _edit("civil_1", lambda d: d["rewards"][0].update(stat="bonheur")),
         ["bonus inconnu « bonheur »"], [], True),
        ("clé de déblocage hors catalogue",
         _edit("civil_5", lambda d: d["rewards"][0].update(key="storage.expansion_9")),
         ["« storage.expansion_9 » hors catalogue"], [], True),
        ("écart au plan (montant d'une récompense)",
         _edit("civil_3", lambda d: d["rewards"][0].update(amount=0.15)),
         ["écart au plan (récompenses)"], [], True),
        ("écart au plan (prérequis croisé retiré)",
         _edit("technologie_7", lambda d: d["requires"].remove(_req("militaire_10"))),
         ["écart au plan (prérequis croisés)"], [], True),
        ("écart au plan (population)", _edit("civil_4", lambda d: d.update(min_population=9)),
         ["écart au plan (population minimale) : JSON 9, plan 3"], [], True),
        ("écart au plan (signe du bonus)",
         _edit("civil_2", lambda d: d["rewards"][0].update(amount=0.05)),
         ["écart au plan (récompenses)"], [], True),
        ("prérequis redondant (palier précédent)",
         _edit("civil_5", lambda d: d.setdefault("requires", []).append(_req("civil_4"))),
         ["redondant"], [], True),
        ("prérequis inexistant",
         _edit("civil_5", lambda d: d.setdefault("requires", []).append(_req("civil_99"))),
         ["« sovereignfrontier:civil_99 » inexistant"], [], True),
        ("prérequis sans namespace", _edit("civil_5", lambda d: d.setdefault("requires", []).append("civil_4")),
         ["forme attendue"], [], True),
        ("palier qui se requiert lui-même",
         _edit("civil_5", lambda d: d.setdefault("requires", []).append(_req("civil_5"))),
         ["se requiert lui-même"], [], True),
        ("nom de fichier ≠ contenu (et doublon)", _edit("civil_2", lambda d: d.update(tier=3)),
         ["nom de fichier incorrect", "civil_3 défini par plusieurs fichiers"], [], True),
        ("champ inconnu", _edit("civil_2", lambda d: d.update(cout={})), ["champ inconnu « cout »"], [], True),
        ("type incorrect (tier en chaîne)", _edit("civil_2", lambda d: d.update(tier="2")),
         ["« tier » doit être un entier"], [], True),
        ("booléen refusé comme quantité", _edit("civil_2", lambda d: d["cost"]["items"][0].update(count=True)),
         ["count doit être un entier > 0"], [], True),
        ("quantité nulle", _edit("civil_2", lambda d: d["cost"]["items"][0].update(count=0)),
         ["count doit être un entier > 0"], [], True),
        ("namespace d'objet interdit",
         _edit("civil_2", lambda d: d["cost"]["items"].append({"item": "create:brass_ingot", "count": 4})),
         ["namespace « create » interdit"], [], True),
        ("identifiant d'objet sans namespace",
         _edit("civil_2", lambda d: d["cost"]["items"].append({"item": "stone", "count": 4})),
         ["forme namespace:chemin"], [], True),
        ("objet du mod non enregistré",
         _edit("civil_2", lambda d: d["cost"]["items"].append({"item": f"{NAMESPACE}:plutonium", "count": 1})),
         ["non enregistré"], [], True),
        ("objet introuvable en survie",
         _edit("civil_2", lambda d: d["cost"]["items"].append({"item": "minecraft:bedrock", "count": 1})),
         ["introuvable en survie"], [], True),
        ("trop de piles", _edit("civil_2", lambda d: d["cost"]["items"][0].update(count=64 * 30 + 1)),
         ["piles de 64 au total"], [], True),
        ("points de recherche sous le palier 6", _edit("civil_2", lambda d: d["cost"].update(points=5)),
         ["cost.points = 5 : 0 attendu"], [], True),
        ("points de recherche hors règle", _edit("civil_6", lambda d: d["cost"].update(points=4)),
         ["cost.points = 4 : 5 attendu"], [], True),
        ("points de recherche absents (palier ≥ 6)", _edit("militaire_16", lambda d: d["cost"].pop("points")),
         ["cost.points absent : 55 attendu"], [], True),
        ("points de recherche négatifs", _edit("industrie_12", lambda d: d["cost"].update(points=-35)),
         ["cost.points doit être un entier ≥ 0"], [], True),
        ("JSON invalide", _raw("civil_2.json", '{ "domain": "civil", '), ["JSON invalide"], [], True),
        ("clé JSON en double", _raw("civil_2.json", '{"domain": "civil", "tier": 2, "tier": 2}'),
         ["« tier » en double"], [], True),
        ("domaine inconnu", _raw("chimie_1.json", '{"domain": "chimie", "tier": 1}'),
         ["domaine inconnu « chimie »"], [], True),
        ("palier hors limites", _raw("technologie_8.json", '{"domain": "technologie", "tier": 8}'),
         ["palier 8 hors limites pour technologie"], [], True),
        ("même déblocage accordé deux fois",
         _edit("civil_10", lambda d: d["rewards"][0].update(key="storage.expansion_1")),
         ["déjà débloquée par civil_5"], [], True),
        ("montant de bonus nul", _edit("civil_1", lambda d: d["rewards"][0].update(amount=0)),
         ["non nul"], [], True),
        ("champ inutile sur un déblocage", _edit("civil_5", lambda d: d["rewards"][0].update(amount=1)),
         ["inutile pour un déblocage"], [], True),
        ("type de récompense inconnu", _edit("civil_5", lambda d: d["rewards"][0].update(type="unlocks")),
         ["type \"unlocks\" inconnu"], [], True),
        ("aucune récompense", _edit("civil_5", lambda d: d.update(rewards=[])),
         ["aucune récompense"], [], True),
        ("trésorerie hors barème : avertissement seulement",
         _edit("civil_6", lambda d: d["cost"].update(treasury=d["cost"]["treasury"] * 3)),
         [], ["éloignée du barème"], True),
        ("trésorerie non arrondie : avertissement seulement",
         _edit("civil_6", lambda d: d["cost"].update(treasury=d["cost"]["treasury"] + 7)),
         [], ["non arrondie à 50"], True),
        ("durée explicite : avertissement seulement", _edit("civil_6", lambda d: d.update(duration_seconds=60)),
         [], ["durée explicite"], True),
        ("description manquante", _edit_lang(lambda d: d.pop(f"{PREFIX}research.civil_1.desc")),
         ["research.civil_1.desc"], [], True),
        ("nom traduit ≠ plan", _edit_lang(lambda d: d.update({f"{PREFIX}research.civil_2": "Cadastres"})),
         ["le plan nomme ce palier « Cadastre »"], [], True),
        ("nom de déblocage manquant", _edit_lang(lambda d: d.pop(f"{PREFIX}unlock.refinery")),
         ["unlock.refinery"], [], True),
        ("--no-lang ignore les traductions", _edit_lang(lambda d: d.pop(f"{PREFIX}research.civil_1.desc")),
         [], [], False),
    ]

    with tempfile.TemporaryDirectory(prefix="check_research_") as tmp:
        base = Path(tmp) / "base"
        try:
            _build_fixture(base)
        except RuntimeError as e:
            print(f"ERREUR : {e}")
            return 1
        rep = check(base)
        expect("arbre de référence (généré depuis le plan) accepté sans avertissement",
               rep.ok and not rep.warnings, format_report(rep))
        expect("65 paliers comptés", rep.tiers == sum(DOMAINS.values()) == 65, str(rep.tiers))
        expect("message « OK : 65 paliers valides »", format_report(rep).startswith("OK : 65 paliers valides"),
               format_report(rep))
        expect("prérequis croisés du plan comptés", rep.cross == sum(len(r.requires) for r in
                                                                     parse_plan((base / PLAN_REL).read_text(
                                                                         encoding="utf-8")).rows.values()),
               str(rep.cross))

        for n, (label, mutate, errors, warnings, with_lang) in enumerate(cases, 1):
            root = Path(tmp) / f"cas_{n:02d}"
            shutil.copytree(base, root)
            mutate(root)
            r = check(root, lang=with_lang)
            err_text, warn_text = r.text(), r.text(r.warnings)
            if errors:
                missing = [f for f in errors if f not in err_text]
                expect(f"{label} : détecté", not r.ok and not missing,
                       f"fragments absents : {missing}\n{format_report(r)}")
            else:
                expect(f"{label} : aucune erreur", r.ok, format_report(r))
            missing_w = [f for f in warnings if f not in warn_text]
            if warnings:
                expect(f"{label} : avertissement émis", not missing_w,
                       f"fragments absents : {missing_w}\n{format_report(r)}")

    print()
    if failures:
        print(f"Auto-tests : {len(failures)} échec(s)")
        for f in failures:
            print("-" * 60)
            print(f)
        return 1
    print("Auto-tests : tous réussis")
    return 0


# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(
        description="Vérifie l'arbre de recherche (data/sovereignfrontier/research/*.json) "
                    "contre le plan docs/recherche.md.")
    parser.add_argument("--no-lang", action="store_true",
                        help="ne pas vérifier les traductions (en_us.json)")
    parser.add_argument("--self-test", action="store_true",
                        help="exécute les tests intégrés sur un arbre temporaire")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    rep = check(REPO_ROOT, lang=not args.no_lang)
    print(format_report(rep))
    return 0 if rep.ok else 1


if __name__ == "__main__":
    sys.exit(main())
