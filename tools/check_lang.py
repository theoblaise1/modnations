#!/usr/bin/env python3
"""Vérifie le fichier de langue : chaque clé de traduction utilisée par le code Java existe, et les clés sont
triées (ordre des points de code), sans doublon. Le tri permet aux lots de travail parallèles d'insérer leurs
clés à leur place alphabétique sans conflit de fusion.

Usage : python3 tools/check_lang.py   (code de sortie 1 si des clés manquent, sont en double ou mal ordonnées)

Clés détectées :
  * littérales : Msg.tr/ok/err/info/warn("clé") et Component.translatable("sovereignfrontier.…") ;
  * ternaires : Msg.xxx(cond ? "a" : "b") ;
  * dynamiques : role.<NationRole>, perm.<id NationPermission>, building.<BuildingType>,
    log.<clé de nation.log(...)>, help.nation.<sous-commande>, gui.tab.<id NationTab>,
    l'aide tr("x") de l'écran national (gui.x), les touches (KeyMapping), research.domain.<id>,
    research.stat.<id>, research.state.<statut>, reward.crate.<id CrateKind>,
    enterprise.sector.<id Sector>, contract.kind.<id ContractKind> (si l'énumération existe), machine.<id> et
    tooltip.<id> de chaque MachineKind (premier argument).
    Les noms de paliers et de déblocages (research.<palier>, unlock.<clé>) sont vérifiés par
    tools/check_research.py.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "src/main/java"
LANG = ROOT / "src/main/resources/assets/sovereignfrontier/lang/en_us.json"
PREFIX = "sovereignfrontier."


def read(rel: str) -> str:
    matches = list(JAVA.rglob(rel))
    return matches[0].read_text(encoding="utf-8") if matches else ""


def enum_constants(source: str) -> list[str]:
    body = re.search(r"enum\s+\w+\s*\{(.*?);", source, re.S)
    if not body:
        return []
    return re.findall(r"^\s*([A-Z_]+)\b", body.group(1), re.M)


def enum_ids(source: str) -> list[str]:
    """Identifiants des constantes « NOM("id") » d'une énumération à un seul argument chaîne."""
    return re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', source, re.M)


def order_errors(pairs: list[tuple[str, object]]) -> list[str]:
    """Doublons et clés hors de l'ordre trié (points de code), dans l'ordre du fichier."""
    errors: list[str] = []
    seen: set[str] = set()
    for key, _ in pairs:
        if key in seen:
            errors.append(f"clé en double : {key}")
        seen.add(key)
    keys = [k for k, _ in pairs]
    for prev, cur in zip(keys, keys[1:]):
        if prev > cur:
            errors.append(f"ordre : « {cur} » doit précéder « {prev} »")
    return errors


def main() -> int:
    pairs = json.loads(LANG.read_text(encoding="utf-8"), object_pairs_hook=lambda p: p)
    lang = dict(pairs)
    order = order_errors(pairs)
    if order:
        print(f"ERREUR : {LANG.relative_to(ROOT)} doit être trié, sans doublon ({len(order)} problème(s)) :")
        for e in order[:20]:
            print("  - " + e)
        if len(order) > 20:
            print(f"  … et {len(order) - 20} autre(s)")
        return 1
    used: set[str] = set()
    for f in JAVA.rglob("*.java"):
        src = f.read_text(encoding="utf-8")
        for key in re.findall(r'Msg\.(?:tr|ok|err|info|warn)\("([a-z0-9_.]*[a-z0-9_])"\s*[,)]', src):
            used.add(PREFIX + key)
        for a, b in re.findall(r'Msg\.(?:tr|ok|err|info|warn)\(\w+ \? "([a-z0-9_.]+)" : "([a-z0-9_.]+)"', src):
            used.update({PREFIX + a, PREFIX + b})
        for key in re.findall(r'Component\.translatable\("(sovereignfrontier\.[a-z0-9_.]+)"', src):
            used.add(key)
        helper = re.search(r'Msg\.tr\("([a-z0-9_.]+\.)" \+ key', src)
        if helper:
            # Aide locale tr("x") d'un écran, qui préfixe ses clés (ex. « gui. », « gui.research. »).
            for key in re.findall(r'(?<![\w.])tr\("([a-z0-9_.]*[a-z0-9_])"\s*[,)]', src):
                used.add(PREFIX + helper.group(1) + key)
        for key in re.findall(r'new KeyMapping\(\s*"([a-z0-9_.]+)"', src):
            used.add(key)
        for key in re.findall(r'"(key\.categories\.[a-z0-9_.]+)"', src):
            used.add(key)
        for key in re.findall(r'\.log\("([a-z_]+)"', src):
            used.add(PREFIX + "log." + key)
        for a, b in re.findall(r'\.log\(\w+ \? "([a-z_]+)" : "([a-z_]+)"', src):
            used.update({PREFIX + "log." + a, PREFIX + "log." + b})
    for c in enum_constants(read("NationRole.java")):
        used.add(PREFIX + "role." + c.lower())
    for c in enum_constants(read("BuildingType.java")):
        used.add(PREFIX + "building." + c.lower())
    for did in re.findall(r'^\s*[A-Z_]+\("([a-z_]+)", \d+\)', read("ResearchDomain.java"), re.M):
        used.add(PREFIX + "research.domain." + did)
    for sid in re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', read("ResearchStat.java"), re.M):
        used.add(PREFIX + "research.stat." + sid)
    status = re.search(r'enum Status \{(.*?)\}', read("ResearchManager.java"), re.S)
    if status:
        for c in re.findall(r'([A-Z_]+)', status.group(1)):
            used.add(PREFIX + "research.state." + c.lower())
    for pid in re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', read("NationPermission.java"), re.M):
        used.add(PREFIX + "perm." + pid)
    for tid in re.findall(r'^\s*[A-Z_]+\("([a-z_]+)"\)', read("NationTab.java"), re.M):
        used.add(PREFIX + "gui.tab." + tid)
    # J4 : noms des types de caisse, des secteurs d'entreprise et des types de contrat.
    for kid in enum_ids(read("CrateKind.java")):
        used.add(PREFIX + "reward.crate." + kid)
    for sid in enum_ids(read("Sector.java")):
        used.add(PREFIX + "enterprise.sector." + sid)
    for cid in enum_ids(read("ContractKind.java")):
        used.add(PREFIX + "contract.kind." + cid)
    # J5 : nom et info-bulle de chaque machine (premier argument de MachineKind, « NOM("id", rôle, … ) »).
    for mid in re.findall(r'^\s*[A-Z_]+\("([a-z_]+)",', read("MachineKind.java"), re.M):
        used.update({PREFIX + "machine." + mid, PREFIX + "tooltip." + mid})
    help_list = re.search(r'for \(String sub : List\.of\((.*?)\)\)', read("NationCommand.java"), re.S)
    if help_list:
        for sub in re.findall(r'"([a-z]+)"', help_list.group(1)):
            used.add(PREFIX + "help.nation." + sub)

    missing = sorted(k for k in used if k not in lang)
    if missing:
        print(f"ERREUR : {len(missing)} clé(s) de traduction absente(s) de {LANG.relative_to(ROOT)} :")
        for k in missing:
            print("  - " + k)
        return 1
    unused = sorted(k for k in lang if k.startswith(PREFIX) and k not in used)
    print(f"OK : {len(used)} clé(s) utilisées, toutes traduites ({len(unused)} clé(s) non détectées statiquement).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
