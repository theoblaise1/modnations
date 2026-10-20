#!/usr/bin/env python3
"""Vérifie le contenu de l'économie (jalon J4) et l'interdiction absolue des objets de palier 3/4.

Contrôles du socle (code de sortie 1 en cas d'erreur ; les avertissements ne font pas échouer) :
  1. tags d'objets du dépôt (``data/<ns>/tags/item/``) : JSON strict, format ``{"values": [...]}``,
     résolution récursive sans cycle, tags ``#sovereignfrontier:`` existants, objets ``sovereignfrontier:``
     enregistrés (registry/ModItems.java, ModBlocks.java) ;
  2. tags d'interdiction présents : ``restreint`` et ses sous-tags ``palier_3``, ``palier_4``,
     ``fusee_finale``, ``arme_max``, ``hors_economie``, ``interdit_caisses`` ; ``restreint`` inclut les
     4 sous-tags, ``interdit_caisses`` inclut ``#restreint`` et ``#hors_economie`` ; aucune référence à un
     tag d'un autre espace de noms (``#minecraft:…``) dans ces tags, même indirecte ;
  3. recettes (``data/*/recipe/``) et recettes d'ingénierie (``data/<ns>/engineering/``) : objets
     connus, aucun résultat de ``#restreint``, aucune recette de clé de caisse ;
  4. modules de contenu (``tools/economy_checks/``) : marchands, caisses, /daily, contrats, entreprises.

Le dépôt est localisé à partir de l'emplacement du script (racine = parent de ``tools/``).

Usage :
    python3 tools/check_economy.py              # vérification complète
    python3 tools/check_economy.py --self-test  # tests intégrés sur un dépôt temporaire
"""

from __future__ import annotations

import argparse
import importlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

from economy_checks import MODULES
from economy_checks.common import (
    BANNED_FROM_LOOT, CRATE_KIND_REL, DATA_REL, ECONOMY_TAGS, JAVA_REL, NAMESPACE, NS_DATA_REL, REGISTRY_FILES,
    REQUIRED_INCLUDES, RESTRICTED, Context, build_context, show,
)

REPO_ROOT = Path(__file__).resolve().parent.parent


# ---------------------------------------------------------------------------
# Contrôles du socle
# ---------------------------------------------------------------------------

def check_economy_tags(ctx: Context) -> None:
    for tag in ECONOMY_TAGS:
        subject = f"#{tag}"
        if tag not in ctx.raw_tags:
            ctx.error(subject, f"tag manquant : data/{NAMESPACE}/tags/item/{tag.split(':', 1)[1]}.json")
            continue
        ext = ctx.external_refs.get(tag, frozenset())
        if ext:
            ctx.error(subject, "référence(s) à un tag externe interdite(s) (objets explicites seulement) : "
                      + ", ".join(f"#{e}" for e in sorted(ext)))
    for tag, includes in REQUIRED_INCLUDES.items():
        values = ctx.raw_tags.get(tag)
        if values is None:
            continue
        direct = {v.get("id") if isinstance(v, dict) else v for v in values}
        for inc in includes:
            if f"#{inc}" not in direct:
                ctx.error(f"#{tag}", f"doit inclure « #{inc} »")
    keys = ctx.key_items() & ctx.tags.get(BANNED_FROM_LOOT, frozenset())
    for key in sorted(keys):
        ctx.warn(f"#{BANNED_FROM_LOOT}", f"la clé « {key} » est interdite dans les sources automatiques")


def _result_id(data: object) -> object:
    """Identifiant du résultat d'une recette (formats 1.21 « result.id », ancien « result.item » ou chaîne)."""
    if not isinstance(data, dict) or "result" not in data:
        return None
    result = data["result"]
    if isinstance(result, dict):
        return result.get("id", result.get("item"))
    return result


def _check_ids(ctx: Context, node: object, subject: str, where: str) -> None:
    """Parcourt une recette : objets (« item », « id ») connus, tags (« tag ») existants."""
    if isinstance(node, dict):
        for k, v in node.items():
            here = f"{where}.{k}" if where else k
            if k in ("item", "id") and isinstance(v, str):
                ctx.check_item_id(v, subject, here)
            elif k == "tag" and isinstance(v, str):
                ctx.check_tag_ref(v, subject, here)
            else:
                _check_ids(ctx, v, subject, here)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            _check_ids(ctx, v, subject, f"{where}[{i}]")


def _check_result(ctx: Context, result: object, subject: str, kind: str) -> None:
    if not isinstance(result, str):
        return
    if result in ctx.key_items():
        ctx.error(subject, f"{kind} de clé de caisse « {result} » : les clés ne se fabriquent jamais")
    if ctx.restricted(result):
        ctx.error(subject, f"{kind} d'un objet restreint « {result} » (#{RESTRICTED}) : interdit dans tout craft")


def check_recipes(ctx: Context) -> int:
    count = 0
    for path in ctx.data_files("recipe", namespace=None):
        data = ctx.load(path)
        if data is None:
            continue
        count += 1
        subject = ctx.rel(path)
        _check_ids(ctx, data, subject, "")
        _check_result(ctx, _result_id(data), subject, "recette")
    return count


def check_engineering(ctx: Context) -> int:
    count = 0
    for path in ctx.data_files("engineering"):
        data = ctx.load(path)
        if data is None:
            continue
        count += 1
        subject = ctx.rel(path)
        if not isinstance(data, dict) or not isinstance(data.get("result"), dict):
            ctx.error(subject, "recette d'ingénierie sans « result » {item, count}")
            continue
        _check_ids(ctx, data, subject, "")
        _check_result(ctx, data["result"].get("item"), subject, "recette d'ingénierie")
    return count


def check(root: Path) -> tuple[Context, dict[str, int]]:
    ctx = build_context(root)
    stats = {"tags": len(ctx.raw_tags)}
    check_economy_tags(ctx)
    stats["recettes"] = check_recipes(ctx)
    stats["ingénierie"] = check_engineering(ctx)
    for name in MODULES:
        module = importlib.import_module(f"economy_checks.{name}")
        found = module.check(ctx)
        if not isinstance(found, list):
            ctx.error(f"economy_checks/{name}.py", "check() doit renvoyer une liste d'erreurs (sujet, message)")
            continue
        ctx.errors.extend(found)
    return ctx, stats


def format_report(ctx: Context, stats: dict[str, int]) -> str:
    lines: list[str] = []
    for title, entries in (("ERREUR", ctx.errors), ("AVERTISSEMENT", ctx.warnings)):
        for subject, msg in entries:
            lines.append(f"{title} : {subject + ' : ' if subject else ''}{msg}")
    summary = ", ".join(f"{v} {k}" for k, v in stats.items())
    restricted = len(ctx.tags.get(RESTRICTED, frozenset()))
    banned = len(ctx.tags.get(BANNED_FROM_LOOT, frozenset()))
    if ctx.errors:
        lines.append(f"ÉCHEC : {len(ctx.errors)} erreur(s), {len(ctx.warnings)} avertissement(s) ({summary}).")
    else:
        lines.append(f"OK : {summary} vérifiés ; #restreint : {restricted} objet(s), #interdit_caisses : {banned} "
                     f"objet(s) ; {len(ctx.warnings)} avertissement(s).")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Tests intégrés (--self-test)
# ---------------------------------------------------------------------------

TAG_REL = NS_DATA_REL / "tags" / "item"


def _write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def _build_fixture(root: Path) -> None:
    """Dépôt minimal conforme : registre, CrateKind, tags d'interdiction, une recette et une recette d'ingénierie."""
    blocks, items = (root / rel for rel in REGISTRY_FILES)
    blocks.parent.mkdir(parents=True, exist_ok=True)
    blocks.write_text('class ModBlocks { Object A = BLOCKS.registerBlock("terminal_bancaire", null);\n'
                      '  Object B = machine("generateur_thermique", null);\n'
                      '  private static DeferredBlock<MachineBlock> machine(String id, MachineKind kind) { return null; } }\n',
                      encoding="utf-8")
    items.write_text('class ModItems { Object A = ITEMS.registerSimpleItem("cle_commune", null);\n'
                     '  Object B = ITEMS.registerSimpleItem("cle_militaire", null);\n'
                     '  Object C = ITEMS.registerSimpleItem("circuit"); }\n', encoding="utf-8")
    kind = root / CRATE_KIND_REL
    kind.parent.mkdir(parents=True, exist_ok=True)
    kind.write_text('public enum CrateKind {\n    COMMUNE("commune"),\n    MILITAIRE("militaire");\n}\n', encoding="utf-8")
    ns = NAMESPACE
    _write_json(root / TAG_REL / "restreint.json",
                {"values": [f"#{ns}:restreint/palier_3", f"#{ns}:restreint/palier_4", f"#{ns}:restreint/fusee_finale",
                            f"#{ns}:restreint/arme_max"]})
    for sub in ("palier_3", "palier_4", "fusee_finale", "arme_max"):
        _write_json(root / TAG_REL / "restreint" / f"{sub}.json", {"values": []})
    _write_json(root / TAG_REL / "hors_economie.json",
                {"values": ["minecraft:elytra", {"id": "minecraft:mace", "required": False}, f"{ns}:generateur_thermique"]})
    _write_json(root / TAG_REL / "interdit_caisses.json", {"values": [f"#{ns}:restreint", f"#{ns}:hors_economie"]})
    _write_json(root / NS_DATA_REL / "recipe" / "terminal_bancaire.json",
                {"type": "minecraft:crafting_shaped", "key": {"I": {"item": "minecraft:iron_ingot"},
                                                              "P": {"tag": "minecraft:planks"}},
                 "pattern": ["IPI"], "result": {"count": 1, "id": f"{ns}:terminal_bancaire"}})
    _write_json(root / NS_DATA_REL / "engineering" / "circuit.json",
                {"tab": "machines", "ingredients": [{"item": "minecraft:copper_ingot", "count": 2}],
                 "result": {"item": f"{ns}:circuit", "count": 1}})


def _edit_json(rel: Path, fn):
    def mutate(root: Path) -> None:
        path = root / rel
        data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        fn(data)
        _write_json(path, data)
    return mutate


def _tag(name: str, fn):
    return _edit_json(TAG_REL / f"{name}.json", fn)


def _remove(rel: Path):
    def mutate(root: Path) -> None:
        (root / rel).unlink()
    return mutate


def _raw(rel: Path, content: str):
    def mutate(root: Path) -> None:
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_text(content, encoding="utf-8")
    return mutate


def _recipe(name: str, result: str, folder: str = "recipe", namespace: str = NAMESPACE):
    rel = DATA_REL / namespace / folder / f"{name}.json"
    if folder == "engineering":
        data = {"tab": "machines", "ingredients": [{"item": "minecraft:iron_ingot", "count": 1}],
                "result": {"item": result, "count": 1}}
    else:
        data = {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": "minecraft:iron_ingot"}],
                "result": {"count": 1, "id": result}}
    return _edit_json(rel, lambda d: d.update(data))


def _both(*mutations):
    def mutate(root: Path) -> None:
        for m in mutations:
            m(root)
    return mutate


def _register_item(name: str):
    """Enregistre un objet de plus dans la ModItems du dépôt temporaire."""
    def mutate(root: Path) -> None:
        path = root / REGISTRY_FILES[1]
        src = path.read_text(encoding="utf-8")
        path.write_text(src.replace("class ModItems {", f'class ModItems {{ Object X_{name} = ITEMS.registerSimpleItem("{name}");\n', 1),
                        encoding="utf-8")
    return mutate


def _contract(name: str, **parts):
    """Modèle de contrat de livraison minimal (``parts`` remplace des champs : objective, reward, kind…)."""
    data = {"kind": "deliver", "duration_hours": 24,
            "objective": {"item": "minecraft:iron_ingot", "amount": {"min": 1, "max": 4}},
            "reward": {"money": 10}}
    data.update(parts)
    return _edit_json(NS_DATA_REL / "contracts" / f"{name}.json", lambda d: d.update(data))


def _module_reward(name: str):
    """Contrat dont la récompense est le composant final de fusée (le module est enregistré et dans ``fusee_finale``)."""
    return _both(_register_item("module_cryogenique"),
                 _tag("restreint/fusee_finale", lambda d: d["values"].append(f"{NAMESPACE}:module_cryogenique")),
                 _contract(name, reward={"money": 10, "items": [{"item": f"{NAMESPACE}:module_cryogenique", "count": 1}]}))


def _self_test() -> int:
    failures: list[str] = []
    ns = NAMESPACE

    def expect(label: str, cond: bool, detail: str = "") -> None:
        print(f"  [{'ok' if cond else 'ÉCHEC'}] {label}")
        if not cond:
            failures.append(f"{label}\n{detail}")

    cases = [
        # (libellé, mutation, fragments d'erreur attendus)
        ("tag vanilla dans hors_economie", _tag("hors_economie", lambda d: d["values"].append("#minecraft:swords")),
         ["tag externe", "#minecraft:swords"]),
        ("tag vanilla atteint indirectement",
         _both(_tag("annexe", lambda d: d.update(values=["#minecraft:swords"])),
               _tag("restreint/palier_3", lambda d: d["values"].append(f"#{ns}:annexe"))),
         [f"#{ns}:restreint/palier_3", "tag externe"]),
        ("sous-tag absent de restreint", _tag("restreint", lambda d: d["values"].remove(f"#{ns}:restreint/palier_4")),
         [f"doit inclure « #{ns}:restreint/palier_4 »"]),
        ("hors_economie absent de interdit_caisses",
         _tag("interdit_caisses", lambda d: d["values"].remove(f"#{ns}:hors_economie")),
         [f"doit inclure « #{ns}:hors_economie »"]),
        ("tag d'interdiction manquant", _remove(TAG_REL / "restreint" / "fusee_finale.json"),
         ["tag manquant", "fusee_finale", "tag inconnu"]),
        ("objet du mod non enregistré", _tag("hors_economie", lambda d: d["values"].append(f"{ns}:fusee_inconnue")),
         [f"« {ns}:fusee_inconnue » non enregistré"]),
        ("identifiant malformé", _tag("hors_economie", lambda d: d["values"].append("Minecraft:Mauvais Objet")),
         ["identifiant d'objet invalide"]),
        ("cycle de tags", _tag("restreint/palier_3", lambda d: d["values"].append(f"#{ns}:restreint")),
         ["cycle de tags"]),
        ("JSON invalide (clé en double)", _raw(TAG_REL / "restreint" / "arme_max.json", '{"values": [], "values": []}'),
         ["JSON invalide", "en double"]),
        ("recette de clé", _recipe("cle", f"{ns}:cle_commune"), ["recette de clé de caisse"]),
        ("recette d'ingénierie de clé", _recipe("cle", f"{ns}:cle_militaire", folder="engineering"),
         ["recette d'ingénierie de clé de caisse"]),
        ("recette vanilla d'un objet restreint",
         _both(_tag("restreint/palier_4", lambda d: d["values"].append("minecraft:diamond_sword")),
               _recipe("epee", "minecraft:diamond_sword", namespace="minecraft")),
         ["recette d'un objet restreint « minecraft:diamond_sword »"]),
        ("recette d'ingénierie d'un objet restreint",
         _tag("restreint/arme_max", lambda d: d["values"].append(f"{ns}:circuit")),
         ["recette d'ingénierie d'un objet restreint"]),
        ("ingrédient du mod inconnu",
         _edit_json(NS_DATA_REL / "recipe" / "terminal_bancaire.json",
                    lambda d: d["key"].update(X={"item": f"{ns}:inconnu"})),
         [f"« {ns}:inconnu » non enregistré"]),
        ("tag du mod inconnu dans une recette",
         _edit_json(NS_DATA_REL / "recipe" / "terminal_bancaire.json",
                    lambda d: d["key"].update(X={"tag": f"{ns}:inexistant"})),
         [f"tag inconnu « #{ns}:inexistant »"]),
        # Exemption des contrats (J5) : seul #restreint/fusee_finale est permis en récompense de contrat.
        ("contrat : le composant final de fusée est permis en récompense", _module_reward("mission"), None),
        ("contrat : un objet de palier 3 reste interdit en récompense",
         _both(_register_item("lingot_exotique"),
               _tag("restreint/palier_3", lambda d: d["values"].append(f"{ns}:lingot_exotique")),
               _contract("pillage", reward={"money": 10, "items": [{"item": f"{ns}:lingot_exotique", "count": 1}]})),
         [f"objet interdit « {ns}:lingot_exotique »", "sauf"]),
        ("contrat : les élytres restent interdites en récompense",
         _contract("vol", reward={"money": 10, "items": [{"item": "minecraft:elytra", "count": 1}]}),
         ["objet interdit « minecraft:elytra »"]),
        ("caisse : le composant final de fusée reste interdit",
         _both(_module_reward("mission"),
               _edit_json(NS_DATA_REL / "crates" / "commune.json",
                          lambda d: d.update(rolls=1, entries=[{"item": f"{ns}:module_cryogenique", "weight": 5},
                                                               {"id": "r", "money": 10, "rarity": "rare"}]))),
         [f"objet interdit « {ns}:module_cryogenique »", "jamais dans une caisse"]),
        ("contrat : dimension du mod sans planète",
         _contract("lune", kind="explore", objective={"dimension": f"{ns}:lune"}),
         [f"dimension du mod inconnue « {ns}:lune »"]),
        ("contrat : dimension déclarée par une planète",
         _both(_contract("lune", kind="explore", objective={"dimension": f"{ns}:lune"}),
               _edit_json(NS_DATA_REL / "planets" / "lune.json", lambda d: d.update(dimension=f"{ns}:lune"))),
         None),
        ("contrat : dimension vanilla", _contract("nether", kind="explore", objective={"dimension": "minecraft:the_nether"}),
         None),
    ]

    with tempfile.TemporaryDirectory(prefix="check_economy_") as tmp:
        base = Path(tmp) / "base"
        _build_fixture(base)
        ctx, _ = check(base)
        expect("dépôt conforme : aucune erreur", not ctx.errors, format_report(ctx, {}))
        expect("tags résolus (interdit_caisses ⊇ hors_economie)",
               {"minecraft:elytra", "minecraft:mace", f"{ns}:generateur_thermique"} <= ctx.tags.get(BANNED_FROM_LOOT, set()))
        for i, (label, mutate, fragments) in enumerate(cases):
            root = Path(tmp) / f"cas{i}"
            shutil.copytree(base, root)
            mutate(root)
            ctx, stats = check(root)
            text = format_report(ctx, stats)
            errors = "\n".join(f"{s} : {m}" for s, m in ctx.errors)
            if fragments is None:  # cas conforme : aucune erreur attendue
                expect(label, not ctx.errors, text)
            else:
                expect(label, bool(ctx.errors) and all(f in errors for f in fragments), text)

    if failures:
        print(f"\nÉCHEC : {len(failures)} auto-test(s) en échec.")
        for f in failures:
            print("\n--- " + f)
        return 1
    print(f"\nOK : {len(cases) + 2} auto-tests réussis.")
    return 0


# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    parser = argparse.ArgumentParser(description="Vérifie le contenu de l'économie (J4) et les objets interdits.")
    parser.add_argument("--self-test", action="store_true", help="exécute les tests intégrés sur un dépôt temporaire")
    args = parser.parse_args(argv)
    if args.self_test:
        return _self_test()
    ctx, stats = check(REPO_ROOT)
    print(format_report(ctx, stats))
    return 0 if not ctx.errors else 1


if __name__ == "__main__":
    sys.exit(main())
