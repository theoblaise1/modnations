#!/usr/bin/env python3
"""Vérification statique « serveur dédié » pour Sovereign Frontier.

Deux contrôles sur tous les fichiers ``.java`` de ``src/main/java`` :

1. Aucun fichier situé HORS du paquet client
   (``io/github/theoblaise1/sovereignfrontier/client/``) ne doit référencer du
   code exclusivement client, que ce soit par ``import`` (y compris
   ``import static``) ou par un nom pleinement qualifié dans le code :
   ``net.minecraft.client.``, ``com.mojang.blaze3d.``,
   ``net.neoforged.neoforge.client.`` et ``org.lwjgl.``.
   Les commentaires (``//`` et ``/* */``), les chaînes, les blocs de texte
   (``\"\"\"``) et les littéraux caractère sont ignorés.

2. Chaque classe de premier niveau du paquet client doit porter
   ``@OnlyIn(Dist.CLIENT)``, ou ``@Mod(..., dist = Dist.CLIENT)``, ou
   ``@EventBusSubscriber(..., value = Dist.CLIENT)``. Les fichiers
   ``package-info.java`` (et ``module-info.java``) sont ignorés pour ce
   contrôle.

Le dépôt est localisé à partir de l'emplacement du script (racine = parent de
``tools/``). Code de sortie : 0 si tout va bien, 1 sinon.

Usage :
    python tools/check_sides.py              # analyse src/main/java
    python tools/check_sides.py --self-test  # exécute les tests intégrés
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_ROOT = REPO_ROOT / "src" / "main" / "java"
CLIENT_PACKAGE = "io.github.theoblaise1.sovereignfrontier.client"

# Préfixes de paquets réservés au client.
FORBIDDEN_PREFIXES = (
    "net.minecraft.client.",
    "com.mojang.blaze3d.",
    "net.neoforged.neoforge.client.",
    "org.lwjgl.",
)

_SKIPPED_FOR_ANNOTATIONS = {"package-info.java", "module-info.java"}


def _prefix_regex(prefixes: tuple[str, ...]) -> re.Pattern[str]:
    """Construit une regex tolérant les espaces autour des points
    (``net . minecraft . client . X`` est du Java valide)."""
    alternatives = []
    for prefix in prefixes:
        parts = [re.escape(p) for p in prefix.rstrip(".").split(".")]
        alternatives.append(r"\s*\.\s*".join(parts))
    return re.compile(
        r"(?<![\w$.])(?:" + "|".join(alternatives) + r")\s*\.\s*[\w$*]+"
        r"(?:\s*\.\s*[\w$*]+)*"
    )


FORBIDDEN_RE = _prefix_regex(FORBIDDEN_PREFIXES)
TOKEN_RE = re.compile(r"(?:[^\W\d]|\$)[\w$]*|\d[\w.]*|\S")
PACKAGE_RE = re.compile(r"(?<![\w$.])package\s+([\w$.\s]+?)\s*;")
CLIENT_DIST_RE = re.compile(r"^(?:(?:[\w$]+\.)*Dist\.)?CLIENT$")


# ---------------------------------------------------------------------------
# Nettoyage du source : commentaires, chaînes et caractères remplacés par des
# espaces (les retours à la ligne sont conservés pour garder les numéros de
# ligne et les positions).
# ---------------------------------------------------------------------------

def strip_comments_and_literals(src: str) -> str:
    out: list[str] = []
    i, n = 0, len(src)

    def blank(segment: str) -> str:
        return "".join(c if c == "\n" else " " for c in segment)

    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            end = src.find("\n", i)
            end = n if end == -1 else end
            out.append(blank(src[i:end]))
            i = end
        elif c == "/" and nxt == "*":
            end = src.find("*/", i + 2)
            end = n if end == -1 else end + 2
            out.append(blank(src[i:end]))
            i = end
        elif src.startswith('"""', i):
            # Bloc de texte (Java 15+) : jusqu'au prochain """ non échappé.
            j = i + 3
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src.startswith('"""', j):
                    j += 3
                    break
                j += 1
            if j <= n and src.startswith('"""', j - 3) and j - 3 >= i + 3:
                out.append('"""' + blank(src[i + 3:j - 3]) + '"""')
            else:  # bloc non terminé : on blanchit jusqu'à la fin
                j = n
                out.append('"""' + blank(src[i + 3:n]))
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            if j < n and src[j] == c:
                out.append(c + blank(src[i + 1:j]) + c)
                i = j + 1
            else:  # littéral non terminé : on s'arrête à la fin de ligne
                j = min(j, n)
                out.append(c + blank(src[i + 1:j]))
                i = j
        else:
            out.append(c)
            i += 1
    cleaned = "".join(out)
    assert len(cleaned) == n, "le nettoyage doit préserver les positions"
    return cleaned


# ---------------------------------------------------------------------------
# Résultats
# ---------------------------------------------------------------------------

@dataclass
class Violation:
    path: Path
    line: int
    message: str
    context: str = ""


@dataclass
class Report:
    files: int = 0
    client_types: int = 0
    references: list[Violation] = field(default_factory=list)
    annotations: list[Violation] = field(default_factory=list)
    packages: list[Violation] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not (self.references or self.annotations or self.packages)


def _line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


# ---------------------------------------------------------------------------
# Contrôle 1 : références client hors du paquet client
# ---------------------------------------------------------------------------

def find_forbidden_references(cleaned: str) -> list[tuple[int, str]]:
    found = []
    for m in FORBIDDEN_RE.finditer(cleaned):
        ref = re.sub(r"\s+", "", m.group(0))
        found.append((_line_of(cleaned, m.start()), ref))
    return found


# ---------------------------------------------------------------------------
# Contrôle 2 : annotations des classes de premier niveau du paquet client
# ---------------------------------------------------------------------------

@dataclass
class Annotation:
    name: str  # nom simple (dernier segment)
    args: str  # texte brut (nettoyé) entre les parenthèses


@dataclass
class TopLevelType:
    kind: str
    name: str
    line: int
    annotations: list[Annotation]


def _split_top_level(s: str, sep: str = ",") -> list[str]:
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        if ch == sep and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


def annotation_members(args: str) -> dict[str, str]:
    """``Dist.CLIENT`` -> {'value': 'Dist.CLIENT'} ;
    ``modid = X, value = Dist.CLIENT`` -> {'modid': 'X', 'value': ...}."""
    members: dict[str, str] = {}
    if not args.strip():
        return members
    for part in _split_top_level(args):
        m = re.match(r"^\s*([\w$]+)\s*=(?!=)\s*(.*?)\s*$", part, re.DOTALL)
        if m:
            members[m.group(1)] = m.group(2)
        else:
            members["value"] = part.strip()
    return members


def is_client_only_dist(expr: str | None) -> bool:
    """Vrai si l'expression vaut ``Dist.CLIENT`` ou ``{Dist.CLIENT}``
    (et rien d'autre)."""
    if expr is None:
        return False
    expr = re.sub(r"\s+", "", expr)
    if expr.startswith("{") and expr.endswith("}"):
        elements = [e for e in _split_top_level(expr[1:-1]) if e]
    else:
        elements = [expr]
    return bool(elements) and all(CLIENT_DIST_RE.match(e) for e in elements)


def is_client_annotated(annotations: list[Annotation]) -> bool:
    for ann in annotations:
        members = annotation_members(ann.args)
        if ann.name == "OnlyIn" and is_client_only_dist(members.get("value")):
            return True
        if ann.name == "Mod" and is_client_only_dist(members.get("dist")):
            return True
        if ann.name == "EventBusSubscriber" and is_client_only_dist(members.get("value")):
            return True
    return False


def _parse_annotations(tokens: list[tuple[str, int]], cleaned: str) -> list[Annotation]:
    anns: list[Annotation] = []
    i, n = 0, len(tokens)
    while i < n:
        text, _ = tokens[i]
        if text == "@" and i + 1 < n and tokens[i + 1][0] != "interface":
            j = i + 1
            name = tokens[j][0]
            # Nom qualifié : a.b.C
            while j + 2 < n and tokens[j + 1][0] == "." and re.match(r"[\w$]", tokens[j + 2][0]):
                j += 2
                name = tokens[j][0]
            args = ""
            if j + 1 < n and tokens[j + 1][0] == "(":
                depth, k = 0, j + 1
                while k < n:
                    if tokens[k][0] == "(":
                        depth += 1
                    elif tokens[k][0] == ")":
                        depth -= 1
                        if depth == 0:
                            break
                    k += 1
                start = tokens[j + 1][1] + 1
                end = tokens[k][1] if k < n else len(cleaned)
                args = cleaned[start:end]
                j = k
            anns.append(Annotation(name, args))
            i = j + 1
        else:
            i += 1
    return anns


def find_top_level_types(cleaned: str) -> list[TopLevelType]:
    tokens = [(m.group(0), m.start()) for m in TOKEN_RE.finditer(cleaned)]
    types: list[TopLevelType] = []
    brace = paren = 0
    region_start = 0  # début des modificateurs/annotations de la déclaration suivante
    in_header = False  # entre le mot-clé de la déclaration et son « { »
    for idx, (text, pos) in enumerate(tokens):
        if text == "(":
            paren += 1
        elif text == ")":
            paren = max(0, paren - 1)
        elif text == "{":
            brace += 1
            in_header = False
        elif text == "}":
            brace = max(0, brace - 1)
            if brace == 0 and paren == 0:
                region_start = idx + 1
        elif text == ";" and brace == 0 and paren == 0:
            region_start = idx + 1
            in_header = False
        elif brace == 0 and paren == 0 and not in_header:
            prev = tokens[idx - 1][0] if idx > 0 else ""
            nxt = tokens[idx + 1][0] if idx + 1 < len(tokens) else ""
            nxt2 = tokens[idx + 2][0] if idx + 2 < len(tokens) else ""
            is_decl = (
                (text in ("class", "interface", "enum") and prev != ".")
                or (text == "record" and re.match(r"(?:[^\W\d]|\$)", nxt or "0")
                    and nxt2 in ("(", "<"))
            )
            if is_decl:
                kind = "@interface" if text == "interface" and prev == "@" else text
                region = tokens[region_start:idx]
                types.append(TopLevelType(
                    kind=kind,
                    name=nxt or "?",
                    line=_line_of(cleaned, pos),
                    annotations=_parse_annotations(region, cleaned),
                ))
                in_header = True
    return types


# ---------------------------------------------------------------------------
# Analyse d'une arborescence
# ---------------------------------------------------------------------------

def _rel(path: Path, base: Path) -> Path:
    try:
        return path.relative_to(base)
    except ValueError:
        return path


def check_tree(src_root: Path, client_package: str = CLIENT_PACKAGE,
               display_base: Path | None = None) -> Report:
    src_root = Path(src_root)
    display_base = display_base or src_root
    client_dir = src_root.joinpath(*client_package.split("."))
    report = Report()

    for path in sorted(src_root.rglob("*.java")):
        report.files += 1
        raw = path.read_text(encoding="utf-8", errors="replace").lstrip("﻿")
        cleaned = strip_comments_and_literals(raw)
        raw_lines = raw.splitlines()
        shown = _rel(path, display_base)
        in_client = client_dir in path.parents

        # Cohérence paquet / répertoire : le classement client/commun se fait
        # par répertoire, il doit donc correspondre au paquet déclaré.
        pkg_match = PACKAGE_RE.search(cleaned)
        declared = re.sub(r"\s+", "", pkg_match.group(1)) if pkg_match else ""
        expected = ".".join(_rel(path.parent, src_root).parts)
        if declared != expected:
            report.packages.append(Violation(
                shown, _line_of(cleaned, pkg_match.start(1)) if pkg_match else 1,
                f"paquet déclaré « {declared or '(aucun)'} » mais fichier situé dans « {expected or '(racine)'} »",
            ))

        if not in_client:
            for line, ref in find_forbidden_references(cleaned):
                ctx = raw_lines[line - 1].strip() if line - 1 < len(raw_lines) else ""
                report.references.append(Violation(shown, line, ref, ctx))
        elif path.name not in _SKIPPED_FOR_ANNOTATIONS:
            for t in find_top_level_types(cleaned):
                report.client_types += 1
                if not is_client_annotated(t.annotations):
                    report.annotations.append(Violation(
                        shown, t.line, f"{t.kind} {t.name}",
                        raw_lines[t.line - 1].strip() if t.line - 1 < len(raw_lines) else "",
                    ))
    return report


def format_report(report: Report) -> str:
    if report.ok:
        return (f"OK : aucune référence client hors du paquet client "
                f"({report.files} fichiers analysés, {report.client_types} "
                f"classe(s) client correctement annotée(s))")
    total = len(report.references) + len(report.annotations) + len(report.packages)
    out = [f"ÉCHEC : {total} problème(s) de compatibilité serveur dédié "
           f"({report.files} fichiers analysés)", ""]
    if report.references:
        out.append("Références à du code client hors du paquet client "
                   f"({CLIENT_PACKAGE}) :")
        for v in report.references:
            out.append(f"  {v.path}:{v.line} : {v.message}")
            if v.context:
                out.append(f"      > {v.context}")
        out.append("  -> Déplacez ce code dans le paquet client, ou isolez-le derrière "
                   "une classe client appelée uniquement si FMLEnvironment.dist.isClient().")
        out.append("")
    if report.annotations:
        out.append("Classes du paquet client sans @OnlyIn(Dist.CLIENT), "
                   "@Mod(dist = Dist.CLIENT) ni @EventBusSubscriber(value = Dist.CLIENT) :")
        for v in report.annotations:
            out.append(f"  {v.path}:{v.line} : {v.message}")
        out.append("")
    if report.packages:
        out.append("Déclarations de paquet incohérentes avec l'emplacement du fichier :")
        for v in report.packages:
            out.append(f"  {v.path}:{v.line} : {v.message}")
        out.append("")
    return "\n".join(out).rstrip()


# ---------------------------------------------------------------------------
# Tests intégrés (--self-test)
# ---------------------------------------------------------------------------

def _write(root: Path, rel: str, content: str) -> None:
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).lstrip("\n"), encoding="utf-8")


def _self_test() -> int:
    base = "io/github/theoblaise1/sovereignfrontier"
    failures: list[str] = []

    def expect(label: str, cond: bool, detail: str = "") -> None:
        print(f"  [{'ok' if cond else 'ÉCHEC'}] {label}")
        if not cond:
            failures.append(f"{label}\n{detail}")

    with tempfile.TemporaryDirectory(prefix="check_sides_") as tmp:
        # --- 1. Arborescence saine -----------------------------------------
        clean = Path(tmp) / "clean"
        _write(clean, f"{base}/SovereignFrontier.java", """
            package io.github.theoblaise1.sovereignfrontier;

            import net.minecraft.world.level.Level;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.fml.loading.FMLEnvironment;

            // import net.minecraft.client.Minecraft;  (commentaire : ignoré)
            /* com.mojang.blaze3d.systems.RenderSystem
               org.lwjgl.glfw.GLFW */
            /** Voir {@link net.minecraft.client.Minecraft}. */
            public class SovereignFrontier {
                static final String A = "net.minecraft.client.Minecraft";
                static final String B = "\\" org.lwjgl.glfw.GLFW \\"";
                static final char Q = '"';
                static final char E = '\\'';
                static final String T = \"\"\"
                    net.neoforged.neoforge.client.event.RenderGuiEvent
                    \"\"\";
                Class<?> c = SovereignFrontier.class;
                boolean client() { return FMLEnvironment.dist == Dist.CLIENT; }
            }
            """)
        _write(clean, f"{base}/client/ClientSetup.java", """
            package io.github.theoblaise1.sovereignfrontier.client;

            import net.minecraft.client.Minecraft;
            import net.neoforged.api.distmarker.Dist;
            import net.neoforged.api.distmarker.OnlyIn;

            @OnlyIn(Dist.CLIENT)
            public final class ClientSetup {
                static class Nested {}      // classe imbriquée : pas d'annotation requise
                Class<?> k = Minecraft.class;
            }

            @OnlyIn(value = Dist.CLIENT)
            record Pair(int a, int b) {}
            """)
        _write(clean, f"{base}/client/ClientMod.java", """
            package io.github.theoblaise1.sovereignfrontier.client;

            @net.neoforged.fml.common.Mod(value = "sovereignfrontier", dist = Dist.CLIENT)
            public class ClientMod {}
            """)
        _write(clean, f"{base}/client/ClientEvents.java", """
            package io.github.theoblaise1.sovereignfrontier.client;

            @EventBusSubscriber(modid = SovereignFrontier.MODID, bus = EventBusSubscriber.Bus.MOD,
                    value = {Dist.CLIENT})
            public final class ClientEvents { Class<?> x = ClientEvents.class; }
            """)
        _write(clean, f"{base}/client/screen/package-info.java", """
            package io.github.theoblaise1.sovereignfrontier.client.screen;
            """)
        _write(clean, f"{base}/client/screen/NationScreen.java", """
            package io.github.theoblaise1.sovereignfrontier.client.screen;

            import com.mojang.blaze3d.systems.RenderSystem;

            @Deprecated
            @OnlyIn(Dist.CLIENT)
            public sealed interface NationScreen permits A {}
            """)
        r = check_tree(clean)
        expect("arborescence saine acceptée", r.ok, format_report(r))
        expect("6 fichiers analysés", r.files == 6, str(r.files))
        expect("5 types client de premier niveau détectés", r.client_types == 5,
               str(r.client_types))

        # --- 2. Références interdites hors du paquet client ----------------
        bad = Path(tmp) / "bad"
        _write(bad, f"{base}/network/Handler.java", """
            package io.github.theoblaise1.sovereignfrontier.network;

            import net.minecraft.client.Minecraft;
            import static com.mojang.blaze3d.systems.RenderSystem.*;
            import org.lwjgl.glfw.GLFW;

            public class Handler {
                char q = '"'; net.neoforged.neoforge.client.event.RenderGuiEvent e = null;
                double t = org . lwjgl . glfw . GLFW.glfwGetTime(); // "piège"
                String s = "net.minecraft.client.Minecraft"; /* org.lwjgl.x */
            }
            """)
        r = check_tree(bad)
        got = sorted((v.line, v.message) for v in r.references)
        want = [
            (3, "net.minecraft.client.Minecraft"),
            (4, "com.mojang.blaze3d.systems.RenderSystem.*"),
            (5, "org.lwjgl.glfw.GLFW"),
            (8, "net.neoforged.neoforge.client.event.RenderGuiEvent"),
            (9, "org.lwjgl.glfw.GLFW.glfwGetTime"),
        ]
        expect("références interdites détectées (import, static, FQN)", got == want,
               f"obtenu : {got}\nattendu : {want}")
        expect("échec signalé", not r.ok)
        txt = format_report(r)
        expect("rapport au format fichier:ligne",
               f"{Path(base) / 'network' / 'Handler.java'}:3 : net.minecraft.client.Minecraft" in txt,
               txt)

        # --- 3. Annotations manquantes dans le paquet client ---------------
        ann = Path(tmp) / "ann"
        _write(ann, f"{base}/client/NoAnnot.java", """
            package io.github.theoblaise1.sovereignfrontier.client;

            // @OnlyIn(Dist.CLIENT)  <- dans un commentaire : ne compte pas
            public class NoAnnot {}
            """)
        _write(ann, f"{base}/client/ServerOnly.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            @OnlyIn(Dist.DEDICATED_SERVER)
            public enum ServerOnly { A, B; }
            """)
        _write(ann, f"{base}/client/ModNoDist.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            @Mod("sovereignfrontier")
            public class ModNoDist {}
            """)
        _write(ann, f"{base}/client/BothSides.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            @EventBusSubscriber(modid = "x", value = {Dist.CLIENT, Dist.DEDICATED_SERVER})
            public class BothSides {}
            """)
        _write(ann, f"{base}/client/NoValue.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            @EventBusSubscriber(modid = "x", bus = EventBusSubscriber.Bus.MOD)
            public class NoValue {}
            """)
        _write(ann, f"{base}/client/TwoTypes.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            @OnlyIn(Dist.CLIENT)
            public class TwoTypes {}

            record Helper(int x) {}
            """)
        _write(ann, f"{base}/client/Marker.java", """
            package io.github.theoblaise1.sovereignfrontier.client;
            public @interface Marker {}
            """)
        r = check_tree(ann)
        got = sorted((str(v.path.name), v.message) for v in r.annotations)
        want = sorted([
            ("NoAnnot.java", "class NoAnnot"),
            ("ServerOnly.java", "enum ServerOnly"),
            ("ModNoDist.java", "class ModNoDist"),
            ("BothSides.java", "class BothSides"),
            ("NoValue.java", "class NoValue"),
            ("TwoTypes.java", "record Helper"),
            ("Marker.java", "@interface Marker"),
        ])
        expect("classes client non annotées détectées", got == want,
               f"obtenu : {got}\nattendu : {want}")
        expect("aucune fausse référence interdite", not r.references,
               str(r.references))

        # --- 4. Paquet incohérent avec le répertoire ------------------------
        pkg = Path(tmp) / "pkg"
        _write(pkg, f"{base}/client/Misplaced.java", """
            package io.github.theoblaise1.sovereignfrontier.nation;
            import net.minecraft.client.Minecraft;
            @OnlyIn(Dist.CLIENT) public class Misplaced {}
            """)
        r = check_tree(pkg)
        expect("paquet incohérent signalé", len(r.packages) == 1, format_report(r))

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
    parser = argparse.ArgumentParser(
        description="Vérifie qu'aucun code client n'est référencé hors du paquet client.")
    parser.add_argument("--self-test", action="store_true",
                        help="exécute les tests intégrés sur une arborescence temporaire")
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    if not SRC_ROOT.is_dir():
        print(f"ERREUR : répertoire source introuvable : {SRC_ROOT}")
        return 1
    report = check_tree(SRC_ROOT, display_base=REPO_ROOT)
    if report.files == 0:
        print(f"ERREUR : aucun fichier .java trouvé sous {_rel(SRC_ROOT, REPO_ROOT)}")
        return 1
    print(format_report(report))
    return 0 if report.ok else 1


if __name__ == "__main__":
    sys.exit(main())
