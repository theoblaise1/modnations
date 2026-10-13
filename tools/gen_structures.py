#!/usr/bin/env python3
"""Génère les structures NBT (gabarits) du mod de façon déterministe.

Usage :
    python3 tools/gen_structures.py          # écrit les fichiers .nbt
    python3 tools/gen_structures.py --check  # vérifie que les fichiers sur disque sont à jour (CI)

Les fichiers sont écrits dans src/main/resources/data/sovereignfrontier/structure/.
Format : NBT compressé gzip d'un StructureTemplate Minecraft 1.21.1 (DataVersion 3955).
Le contenu est comparé après décompression (l'en-tête gzip dépend de l'outil).
Pour ajouter une structure : écrire une fonction qui renvoie (size, palette, blocks) et
l'enregistrer dans STRUCTURES.
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


def template(size, palette, blocks) -> bytes:
    """palette : liste de noms de blocs ; blocks : liste de (x, y, z, index_palette)."""
    root = {
        "DataVersion": Int(DATA_VERSION),
        "size": [Int(s) for s in size],
        "palette": [{"Name": name} for name in palette],
        "blocks": [{"pos": [Int(x), Int(y), Int(z)], "state": Int(i)} for x, y, z, i in blocks],
        "entities": [],
    }
    return encode_root(root)


# --------------------------------------------------------------------------- structures
def empty_area(n: int):
    """Volume vide n×n×n posé sur un plancher de pierre (sert aux GameTests)."""
    blocks = [(x, 0, z, 0) for x in range(n) for z in range(n)]
    return (n, n, n), ["minecraft:stone"], blocks


STRUCTURES = {
    "gametest/empty9": lambda: empty_area(9),
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
