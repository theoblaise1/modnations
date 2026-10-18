#!/usr/bin/env python3
"""Génère les structures NBT (gabarits) du mod de façon déterministe.

Usage :
    python3 tools/gen_structures.py          # écrit les fichiers .nbt
    python3 tools/gen_structures.py --check  # vérifie que les fichiers sur disque sont à jour (CI)

Les fichiers sont écrits dans src/main/resources/data/sovereignfrontier/structure/.
Format : NBT compressé gzip d'un StructureTemplate Minecraft 1.21.1 (DataVersion 3955).
Le contenu est comparé après décompression (l'en-tête gzip dépend de l'outil).
Pour ajouter une structure : écrire une fonction qui renvoie (size, palette, blocks) et
l'enregistrer dans STRUCTURES. Une entrée de palette est un nom de bloc, ou (nom, {propriété: valeur}) ; une
entrée de bloc est (x, y, z, index_palette) avec un 5e élément facultatif : le NBT du bloc-entité (dictionnaire).
Aucune entité dans les gabarits (leur position exige des TAG_Double, non gérés par cet encodeur).
"""
import argparse
import gzip
import io
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src/main/resources/data/sovereignfrontier/structure"
DATA_VERSION = 3955  # Minecraft 1.21.1

# --------------------------------------------------------------------------- encodeur NBT minimal
TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


class Int(int):
    """Entier NBT (TAG_Int)."""


def _write_payload(buf: io.BytesIO, tag_type: int, value) -> None:
    if tag_type == TAG_INT:
        buf.write(struct.pack(">i", value))
    elif tag_type == TAG_STRING:
        raw = value.encode("utf-8")
        buf.write(struct.pack(">H", len(raw)) + raw)
    elif tag_type == TAG_LIST:
        elem_type = _type_of(value[0]) if value else TAG_END
        buf.write(struct.pack(">bi", elem_type, len(value)))
        for item in value:
            _write_payload(buf, elem_type, item)
    elif tag_type == TAG_COMPOUND:
        for name, item in value.items():
            t = _type_of(item)
            buf.write(struct.pack(">b", t))
            _write_payload(buf, TAG_STRING, name)
            _write_payload(buf, t, item)
        buf.write(struct.pack(">b", TAG_END))
    else:
        raise ValueError(f"type NBT non géré : {tag_type}")


def _type_of(value) -> int:
    if isinstance(value, bool):
        raise TypeError("booléen non géré")
    if isinstance(value, int):
        return TAG_INT
    if isinstance(value, str):
        return TAG_STRING
    if isinstance(value, list):
        return TAG_LIST
    if isinstance(value, dict):
        return TAG_COMPOUND
    raise TypeError(f"valeur NBT non gérée : {value!r}")


def encode_root(root: dict) -> bytes:
    buf = io.BytesIO()
    buf.write(struct.pack(">b", TAG_COMPOUND))
    _write_payload(buf, TAG_STRING, "")
    _write_payload(buf, TAG_COMPOUND, root)
    return buf.getvalue()


def _palette_entry(entry) -> dict:
    """« nom » ou (nom, {propriété: valeur}) → {"Name", "Properties"} (propriétés triées, chaînes)."""
    if isinstance(entry, str):
        return {"Name": entry}
    name, props = entry
    if not props:
        return {"Name": name}
    return {"Name": name, "Properties": {k: str(props[k]) for k in sorted(props)}}


def _block_entry(block) -> dict:
    x, y, z, index = block[:4]
    out = {"pos": [Int(x), Int(y), Int(z)], "state": Int(index)}
    if len(block) > 4 and block[4] is not None:
        out["nbt"] = block[4]
    return out


def template(size, palette, blocks) -> bytes:
    """palette : noms de blocs ou (nom, propriétés) ; blocks : (x, y, z, index_palette[, nbt])."""
    root = {
        "DataVersion": Int(DATA_VERSION),
        "size": [Int(s) for s in size],
        "palette": [_palette_entry(p) for p in palette],
        "blocks": [_block_entry(b) for b in blocks],
        "entities": [],
    }
    return encode_root(root)


class Builder:
    """Gabarit construit case par case : palette dédoublonnée, blocs triés (y, z, x) pour une sortie stable."""

    def __init__(self, size):
        self.size = size
        self.palette: list = []
        self._index: dict = {}
        self._cells: dict = {}

    def set(self, x, y, z, name, props=None, nbt=None):
        key = (name, tuple(sorted((props or {}).items())))
        if key not in self._index:
            self._index[key] = len(self.palette)
            self.palette.append((name, dict(props)) if props else name)
        self._cells[(x, y, z)] = (self._index[key], nbt)

    def build(self):
        cells = sorted(self._cells.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0]))
        blocks = [(x, y, z, i, nbt) for (x, y, z), (i, nbt) in cells]
        return tuple(self.size), self.palette, blocks


# --------------------------------------------------------------------------- structures
def empty_area(n: int):
    """Volume vide n×n×n posé sur un plancher de pierre (sert aux GameTests)."""
    blocks = [(x, 0, z, 0) for x in range(n) for z in range(n)]
    return (n, n, n), ["minecraft:stone"], blocks


# Variantes d'avant-poste (docs/conception/J4.md §3) : sol (bordure, intérieur), poteaux, toit, pool du comptoir.
OUTPOST_VARIANTS = {
    "plaines": ("minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:oak_log", "minecraft:oak_slab", "general"),
    "desert": ("minecraft:cut_sandstone", "minecraft:smooth_sandstone", "minecraft:acacia_log", "minecraft:acacia_slab",
               "prospecteur"),
    "taiga": ("minecraft:cobblestone", "minecraft:spruce_planks", "minecraft:spruce_log", "minecraft:spruce_slab",
              "trappeur"),
}


def outpost(variant: str):
    """
    Avant-poste marchand 11 × 7 × 11 : sol, volume dégagé (air explicite), 4 poteaux, toit de dalles, 4 lanternes
    suspendues, comptoir (5,1,4) tourné au nord (le marchand apparaît au premier tick à son poste, (5,1,5)), caisse
    commune publique et tonneau à butin.
    """
    border, inner, log, slab, pool = OUTPOST_VARIANTS[variant]
    b = Builder((11, 7, 11))
    for x in range(11):
        for z in range(11):
            b.set(x, 0, z, border if x in (0, 10) or z in (0, 10) else inner)
            for y in range(1, 7):
                b.set(x, y, z, "minecraft:air")
    for x, z in ((1, 1), (9, 1), (1, 9), (9, 9)):
        for y in range(1, 5):
            b.set(x, y, z, log, {"axis": "y"})
    for x in range(1, 10):
        for z in range(1, 10):
            b.set(x, 5, z, slab, {"type": "bottom", "waterlogged": "false"})
    for x, z in ((2, 2), (8, 2), (2, 8), (8, 8)):
        b.set(x, 4, z, "minecraft:lantern", {"hanging": "true", "waterlogged": "false"})
    b.set(5, 1, 4, "sovereignfrontier:comptoir_avant_poste", {"bound": "false", "facing": "north"},
          {"id": "sovereignfrontier:comptoir", "pool": f"sovereignfrontier:{pool}"})
    b.set(8, 1, 8, "sovereignfrontier:caisse_commune", {"facing": "west", "open": "false"})
    b.set(2, 1, 8, "minecraft:barrel", {"facing": "up", "open": "false"},
          {"id": "minecraft:barrel", "LootTable": f"sovereignfrontier:chests/avant_poste_{variant}"})
    return b.build()


STRUCTURES = {
    "gametest/empty9": lambda: empty_area(9),
    "avant_poste/plaines": lambda: outpost("plaines"),
    "avant_poste/desert": lambda: outpost("desert"),
    "avant_poste/taiga": lambda: outpost("taiga"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="vérifier sans écrire")
    args = parser.parse_args()
    stale = []
    for name, factory in STRUCTURES.items():
        raw = template(*factory())
        path = OUT / f"{name}.nbt"
        if args.check:
            if not path.exists() or gzip.decompress(path.read_bytes()) != raw:
                stale.append(name)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("wb") as fh:
            # mtime=0 : sortie reproductible
            with gzip.GzipFile(filename="", mode="wb", fileobj=fh, mtime=0) as gz:
                gz.write(raw)
        print(f"écrit : {path.relative_to(ROOT)}")
    if args.check:
        if stale:
            print("ERREUR : structure(s) à régénérer (python3 tools/gen_structures.py) : " + ", ".join(stale))
            return 1
        print(f"OK : {len(STRUCTURES)} structure(s) à jour.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
