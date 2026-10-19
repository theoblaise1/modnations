#!/usr/bin/env python3
"""Vérifie le contenu spatial (jalon J5) : planètes ``data/<ns>/planets/*.json`` (docs/conception/J5.md §2.1).

Mêmes règles que le chargement strict du jeu (``space/PlanetDefinition.java`` et ``PlanetRegistry.java``) ; les
bornes sont les constantes du code (§1.11). Contrôles (code de sortie 1 en cas d'erreur) :
  1. planètes : JSON strict (clé en double refusée), champs autorisés uniquement (dimension, requires, outbound,
     return, radius, environment{oxygen, cold}, gas_reserve, solar_multiplier, module, mining{unlock, blocks}),
     types et bornes : radius 256..30 000 ; outbound et return : 1 à 4 options {item, count}, count 1..64, objets
     distincts ; gas_reserve 0..10¹² (facultatif) ; solar_multiplier 0..4 (facultatif) ; requires : 1 à 8 clés
     distinctes ; nom de fichier [a-z0-9_]{1,32}, ni « terre » (retour vers l'Overworld), ni sous-dossier ;
  2. objets : identifiants bien formés, « sovereignfrontier:… » enregistrés (registry/ModItems, ModBlocks) ;
  3. clés de déblocage (requires, mining.unlock) : dans le catalogue de docs/recherche.md ;
  4. tags : mining.blocks est un tag de blocs « #ns:chemin » existant et non vide ; module membre de
     #sovereignfrontier:restreint/fusee_finale (tags d'objets du dépôt, résolus) ;
  5. ensemble : au plus 8 planètes, une dimension par planète (toutes les planètes d'une dimension partagée sont
     rejetées), minecraft:overworld interdit ;
  6. traductions : « sovereignfrontier.planet.<nom> » de chaque planète dans en_us.json ;
  7. dimensions : section du lot WP1 (fichiers dimension/, dimension_type/, worldgen/ de chaque planète).

Le dépôt est localisé à partir de l'emplacement du script (racine = parent de ``tools/``).

Usage :
    python3 tools/check_space.py              # vérification complète
    python3 tools/check_space.py --self-test  # tests intégrés sur un dépôt temporaire
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from check_research import PLAN_REL, parse_plan
from economy_checks.common import (
    DATA_REL, ID_RE, NAMESPACE, NS_DATA_REL, REGISTRY_FILES, Context, is_int, is_number, load_json, load_tags,
    read_registered, show,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
PLANETS_REL = NS_DATA_REL / "planets"
LANG_REL = Path("src/main/resources/assets") / NAMESPACE / "lang" / "en_us.json"
FINAL_ROCKET = f"{NAMESPACE}:restreint/fusee_finale"

# ---------------------------------------------------------------------------
# Constantes de validation (docs/conception/J5.md §1.11), identiques à PlanetDefinition.java : jamais lues dans
# la configuration du serveur (non chargée au premier chargement des datapacks).
# ---------------------------------------------------------------------------
RADIUS = (256, 30_000)
OPTIONS = (1, 4)
COUNT = (1, 64)
GAS_RESERVE = (0, 10 ** 12)
SOLAR = (0.0, 4.0)
REQUIRES = (1, 8)
MAX_PLANETS = 8
EARTH = "terre"
OVERWORLD = "minecraft:overworld"

FIELDS = ("dimension", "requires", "outbound", "return", "radius", "environment", "gas_reserve", "solar_multiplier",
          "module", "mining")
REQUIRED = ("dimension", "requires", "outbound", "return", "radius", "environment")
OPTION_FIELDS = ("item", "count")
ENVIRONMENT_FIELDS = ("oxygen", "cold")
MINING_FIELDS = ("unlock", "blocks")
NAME_RE = re.compile(r"^[a-z0-9_]{1,32}$")
UNLOCK_KEY_RE = re.compile(r"^[a-z0-9_]+(?:\.[a-z0-9_]+)*$")


# ---------------------------------------------------------------------------
# Contexte
# ---------------------------------------------------------------------------

@dataclass
class Planet:
    name: str
    file: str
    dimension: str | None


@dataclass
class SpaceContext:
    root: Path
    registered: set[str] | None = None
    catalogue: set[str] | None = None
    item_tags: dict[str, frozenset[str]] = field(default_factory=dict)
    block_tags: dict[str, frozenset[str]] = field(default_factory=dict)
    lang: dict | None = None
    planets: list[Planet] = field(default_factory=list)
    errors: list[tuple[str, str]] = field(default_factory=list)
    warnings: list[tuple[str, str]] = field(default_factory=list)

    def error(self, subject: str, msg: str) -> None:
        self.errors.append((subject, msg))

    def warn(self, subject: str, msg: str) -> None:
        self.warnings.append((subject, msg))

    def rel(self, path: Path) -> str:
        try:
            return path.relative_to(self.root).as_posix()
        except ValueError:
            return path.as_posix()


def load_block_tags(ctx: SpaceContext) -> None:
    """Tags de blocs du dépôt (``data/<ns>/tags/block/``), résolus récursivement ; un tag externe est opaque."""
    raw: dict[str, list] = {}
    base = ctx.root / DATA_REL
    for ns_dir in sorted(p for p in base.glob("*") if p.is_dir()) if base.is_dir() else []:
        folder = ns_dir / "tags" / "block"
        for path in sorted(folder.rglob("*.json")) if folder.is_dir() else []:
            tag = f"{ns_dir.name}:{path.relative_to(folder).with_suffix('').as_posix()}"
            try:
                data = load_json(path)
            except (ValueError, OSError) as e:
                ctx.error(ctx.rel(path), f"JSON invalide : {e}")
                continue
            if not isinstance(data, dict) or not isinstance(data.get("values"), list):
                ctx.error(ctx.rel(path), "objet {\"values\": [...]} attendu")
                continue
            raw[tag] = data["values"]

    done: dict[str, frozenset[str]] = {}

    def resolve(tag: str, stack: list[str]) -> frozenset[str]:
        if tag in done:
            return done[tag]
        if tag in stack:
            ctx.error(f"#{tag}", "cycle de tags de blocs : " + " → ".join(stack[stack.index(tag):] + [tag]))
            return frozenset()
        stack.append(tag)
        out: set[str] = set()
        for entry in raw[tag]:
            value = entry.get("id") if isinstance(entry, dict) else entry
            if not isinstance(value, str):
                continue
            if value.startswith("#"):
                ref = value[1:]
                if ref in raw:
                    out |= resolve(ref, stack)
                elif not ref.startswith(NAMESPACE + ":"):
                    out.add(value)  # tag externe (vanilla) : opaque, non vide par hypothèse
                else:
                    ctx.error(f"#{tag}", f"tag de blocs inconnu « {value} »")
            else:
                ns, _, name = value.partition(":")
                if ns == NAMESPACE and ctx.registered is not None and name not in ctx.registered:
                    ctx.error(f"#{tag}", f"bloc « {value} » non enregistré (registry/ModBlocks)")
                out.add(value)
        stack.pop()
        done[tag] = frozenset(out)
        return done[tag]

    for tag in raw:
        resolve(tag, [])
    ctx.block_tags = done


def build_context(root: Path) -> SpaceContext:
    ctx = SpaceContext(root=root)
    ctx.registered = read_registered(root)
    if ctx.registered is None:
        ctx.warn("", "registre introuvable (registry/ModBlocks.java, ModItems.java) : objets non vérifiés")
    plan_path = root / PLAN_REL
    if plan_path.is_file():
        plan = parse_plan(plan_path.read_text(encoding="utf-8"))
        ctx.catalogue = plan.catalogue or None
    if ctx.catalogue is None:
        ctx.error(PLAN_REL.as_posix(), "catalogue des déblocages introuvable (tableau « ## Déblocages »)")
    # Tags d'objets : résolus par le socle de l'économie (ses erreurs sont signalées par tools/check_economy.py).
    economy = Context(root=root, registered=ctx.registered)
    load_tags(economy)
    ctx.item_tags = economy.tags
    load_block_tags(ctx)
    lang_path = root / LANG_REL
    try:
        ctx.lang = load_json(lang_path) if lang_path.is_file() else None
    except (ValueError, OSError) as e:
        ctx.error(LANG_REL.as_posix(), f"JSON invalide : {e}")
    if ctx.lang is None:
        ctx.error(LANG_REL.as_posix(), "fichier de langue introuvable ou invalide")
    return ctx


# ---------------------------------------------------------------------------
# Planètes
# ---------------------------------------------------------------------------

def _check_item(ctx: SpaceContext, value: object, subject: str, where: str) -> str | None:
    if not isinstance(value, str) or not ID_RE.match(value):
        ctx.error(subject, f"{where} : identifiant d'objet invalide {show(value)}")
        return None
    ns, name = value.split(":", 1)
    if value == "minecraft:air":
        ctx.error(subject, f"{where} : « minecraft:air » interdit")
        return None
    if ns == NAMESPACE and ctx.registered is not None and name not in ctx.registered:
        ctx.error(subject, f"{where} : objet « {value} » non enregistré (registry/ModItems, ModBlocks)")
        return None
    return value


def _check_key(ctx: SpaceContext, value: object, subject: str, where: str) -> str | None:
    if not isinstance(value, str) or not UNLOCK_KEY_RE.match(value):
        ctx.error(subject, f"{where} : clé de déblocage mal formée {show(value)} (identifiant pointé en minuscules)")
        return None
    if ctx.catalogue is not None and value not in ctx.catalogue:
        ctx.error(subject, f"{where} : clé « {value} » hors du catalogue de {PLAN_REL.as_posix()}")
        return None
    return value


def _int_in(ctx: SpaceContext, data: dict, key: str, bounds: tuple[int, int], subject: str, where: str = "") -> None:
    v = data.get(key)
    if not is_int(v) and not (isinstance(v, float) and v.is_integer()) or not bounds[0] <= v <= bounds[1]:
        ctx.error(subject, f"{where}{key} : entier de {bounds[0]} à {bounds[1]} attendu, reçu {show(v)}")


def _check_options(ctx: SpaceContext, data: dict, key: str, subject: str) -> None:
    options = data.get(key)
    if not isinstance(options, list):
        ctx.error(subject, f"{key} : liste attendue, reçu {show(options)}")
        return
    if not OPTIONS[0] <= len(options) <= OPTIONS[1]:
        ctx.error(subject, f"{key} : de {OPTIONS[0]} à {OPTIONS[1]} options de carburant attendues, {len(options)} reçue(s)")
    seen: set[str] = set()
    for i, option in enumerate(options):
        where = f"{key}[{i}]"
        if not isinstance(option, dict):
            ctx.error(subject, f"{where} : objet {{item, count}} attendu")
            continue
        unknown = sorted(set(option) - set(OPTION_FIELDS))
        if unknown:
            ctx.error(subject, f"{where} : champ(s) inconnu(s) : {', '.join(unknown)}")
        if "item" not in option:
            ctx.error(subject, f"{where}.item : champ obligatoire absent")
        else:
            item = _check_item(ctx, option["item"], subject, f"{where}.item")
            if item is not None and item in seen:
                ctx.error(subject, f"{where}.item : objet « {item} » en double")
            if item is not None:
                seen.add(item)
        if "count" not in option:
            ctx.error(subject, f"{where}.count : champ obligatoire absent")
        else:
            _int_in(ctx, option, "count", COUNT, subject, f"{where}.")


def check_planet(ctx: SpaceContext, path: Path, folder: Path) -> Planet | None:
    subject = ctx.rel(path)
    rel = path.relative_to(folder)
    name = rel.with_suffix("").as_posix()
    if path.parent != folder or not NAME_RE.match(name):
        ctx.error(subject, f"nom de fichier « {name} » invalide ([a-z0-9_], 32 caractères au plus, sans sous-dossier)")
        return None
    if name == EARTH:
        ctx.error(subject, f"nom « {EARTH} » réservé au retour vers l'Overworld")
        return None
    try:
        data = load_json(path)
    except (ValueError, OSError) as e:
        ctx.error(subject, f"JSON invalide : {e}")
        return None
    if not isinstance(data, dict):
        ctx.error(subject, "objet JSON attendu")
        return None
    unknown = sorted(set(data) - set(FIELDS))
    if unknown:
        ctx.error(subject, f"champ(s) inconnu(s) : {', '.join(unknown)}")
    for key in REQUIRED:
        if key not in data:
            ctx.error(subject, f"{key} : champ obligatoire absent")

    dimension = data.get("dimension")
    if "dimension" in data:
        if not isinstance(dimension, str) or not ID_RE.match(dimension):
            ctx.error(subject, f"dimension : identifiant invalide {show(dimension)}")
            dimension = None
        elif dimension == OVERWORLD:
            ctx.error(subject, f"dimension : « {OVERWORLD} » ne peut pas être une planète")
            dimension = None

    if "requires" in data:
        requires = data["requires"]
        if not isinstance(requires, list):
            ctx.error(subject, f"requires : liste attendue, reçu {show(requires)}")
        else:
            if not REQUIRES[0] <= len(requires) <= REQUIRES[1]:
                ctx.error(subject, f"requires : de {REQUIRES[0]} à {REQUIRES[1]} clés attendues, {len(requires)} reçue(s)")
            seen: set[str] = set()
            for i, key in enumerate(requires):
                valid = _check_key(ctx, key, subject, f"requires[{i}]")
                if valid is not None and valid in seen:
                    ctx.error(subject, f"requires[{i}] : clé « {valid} » en double")
                if valid is not None:
                    seen.add(valid)

    for key in ("outbound", "return"):
        if key in data:
            _check_options(ctx, data, key, subject)
    if "radius" in data:
        _int_in(ctx, data, "radius", RADIUS, subject)

    if "environment" in data:
        env = data["environment"]
        if not isinstance(env, dict):
            ctx.error(subject, f"environment : objet {{oxygen, cold}} attendu, reçu {show(env)}")
        else:
            unknown = sorted(set(env) - set(ENVIRONMENT_FIELDS))
            if unknown:
                ctx.error(subject, f"environment : champ(s) inconnu(s) : {', '.join(unknown)}")
            for key in ENVIRONMENT_FIELDS:
                if not isinstance(env.get(key), bool):
                    ctx.error(subject, f"environment.{key} : booléen attendu, reçu {show(env.get(key))}")

    if "gas_reserve" in data:
        _int_in(ctx, data, "gas_reserve", GAS_RESERVE, subject)
    if "solar_multiplier" in data:
        v = data["solar_multiplier"]
        if not is_number(v) or not SOLAR[0] <= v <= SOLAR[1]:
            ctx.error(subject, f"solar_multiplier : nombre de {SOLAR[0]} à {SOLAR[1]} attendu, reçu {show(v)}")

    if "module" in data:
        module = _check_item(ctx, data["module"], subject, "module")
        if module is not None and module not in ctx.item_tags.get(FINAL_ROCKET, frozenset()):
            ctx.error(subject, f"module « {module} » hors de #{FINAL_ROCKET}")

    if "mining" in data:
        mining = data["mining"]
        if not isinstance(mining, dict):
            ctx.error(subject, f"mining : objet {{unlock, blocks}} attendu, reçu {show(mining)}")
        else:
            unknown = sorted(set(mining) - set(MINING_FIELDS))
            if unknown:
                ctx.error(subject, f"mining : champ(s) inconnu(s) : {', '.join(unknown)}")
            if "unlock" not in mining:
                ctx.error(subject, "mining.unlock : champ obligatoire absent")
            else:
                _check_key(ctx, mining["unlock"], subject, "mining.unlock")
            blocks = mining.get("blocks")
            if not isinstance(blocks, str) or not blocks.startswith("#") or not ID_RE.match(blocks[1:]):
                ctx.error(subject, f"mining.blocks : tag de blocs « #espace:chemin » attendu, reçu {show(blocks)}")
            elif blocks[1:] not in ctx.block_tags:
                ctx.error(subject, f"mining.blocks : tag de blocs « {blocks} » inconnu")
            elif not ctx.block_tags[blocks[1:]]:
                ctx.error(subject, f"mining.blocks : tag de blocs « {blocks} » vide")

    if ctx.lang is not None:
        key = f"{NAMESPACE}.planet.{name}"
        value = ctx.lang.get(key)
        if not isinstance(value, str) or not value.strip():
            ctx.error(subject, f"traduction « {key} » absente de {LANG_REL.name}")
    return Planet(name, subject, dimension if isinstance(dimension, str) else None)


def check_planets(ctx: SpaceContext) -> None:
    folder = ctx.root / PLANETS_REL
    if not folder.is_dir():
        ctx.error("", f"dossier introuvable : {PLANETS_REL.as_posix()}")
        return
    for path in sorted(p for p in folder.rglob("*") if p.is_file()):
        if path.suffix != ".json":
            ctx.warn(ctx.rel(path), "fichier non JSON, ignoré par le jeu")
            continue
        planet = check_planet(ctx, path, folder)
        if planet is not None:
            ctx.planets.append(planet)
    per_dimension: dict[str, list[Planet]] = {}
    for planet in ctx.planets:
        if planet.dimension:
            per_dimension.setdefault(planet.dimension, []).append(planet)
    for dimension, group in per_dimension.items():
        if len(group) > 1:
            for planet in group:
                others = ", ".join(p.name for p in group if p is not planet)
                ctx.error(planet.file, f"dimension « {dimension} » partagée avec {others} (toutes rejetées par le jeu)")
    if len(ctx.planets) > MAX_PLANETS:
        ctx.error(PLANETS_REL.as_posix(), f"{len(ctx.planets)} planètes (au plus {MAX_PLANETS})")


# ---------------------------------------------------------------------------
# Dimensions
# ---------------------------------------------------------------------------

def check_dimensions(ctx: SpaceContext) -> list[tuple[str, str]]:
    """
    Propriété : WP1. Fichiers dimension/, dimension_type/, worldgen/ de chaque planète (effects, min_y et height
    identiques entre type et bruit, minerai référencé par le biome et ciblant la bonne roche, recettes de fusion).
    Ébauche du socle : aucun contrôle pour l'instant.
    """
    return []


# ---------------------------------------------------------------------------
# Vérification complète
# ---------------------------------------------------------------------------

def check(root: Path) -> SpaceContext:
    ctx = build_context(Path(root))
    check_planets(ctx)
    found = check_dimensions(ctx)
    if not isinstance(found, list):
        ctx.error("check_dimensions", "doit renvoyer une liste d'erreurs (sujet, message)")
    else:
        ctx.errors.extend(found)
    return ctx


def format_report(ctx: SpaceContext) -> str:
    lines: list[str] = []
    for title, entries in (("ERREUR", ctx.errors), ("AVERTISSEMENT", ctx.warnings)):
        for subject, msg in entries:
            lines.append(f"{title} : {subject + ' : ' if subject else ''}{msg}")
    names = ", ".join(p.name for p in ctx.planets) or "aucune"
    if ctx.errors:
        lines.append(f"ÉCHEC : {len(ctx.errors)} erreur(s), {len(ctx.warnings)} avertissement(s).")
    else:
        lines.append(f"OK : {len(ctx.planets)} planète(s) vérifiée(s) ({names}) ; {len(ctx.warnings)} avertissement(s).")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Tests intégrés (--self-test)
# ---------------------------------------------------------------------------

def _write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _build_fixture(root: Path) -> None:
    """Copie des fichiers lus par le contrôle : planètes, registres, tags, plan de recherche, langue."""
    rels = [PLANETS_REL, NS_DATA_REL / "tags", PLAN_REL, LANG_REL, *REGISTRY_FILES]
    for rel in rels:
        src = REPO_ROOT / rel
        if src.is_dir():
            shutil.copytree(src, root / rel)
        elif src.is_file():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, root / rel)


def _planet(name: str, fn):
    def mutate(root: Path) -> None:
        path = root / PLANETS_REL / f"{name}.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        fn(data)
        _write_json(path, data)
    return mutate


def _copy_planet(source: str, target: str, fn=None):
    def mutate(root: Path) -> None:
        data = json.loads((root / PLANETS_REL / f"{source}.json").read_text(encoding="utf-8"))
        if fn:
            fn(data)
        _write_json(root / PLANETS_REL / f"{target}.json", data)
    return mutate


def _raw(rel: Path, content: str):
    def mutate(root: Path) -> None:
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(content, encoding="utf-8")
    return mutate


def _edit_lang(fn):
    def mutate(root: Path) -> None:
        path = root / LANG_REL
        data = json.loads(path.read_text(encoding="utf-8"))
        fn(data)
        _write_json(path, data)
    return mutate


def _all(*mutations):
    def mutate(root: Path) -> None:
        for m in mutations:
            m(root)
    return mutate


def _many(count: int):
    def mutate(root: Path) -> None:
        data = json.loads((root / PLANETS_REL / "lune.json").read_text(encoding="utf-8"))
        for i in range(count):
            data["dimension"] = f"{NAMESPACE}:essai_{i}"
            _write_json(root / PLANETS_REL / f"essai_{i}.json", data)
        lang = json.loads((root / LANG_REL).read_text(encoding="utf-8"))
        lang.update({f"{NAMESPACE}.planet.essai_{i}": f"Essai {i}" for i in range(count)})
        _write_json(root / LANG_REL, lang)
    return mutate


def _self_test() -> int:
    failures: list[str] = []
    ns = NAMESPACE

    def expect(label: str, cond: bool, detail: str = "") -> None:
        print(f"  [{'ok' if cond else 'ÉCHEC'}] {label}")
        if not cond:
            failures.append(f"{label}\n{detail}")

    if not (REPO_ROOT / PLANETS_REL).is_dir():
        print(f"ERREUR : {PLANETS_REL.as_posix()} introuvable, auto-tests impossibles")
        return 1

    cases = [
        # (libellé, mutation, fragments d'erreur attendus ; liste vide = aucune erreur attendue)
        ("champ inconnu", _planet("lune", lambda d: d.update(couleur=1)), ["champ(s) inconnu(s) : couleur"]),
        ("champ obligatoire absent", _planet("mars", lambda d: d.pop("radius")), ["radius : champ obligatoire absent"]),
        ("rayon 100", _planet("lune", lambda d: d.update(radius=100)), ["radius : entier de 256 à 30000"]),
        ("rayon décimal", _planet("lune", lambda d: d.update(radius=2000.5)), ["radius : entier"]),
        ("rayon booléen", _planet("lune", lambda d: d.update(radius=True)), ["radius : entier"]),
        ("option vide", _planet("lune", lambda d: d.update(outbound=[])), ["outbound : de 1 à 4 options"]),
        ("5 options", _planet("lune", lambda d: d.update(outbound=[{"item": f"{ns}:carburant_fusee", "count": 1},
                                                                    {"item": f"{ns}:bouteille_gaz_pleine", "count": 1},
                                                                    {"item": f"{ns}:bouteille_gaz", "count": 1},
                                                                    {"item": f"{ns}:circuit", "count": 1},
                                                                    {"item": f"{ns}:soufre", "count": 1}])),
         ["outbound : de 1 à 4 options", "5 reçue(s)"]),
        ("quantité 0", _planet("lune", lambda d: d["return"][0].update(count=0)), ["return[0].count : entier de 1 à 64"]),
        ("quantité 65", _planet("mars", lambda d: d["outbound"][0].update(count=65)), ["outbound[0].count"]),
        ("objet en double", _planet("lune", lambda d: d["return"][1].update(item=f"{ns}:carburant_fusee")),
         ["return[1].item : objet « sovereignfrontier:carburant_fusee » en double"]),
        ("objet du mod inconnu", _planet("lune", lambda d: d["return"][0].update(item=f"{ns}:plutonium")),
         ["« sovereignfrontier:plutonium » non enregistré"]),
        ("identifiant malformé", _planet("lune", lambda d: d["return"][0].update(item="Carburant")),
         ["identifiant d'objet invalide"]),
        ("champ inconnu dans une option", _planet("lune", lambda d: d["outbound"][0].update(nbt="{}")),
         ["outbound[0] : champ(s) inconnu(s) : nbt"]),
        ("requires vide", _planet("lune", lambda d: d.update(requires=[])), ["requires : de 1 à 8 clés"]),
        ("9 clés", _planet("lune", lambda d: d.update(requires=["rocket"] * 9)), ["requires : de 1 à 8 clés", "en double"]),
        ("clé hors catalogue", _planet("lune", lambda d: d["requires"].append("dimension.venus")),
         ["clé « dimension.venus » hors du catalogue"]),
        ("clé mal formée", _planet("lune", lambda d: d["requires"].append("Dimension Lune")), ["mal formée"]),
        ("clé de minage hors catalogue", _planet("mars", lambda d: d["mining"].update(unlock="mining.venus")),
         ["mining.unlock : clé « mining.venus » hors du catalogue"]),
        ("tag de minerai sans #", _planet("lune", lambda d: d["mining"].update(blocks=f"{ns}:minerais/lune")),
         ["mining.blocks : tag de blocs « #espace:chemin » attendu"]),
        ("tag de minerai inconnu", _planet("lune", lambda d: d["mining"].update(blocks=f"#{ns}:minerais/venus")),
         ["« #sovereignfrontier:minerais/venus » inconnu"]),
        ("tag de minerai vide", _all(_raw(NS_DATA_REL / "tags" / "block" / "minerais" / "vide.json", '{"values": []}'),
                                     _planet("lune", lambda d: d["mining"].update(blocks=f"#{ns}:minerais/vide"))),
         ["« #sovereignfrontier:minerais/vide » vide"]),
        ("module hors de #restreint/fusee_finale", _planet("planete_glacee", lambda d: d.update(module="minecraft:diamond")),
         ["module « minecraft:diamond » hors de #sovereignfrontier:restreint/fusee_finale"]),
        ("module inconnu", _planet("planete_glacee", lambda d: d.update(module=f"{ns}:module_inconnu")),
         ["« sovereignfrontier:module_inconnu » non enregistré"]),
        ("module retiré du tag", _raw(NS_DATA_REL / "tags" / "item" / "restreint" / "fusee_finale.json", '{"values": []}'),
         ["module « sovereignfrontier:module_cryogenique » hors de"]),
        ("dimension Overworld", _planet("lune", lambda d: d.update(dimension="minecraft:overworld")),
         ["ne peut pas être une planète"]),
        ("dimension partagée", _copy_planet("lune", "lune_bis"),
         ["dimension « sovereignfrontier:lune » partagée avec lune_bis", "partagée avec lune"]),
        ("9 planètes", _many(6), ["9 planètes (au plus 8)"]),
        ("8 planètes acceptées", _many(5), []),
        ("nom réservé « terre »", _copy_planet("lune", "terre", lambda d: d.update(dimension=f"{ns}:essai")),
         ["nom « terre » réservé"]),
        ("sous-dossier", _copy_planet("lune", "sous/essai", lambda d: d.update(dimension=f"{ns}:essai")),
         ["sans sous-dossier"]),
        ("gaz négatif", _planet("lune", lambda d: d.update(gas_reserve=-1)), ["gas_reserve : entier de 0"]),
        ("gaz au-delà de 10¹²", _planet("lune", lambda d: d.update(gas_reserve=10 ** 12 + 1)), ["gas_reserve"]),
        ("gaz facultatif", _planet("lune", lambda d: d.pop("gas_reserve")), []),
        ("solaire 4,5", _planet("lune", lambda d: d.update(solar_multiplier=4.5)), ["solar_multiplier : nombre de 0.0 à 4.0"]),
        ("environnement incomplet", _planet("lune", lambda d: d["environment"].pop("cold")),
         ["environment.cold : booléen attendu"]),
        ("environnement non booléen", _planet("lune", lambda d: d["environment"].update(oxygen=1)),
         ["environment.oxygen : booléen attendu"]),
        ("traduction absente", _edit_lang(lambda d: d.pop(f"{ns}.planet.mars")), ["« sovereignfrontier.planet.mars » absente"]),
        ("JSON invalide", _raw(PLANETS_REL / "mars.json", '{"dimension": '), ["JSON invalide"]),
        ("clé JSON en double", _raw(PLANETS_REL / "mars.json", '{"radius": 3000, "radius": 3000}'), ["en double"]),
    ]

    with tempfile.TemporaryDirectory(prefix="check_space_") as tmp:
        base = Path(tmp) / "base"
        _build_fixture(base)
        ctx = check(base)
        expect("dépôt de référence (copie des planètes réelles) : aucune erreur", not ctx.errors, format_report(ctx))
        expect("3 planètes vérifiées", [p.name for p in ctx.planets] == ["lune", "mars", "planete_glacee"],
               format_report(ctx))
        expect("section « dimensions » (WP1) : ébauche sans erreur", check_dimensions(ctx) == [])
        for i, (label, mutate, fragments) in enumerate(cases):
            root = Path(tmp) / f"cas{i:02d}"
            shutil.copytree(base, root)
            mutate(root)
            ctx = check(root)
            errors = "\n".join(f"{s} : {m}" for s, m in ctx.errors)
            if fragments:
                missing = [f for f in fragments if f not in errors]
                expect(f"{label} : détecté", bool(ctx.errors) and not missing,
                       f"fragments absents : {missing}\n{format_report(ctx)}")
            else:
                expect(f"{label} : accepté", not ctx.errors, format_report(ctx))

    if failures:
        print(f"\nÉCHEC : {len(failures)} auto-test(s) en échec.")
        for f in failures:
            print("\n--- " + f)
        return 1
    print(f"\nOK : {len(cases) + 3} auto-tests réussis.")
    return 0


# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description="Vérifie le contenu spatial (J5) : planètes et dimensions.")
    parser.add_argument("--self-test", action="store_true", help="exécute les tests intégrés sur un dépôt temporaire")
    args = parser.parse_args(argv)
    if args.self_test:
        return _self_test()
    ctx = check(REPO_ROOT)
    print(format_report(ctx))
    return 0 if not ctx.errors else 1


if __name__ == "__main__":
    sys.exit(main())
