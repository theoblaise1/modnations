#!/usr/bin/env python3
"""Générateur procédural (et déterministe) des textures de Sovereign Frontier.

Utilisation (depuis n'importe quel répertoire) :

    python3 tools/gen_textures.py            # (ré)écrit toutes les PNG
    python3 tools/gen_textures.py --check    # vérifie que les PNG sur disque sont à jour
    python3 tools/gen_textures.py --preview apercu.png   # planche agrandie pour relecture

Les textures (16x16, RGBA) sont écrites dans
``src/main/resources/assets/sovereignfrontier/textures/block/``.

Chaque texture possède sa propre graine fixe (``random.Random(graine)``) : le
résultat est identique d'une exécution à l'autre. ``--check`` régénère tout en
mémoire et compare les pixels décodés (et non les octets PNG, qui peuvent varier
selon la version de zlib) ; il sort avec le code 1 en listant les fichiers
manquants ou différents.

Ajouter une texture : écrire une fonction ``(canvas, rng) -> None`` décorée par
``@texture("nom_du_fichier", seed=...)`` en réutilisant les petits outils
ci-dessous (remplissage bruité, biseau, rivets, disques, planches...), puis
relancer le script et committer les PNG produites avec le script.
"""

from __future__ import annotations

import argparse
import math
import random
import sys
from pathlib import Path
from typing import Callable, Iterator

from PIL import Image

__version__ = "1.0"

SIZE = 16
REPO_ROOT = Path(__file__).resolve().parent.parent
BLOCK_TEXTURE_DIR = (
    REPO_ROOT / "src" / "main" / "resources" / "assets" / "sovereignfrontier" / "textures" / "block"
)

Color = tuple  # (r, g, b) ou (r, g, b, a)

# --------------------------------------------------------------------------- #
# Palette
# --------------------------------------------------------------------------- #
STEEL = (74, 78, 86)
STEEL_DARK = (56, 59, 66)
STEEL_LIGHT = (112, 118, 128)
STEEL_GROOVE = (30, 32, 37)

GOLD = (222, 176, 52)
GOLD_LIGHT = (252, 224, 112)
GOLD_DARK = (150, 102, 24)
BRONZE = (92, 62, 24)

NAVY = (32, 48, 116)

OAK = (166, 132, 80)
OAK_DARK = (124, 94, 54)
WOOD_DARK = (102, 74, 42)
WOOD_GAP = (52, 36, 20)

PAPER = (236, 229, 207)
INK = (84, 76, 68)
LEATHER = (116, 40, 32)
RIBBON = (178, 32, 30)

BOOK_COLORS = [
    (128, 38, 34),   # rouge sombre
    (46, 92, 52),    # vert
    (40, 58, 122),   # bleu
    (112, 74, 40),   # cuir brun
    (88, 44, 86),    # prune
]


# --------------------------------------------------------------------------- #
# Couleurs
# --------------------------------------------------------------------------- #
def clamp(v: float) -> int:
    return 0 if v < 0 else 255 if v > 255 else int(v)


def rgba(color: Color) -> tuple[int, int, int, int]:
    if len(color) == 4:
        return tuple(int(v) for v in color)  # type: ignore[return-value]
    r, g, b = color
    return (int(r), int(g), int(b), 255)


def shade(color: Color, delta: int) -> tuple[int, int, int, int]:
    """Éclaircit (delta > 0) ou assombrit (delta < 0) une couleur, alpha conservé."""
    r, g, b, a = rgba(color)
    return (clamp(r + delta), clamp(g + delta), clamp(b + delta), a)


def mix(c1: Color, c2: Color, t: float) -> tuple[int, int, int, int]:
    a, b = rgba(c1), rgba(c2)
    return tuple(clamp(round(x + (y - x) * t)) for x, y in zip(a, b))  # type: ignore[return-value]


def jitter(rng: random.Random, amount: float) -> int:
    """Entier pseudo-aléatoire dans [-amount, amount]."""
    return round((rng.random() * 2.0 - 1.0) * amount)


# --------------------------------------------------------------------------- #
# Toile
# --------------------------------------------------------------------------- #
class Canvas:
    """Petite grille de pixels RGBA (coordonnées x, y ; origine en haut à gauche)."""

    def __init__(self, size: int = SIZE, fill: Color = (0, 0, 0)) -> None:
        self.size = size
        self._px = [[rgba(fill)] * size for _ in range(size)]

    @property
    def last(self) -> int:
        return self.size - 1

    def inside(self, x: int, y: int) -> bool:
        return 0 <= x < self.size and 0 <= y < self.size

    def get(self, x: int, y: int) -> tuple[int, int, int, int]:
        return self._px[y][x]

    def set(self, x: int, y: int, color: Color) -> None:
        if self.inside(x, y):
            self._px[y][x] = rgba(color)

    def shade(self, x: int, y: int, delta: int) -> None:
        if self.inside(x, y):
            self._px[y][x] = shade(self._px[y][x], delta)

    def to_image(self) -> Image.Image:
        data = bytearray()
        for row in self._px:
            for px in row:
                data.extend(px)
        return Image.frombytes("RGBA", (self.size, self.size), bytes(data))


# --------------------------------------------------------------------------- #
# Outils de dessin réutilisables (rectangles inclusifs : x0..x1, y0..y1)
# --------------------------------------------------------------------------- #
def fill_rect(c: Canvas, x0: int, y0: int, x1: int, y1: int, color: Color) -> None:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            c.set(x, y, color)


def noise_rect(
    c: Canvas,
    rng: random.Random,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    base: Color,
    amount: float = 5,
    streak: float = 0,
) -> None:
    """Remplissage bruité ; ``streak`` ajoute une teinte par ligne (aspect brossé)."""
    for y in range(y0, y1 + 1):
        row = jitter(rng, streak) if streak else 0
        for x in range(x0, x1 + 1):
            c.set(x, y, shade(base, row + jitter(rng, amount)))


def shade_rect(c: Canvas, x0: int, y0: int, x1: int, y1: int, delta: int) -> None:
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            c.shade(x, y, delta)


def hline(c: Canvas, x0: int, x1: int, y: int, color: Color) -> None:
    fill_rect(c, x0, y, x1, y, color)


def vline(c: Canvas, x: int, y0: int, y1: int, color: Color) -> None:
    fill_rect(c, x, y0, x, y1, color)


def outline_rect(c: Canvas, x0: int, y0: int, x1: int, y1: int, color: Color) -> None:
    hline(c, x0, x1, y0, color)
    hline(c, x0, x1, y1, color)
    vline(c, x0, y0, y1, color)
    vline(c, x1, y0, y1, color)


def bevel(c: Canvas, x0: int, y0: int, x1: int, y1: int, light: int = 24, dark: int = 26) -> None:
    """Biseau relatif (préserve le bruit) : lumière venant d'en haut à gauche.

    Valeurs négatives pour un effet en creux.
    """
    for x in range(x0, x1):
        c.shade(x, y0, light)
    for y in range(y0 + 1, y1):
        c.shade(x0, y, light)
    for x in range(x0 + 1, x1 + 1):
        c.shade(x, y1, -dark)
    for y in range(y0 + 1, y1):
        c.shade(x1, y, -dark)


def rivet(c: Canvas, x: int, y: int, base: Color = STEEL_LIGHT, shadow: int = 16) -> None:
    """Rivet 2x2 bombé, avec une petite ombre portée en bas à droite."""
    c.set(x, y, shade(base, 48))
    c.set(x + 1, y, shade(base, 14))
    c.set(x, y + 1, shade(base, 4))
    c.set(x + 1, y + 1, shade(base, -34))
    if shadow:
        c.shade(x + 2, y + 1, -shadow)
        c.shade(x + 1, y + 2, -shadow)
        c.shade(x + 2, y + 2, -shadow)


def corner_rivets(c: Canvas, inset: int = 1, base: Color = STEEL_LIGHT) -> None:
    far = c.size - inset - 2
    for x, y in ((inset, inset), (far, inset), (inset, far), (far, far)):
        rivet(c, x, y, base)


def ring_pixels(
    c: Canvas, cx: float, cy: float, r_out: float, r_in: float = -1.0
) -> Iterator[tuple[int, int, float, float, float]]:
    """Pixels dont le centre est à une distance d avec r_in < d <= r_out.

    Renvoie (x, y, dx, dy, d) ; (cx, cy) en coordonnées continues
    (le centre d'une texture 16x16 est (8.0, 8.0)).
    """
    for y in range(c.size):
        for x in range(c.size):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            d = math.hypot(dx, dy)
            if r_in < d <= r_out:
                yield x, y, dx, dy, d


def light_at(dx: float, dy: float, d: float) -> float:
    """Éclairage d'une surface bombée vue de face : +1 en haut à gauche, -1 en bas à droite."""
    if d == 0:
        return 0.0
    return -(dx + dy) / (d * math.sqrt(2.0))


def lit(base: Color, dx: float, dy: float, d: float, strength: int) -> tuple[int, int, int, int]:
    return shade(base, round(light_at(dx, dy, d) * strength))


def planks(
    c: Canvas,
    rng: random.Random,
    x0: int,
    y0: int,
    x1: int,
    y1: int,
    base: Color,
    plank_h: int = 4,
    grain: float = 5,
    seam: int = -34,
    joints: bool = True,
) -> None:
    """Planches horizontales : veinage, joint sombre en bas, jointure verticale aléatoire."""
    width = x1 - x0 + 1
    for top in range(y0, y1 + 1, plank_h):
        bottom = min(top + plank_h - 1, y1)
        tone = jitter(rng, 7)
        for y in range(top, bottom + 1):
            row = tone + jitter(rng, 4)
            for x in range(x0, x1 + 1):
                delta = row + jitter(rng, grain)
                if rng.random() < 0.10:
                    delta -= 12  # trait de veinage
                c.set(x, y, shade(base, delta))
        for x in range(x0, x1 + 1):
            c.shade(x, top, 8)
            c.shade(x, bottom, seam)
        if joints and bottom - top >= 2:
            jx = x0 + int(rng.random() * width)
            for y in range(top, bottom):
                c.shade(jx, y, -30)
                c.shade(jx + 1 if jx < x1 else jx - 1, y, 10)


def steel_base(c: Canvas, rng: random.Random, base: Color = STEEL) -> None:
    """Tôle d'acier brossé couvrant toute la texture, avec biseau extérieur."""
    noise_rect(c, rng, 0, 0, c.last, c.last, base, amount=4, streak=3)
    bevel(c, 0, 0, c.last, c.last, 26, 30)


# --------------------------------------------------------------------------- #
# Registre des textures
# --------------------------------------------------------------------------- #
TextureFn = Callable[[Canvas, random.Random], None]
TEXTURES: dict[str, tuple[int, TextureFn]] = {}


def texture(name: str, seed: int) -> Callable[[TextureFn], TextureFn]:
    """Enregistre une texture ``textures/block/<name>.png`` avec sa graine fixe."""

    def register(fn: TextureFn) -> TextureFn:
        if name in TEXTURES:
            raise ValueError(f"texture déjà déclarée : {name}")
        TEXTURES[name] = (seed, fn)
        return fn

    return register


def render(name: str) -> Image.Image:
    seed, fn = TEXTURES[name]
    canvas = Canvas()
    fn(canvas, random.Random(seed))
    return canvas.to_image()


# --------------------------------------------------------------------------- #
# Textures : acier national
# --------------------------------------------------------------------------- #
@texture("blindage_national", seed=0x5F0001)
def blindage_national(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng)
    # Rainure autour du panneau central en relief.
    outline_rect(c, 3, 3, 12, 12, STEEL_GROOVE)
    noise_rect(c, rng, 4, 4, 11, 11, STEEL_DARK, amount=4, streak=2)
    bevel(c, 4, 4, 11, 11, 24, 18)
    # Renfort diagonal discret sur le panneau.
    for i in range(5, 11):
        c.shade(i, i, -10)
        c.shade(15 - i, i, -10)
    corner_rivets(c)


@texture("tresor_national_side", seed=0x5F0002)
def tresor_national_side(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng, STEEL_DARK)
    cx = cy = 8.0
    # Logement circulaire en creux autour du cadran.
    for x, y, dx, dy, d in ring_pixels(c, cx, cy, 5.8, 5.0):
        c.set(x, y, shade(STEEL_GROOVE, jitter(rng, 3)))
    for x, y, dx, dy, d in ring_pixels(c, cx, cy, 6.7, 5.8):
        c.shade(x, y, 12 if dx + dy > 0 else -10)
    # Couronne dorée bombée, avec quatre crans.
    for x, y, dx, dy, d in ring_pixels(c, cx, cy, 5.0, 3.0):
        c.set(x, y, shade(lit(GOLD, dx, dy, d, 46), jitter(rng, 5)))
    for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
        c.set(x, y, GOLD_DARK)
    # Fond du cadran en bronze sombre (en creux), rayons du volant et moyeu.
    for x, y, dx, dy, d in ring_pixels(c, cx, cy, 3.0):
        if x in (7, 8) or y in (7, 8):
            c.set(x, y, lit(GOLD, dx, dy, d, 34))
        else:
            c.set(x, y, shade(lit(BRONZE, dx, dy, d, -16), jitter(rng, 3)))
    c.set(7, 7, GOLD_LIGHT)
    c.set(8, 7, shade(GOLD_LIGHT, -18))
    c.set(7, 8, shade(GOLD, -4))
    c.set(8, 8, GOLD_DARK)
    corner_rivets(c)


@texture("tresor_national_top", seed=0x5F0003)
def tresor_national_top(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng, STEEL_DARK)
    outline_rect(c, 3, 3, 12, 12, STEEL_GROOVE)
    noise_rect(c, rng, 4, 4, 11, 11, shade(STEEL_DARK, -8), amount=3)
    bevel(c, 4, 4, 11, 11, -14, -10)  # panneau en creux
    corner_rivets(c)
    # Plaque dorée avec fente à pièces.
    for y in range(5, 11):
        for x in range(4, 12):
            c.set(x, y, shade(GOLD, jitter(rng, 6)))
    bevel(c, 4, 5, 11, 10, 34, 46)
    hline(c, 5, 10, 7, (34, 22, 8))
    for x in range(5, 11):
        c.set(x, 8, shade(GOLD_LIGHT, -10 + jitter(rng, 4)))
        c.shade(x, 6, -30)
    # Ombre portée de la plaque.
    hline(c, 5, 12, 11, shade(STEEL_DARK, -26))
    vline(c, 12, 6, 11, shade(STEEL_DARK, -26))


@texture("coffre_national_front", seed=0x5F0004)
def coffre_national_front(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng)
    # Jointure couvercle / caisse.
    hline(c, 1, 14, 5, STEEL_GROOVE)
    for x in range(1, 15):
        c.shade(x, 6, 18)
        c.shade(x, 4, -10)
    # Plaque de serrure dorée.
    for y in range(2, 13):
        for x in range(3, 13):
            c.set(x, y, shade(GOLD, jitter(rng, 6)))
    bevel(c, 3, 2, 12, 12, 34, 50)
    # Ombre portée de la plaque.
    vline(c, 13, 3, 13, shade(STEEL, -34))
    hline(c, 4, 13, 13, shade(STEEL, -34))
    # Écusson bleu nuit (émaillé, en creux) avec étoile d'or.
    noise_rect(c, rng, 4, 3, 11, 9, NAVY, amount=4)
    hline(c, 4, 11, 3, shade(NAVY, -12))
    vline(c, 4, 4, 9, shade(NAVY, -8))
    hline(c, 5, 11, 9, shade(NAVY, 10))
    star = [
        "...XX...",
        ".XXXXXX.",
        "..XXXX..",
        "..XXXX..",
        ".XX..XX.",
    ]
    for dy, line in enumerate(star):
        for dx, ch in enumerate(line):
            if ch == "X":
                c.set(4 + dx, 4 + dy, GOLD_LIGHT if dx + dy < 6 else GOLD)
    # Trou de serrure.
    c.set(7, 10, (16, 12, 8))
    c.set(8, 10, (16, 12, 8))
    c.set(7, 11, (30, 22, 10))
    c.set(8, 11, (30, 22, 10))
    corner_rivets(c)


@texture("coffre_national_side", seed=0x5F0005)
def coffre_national_side(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng)
    # Jointure couvercle / caisse, alignée sur la face avant.
    hline(c, 1, 14, 5, STEEL_GROOVE)
    for x in range(1, 15):
        c.shade(x, 6, 18)
        c.shade(x, 4, -10)
    # Bandes de renfort horizontales.
    for top in (2, 10):
        noise_rect(c, rng, 1, top, 14, top + 1, STEEL_LIGHT, amount=4)
        for x in range(1, 15):
            c.shade(x, top, 16)
            c.shade(x, top + 1, -10)
            c.shade(x, top + 2, -24)
        rivet(c, 2, top, shade(STEEL_LIGHT, 6))
        rivet(c, 12, top, shade(STEEL_LIGHT, 6))
    # Troisième bande basse, plus fine.
    for x in range(1, 15):
        c.set(x, 13, shade(STEEL_LIGHT, -6 + jitter(rng, 4)))
        c.shade(x, 14, -14)


@texture("coffre_national_top", seed=0x5F0006)
def coffre_national_top(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng)
    corner_rivets(c)
    # Croix de renfort.
    band = STEEL_LIGHT
    for i in range(1, 15):
        for j in (7, 8):
            c.set(j, i, shade(band, jitter(rng, 4)))
            c.set(i, j, shade(band, jitter(rng, 4)))
    for i in range(1, 15):
        if i not in (7, 8):
            c.shade(6, i, -26)   # bord gauche de la bande verticale
            c.shade(7, i, 14)
            c.shade(8, i, -8)
            c.shade(9, i, -30)   # ombre à droite
            c.shade(i, 6, -26)
            c.shade(i, 7, 14)
            c.shade(i, 8, -8)
            c.shade(i, 9, -30)
    # Plaque centrale rivetée.
    fill_rect(c, 6, 6, 9, 9, shade(STEEL_LIGHT, 8))
    bevel(c, 6, 6, 9, 9, 26, 34)
    rivet(c, 7, 7, STEEL_LIGHT, shadow=0)


# --------------------------------------------------------------------------- #
# Textures : registre civil (bois)
# --------------------------------------------------------------------------- #
@texture("registre_civil_side", seed=0x5F0007)
def registre_civil_side(c: Canvas, rng: random.Random) -> None:
    planks(c, rng, 0, 0, c.last, c.last, OAK, plank_h=4, joints=False)
    # Montants verticaux (pieds) et traverses.
    for x0 in (0, 14):
        for y in range(16):
            for x in (x0, x0 + 1):
                c.set(x, y, shade(OAK_DARK, jitter(rng, 4) + (6 if x == x0 else -4)))
    for y0 in (0, 14):
        for x in range(2, 14):
            for y in (y0, y0 + 1):
                c.set(x, y, shade(OAK_DARK, jitter(rng, 4) + (6 if y == y0 else -6)))
    outline_rect(c, 0, 0, 15, 15, shade(WOOD_DARK, -18))
    # Tiroir.
    outline_rect(c, 2, 2, 13, 8, WOOD_GAP)
    noise_rect(c, rng, 3, 3, 12, 7, shade(OAK, 8), amount=4, streak=3)
    bevel(c, 3, 3, 12, 7, 18, 22)
    c.set(7, 5, GOLD_LIGHT)
    c.set(8, 5, GOLD)
    c.shade(7, 6, -22)
    c.shade(8, 6, -26)
    # Étagère avec dos de livres.
    fill_rect(c, 2, 9, 13, 13, WOOD_GAP)
    hline(c, 2, 13, 9, shade(WOOD_GAP, -14))
    colors = list(BOOK_COLORS)
    rng.shuffle(colors)
    for i, color in enumerate(colors):
        x = 3 + i * 2
        top = 10 + (1 if rng.random() < 0.4 else 0)
        for y in range(top, 14):
            c.set(x, y, shade(color, 16 + jitter(rng, 3)))
            c.set(x + 1, y, shade(color, -8 + jitter(rng, 3)))
        band = (top + 1, x, x + 1)
        c.set(band[1], band[0], shade(GOLD, -10))
        c.set(band[2], band[0], shade(GOLD_DARK, 10))


@texture("registre_civil_top", seed=0x5F0008)
def registre_civil_top(c: Canvas, rng: random.Random) -> None:
    planks(c, rng, 0, 0, c.last, c.last, OAK, plank_h=4)
    outline_rect(c, 0, 0, 15, 15, OAK_DARK)
    bevel(c, 0, 0, 15, 15, 10, 26)
    # Ombre portée du livre.
    hline(c, 3, 14, 13, shade(OAK_DARK, -24))
    vline(c, 14, 4, 13, shade(OAK_DARK, -24))
    # Couverture en cuir.
    noise_rect(c, rng, 2, 3, 13, 12, LEATHER, amount=5)
    bevel(c, 2, 3, 13, 12, 18, 22)
    # Pages blanches.
    noise_rect(c, rng, 3, 4, 12, 11, PAPER, amount=3)
    for y in range(4, 12):
        c.shade(3, y, -8)
        c.shade(12, y, -12)
        c.shade(6, y, -6)
        c.shade(7, y, -30)
        c.shade(8, y, -20)
        c.shade(9, y, -4)
    for x in range(3, 13):
        c.shade(x, 11, -14)
    # Lignes d'écriture.
    lines = [
        (4, 6, 5), (4, 6, 7), (4, 5, 9),
        (9, 11, 5), (9, 11, 7), (9, 10, 9),
    ]
    for x0, x1, y in lines:
        for x in range(x0, x1 + 1):
            c.set(x, y, shade(INK, jitter(rng, 10)))
    # Signet qui dépasse.
    c.set(10, 12, RIBBON)
    c.set(10, 13, shade(RIBBON, -30))


@texture("registre_civil_bottom", seed=0x5F0009)
def registre_civil_bottom(c: Canvas, rng: random.Random) -> None:
    planks(c, rng, 0, 0, c.last, c.last, WOOD_DARK, plank_h=4, grain=4, seam=-30)

# --------------------------------------------------------------------------- #
# Jalon J3 : énergie, machines, réacteur, Station d'ingénierie
# --------------------------------------------------------------------------- #
COPPER = (184, 104, 64)
COPPER_DARK = (122, 64, 38)
ENERGY = (96, 220, 255)
ENERGY_DARK = (36, 120, 170)
HEAT = (255, 120, 40)
GLASS_BLUE = (40, 70, 120)
GAS = (180, 230, 120)
REACTOR = (64, 70, 60)
TRANSPARENT = (0, 0, 0, 0)


def machine_frame(c: Canvas, rng: random.Random, base: Color = STEEL) -> None:
    steel_base(c, rng, base)
    bevel(c, 0, 0, c.last, c.last)
    corner_rivets(c)


def gauge(c: Canvas, x0: int, y0: int, x1: int, y1: int, color: Color) -> None:
    fill_rect(c, x0, y0, x1, y1, STEEL_GROOVE)
    for y in range(y0 + 1, y1):
        for x in range(x0 + 1, x1):
            c.set(x, y, mix(color, shade(color, -70), (y - y0) / max(1, y1 - y0)))


@texture("cable_energie", seed=0x5F0101)
def cable_energie(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, TRANSPARENT)
    noise_rect(c, rng, 5, 0, 10, c.last, COPPER, amount=6, streak=4)
    vline(c, 5, 0, c.last, COPPER_DARK)
    vline(c, 10, 0, c.last, COPPER_DARK)
    for y in range(1, c.last, 4):
        hline(c, 6, 9, y, shade(COPPER, 30))


@texture("generateur_thermique_front", seed=0x5F0102)
def generateur_thermique_front(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    fill_rect(c, 4, 6, 11, 12, STEEL_GROOVE)
    for x in range(5, 11):
        c.set(x, 11, HEAT)
        c.set(x, 10, shade(HEAT, -40) if x % 2 else HEAT)
    hline(c, 4, 11, 4, STEEL_LIGHT)


@texture("generateur_thermique_side", seed=0x5F0103)
def generateur_thermique_side(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    for y in (4, 7, 10):
        hline(c, 3, 12, y, STEEL_GROOVE)


@texture("panneau_solaire_top", seed=0x5F0104)
def panneau_solaire_top(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, STEEL_LIGHT)
    for gy in range(2):
        for gx in range(2):
            x0, y0 = 1 + gx * 7, 1 + gy * 7
            noise_rect(c, rng, x0, y0, x0 + 6, y0 + 6, GLASS_BLUE, amount=4)
            c.set(x0 + 1, y0 + 1, shade(GLASS_BLUE, 70))
            hline(c, x0, x0 + 6, y0 + 3, shade(GLASS_BLUE, -20))


@texture("cellule_energie_side", seed=0x5F0105)
def cellule_energie_side(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    gauge(c, 5, 3, 10, 12, ENERGY)


@texture("cellule_energie_top", seed=0x5F0106)
def cellule_energie_top(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    fill_rect(c, 6, 6, 9, 9, ENERGY)
    outline_rect(c, 5, 5, 10, 10, STEEL_GROOVE)


@texture("foreuse_gaz_side", seed=0x5F0107)
def foreuse_gaz_side(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng, (82, 86, 70))
    gauge(c, 4, 4, 11, 11, GAS)
    vline(c, 7, 12, c.last, STEEL_GROOVE)
    vline(c, 8, 12, c.last, STEEL_GROOVE)


@texture("foreuse_gaz_top", seed=0x5F0108)
def foreuse_gaz_top(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng, (82, 86, 70))
    for i in range(4):
        outline_rect(c, 3 + i, 3 + i, 12 - i, 12 - i, STEEL_GROOVE if i % 2 else STEEL_LIGHT)


@texture("caisson_reacteur", seed=0x5F0109)
def caisson_reacteur(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng, REACTOR)
    bevel(c, 0, 0, c.last, c.last)
    for i in range(0, 16, 4):
        c.set(i, i, (200, 180, 40))
        c.set(c.last - i, i, (200, 180, 40))
    corner_rivets(c)


@texture("controleur_reacteur_front", seed=0x5F010A)
def controleur_reacteur_front(c: Canvas, rng: random.Random) -> None:
    steel_base(c, rng, REACTOR)
    bevel(c, 0, 0, c.last, c.last)
    fill_rect(c, 3, 3, 12, 8, STEEL_GROOVE)
    for x in range(4, 12):
        c.set(x, 6, ENERGY if x < 8 else HEAT)
    for x, col in ((4, (60, 200, 80)), (7, (220, 200, 40)), (10, (220, 50, 40))):
        fill_rect(c, x, 10, x + 1, 11, col)


@texture("cellule_combustible", seed=0x5F010B)
def cellule_combustible(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng, (60, 66, 60))
    for x in (4, 7, 10):
        fill_rect(c, x, 3, x + 1, 12, (90, 230, 90))
        vline(c, x, 3, 12, (40, 150, 40))


@texture("dissipateur_thermique", seed=0x5F010C)
def dissipateur_thermique(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, STEEL_DARK)
    for x in range(1, 15, 3):
        vline(c, x, 1, c.last - 1, STEEL_LIGHT)
        vline(c, x + 1, 1, c.last - 1, shade(STEEL_LIGHT, -30))
    outline_rect(c, 0, 0, c.last, c.last, STEEL_GROOVE)


@texture("station_ingenierie_top", seed=0x5F010D)
def station_ingenierie_top(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    fill_rect(c, 3, 3, 12, 12, (28, 48, 80))
    for x in range(3, 13, 3):
        vline(c, x, 3, 12, (60, 110, 170))
    for y in range(3, 13, 3):
        hline(c, 3, 12, y, (60, 110, 170))


@texture("station_ingenierie_side", seed=0x5F010E)
def station_ingenierie_side(c: Canvas, rng: random.Random) -> None:
    machine_frame(c, rng)
    fill_rect(c, 3, 3, 12, 6, STEEL_GROOVE)
    for x in range(4, 12, 2):
        c.set(x, 4, ENERGY)
    fill_rect(c, 5, 9, 10, 12, GOLD_DARK)
    fill_rect(c, 6, 10, 9, 11, GOLD)


@texture("item/combustible_enrichi", seed=0x5F0201)
def item_combustible_enrichi(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, TRANSPARENT)
    fill_rect(c, 5, 1, 10, 14, STEEL_DARK)
    fill_rect(c, 6, 3, 9, 12, (90, 230, 90))
    vline(c, 6, 3, 12, (150, 255, 150))
    hline(c, 5, 10, 1, STEEL_LIGHT)
    hline(c, 5, 10, 14, STEEL_LIGHT)


@texture("item/bouteille_gaz", seed=0x5F0202)
def item_bouteille_gaz(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, TRANSPARENT)
    fill_rect(c, 4, 4, 11, 14, STEEL)
    fill_rect(c, 6, 1, 9, 3, STEEL_DARK)
    vline(c, 5, 5, 13, STEEL_LIGHT)
    outline_rect(c, 4, 4, 11, 14, STEEL_GROOVE)


@texture("item/bouteille_gaz_pleine", seed=0x5F0203)
def item_bouteille_gaz_pleine(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, TRANSPARENT)
    fill_rect(c, 4, 4, 11, 14, (90, 140, 60))
    fill_rect(c, 6, 1, 9, 3, STEEL_DARK)
    vline(c, 5, 5, 13, GAS)
    fill_rect(c, 6, 8, 9, 10, (230, 220, 60))
    outline_rect(c, 4, 4, 11, 14, STEEL_GROOVE)


@texture("item/circuit", seed=0x5F0204)
def item_circuit(c: Canvas, rng: random.Random) -> None:
    fill_rect(c, 0, 0, c.last, c.last, TRANSPARENT)
    noise_rect(c, rng, 2, 3, 13, 12, (30, 110, 50), amount=4)
    fill_rect(c, 6, 6, 9, 9, (30, 30, 34))
    for x in (3, 5, 10, 12):
        vline(c, x, 4, 11, GOLD)
    outline_rect(c, 2, 3, 13, 12, (20, 70, 30))



# --------------------------------------------------------------------------- #
# Ligne de commande
# --------------------------------------------------------------------------- #
def _rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def texture_path(name: str) -> Path:
    """``item/x`` → textures/item/x.png ; sinon textures/block/<name>.png."""
    if name.startswith("item/"):
        return BLOCK_TEXTURE_DIR.parent / "item" / f"{name[5:]}.png"
    return BLOCK_TEXTURE_DIR / f"{name}.png"


def write_all() -> int:
    for name in TEXTURES:
        path = texture_path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        render(name).save(path, format="PNG")
        print(f"écrit : {_rel(path)}")
    print(f"{len(TEXTURES)} texture(s) générée(s).")
    return 0


def check_all() -> int:
    problems: list[str] = []
    for name in TEXTURES:
        path = texture_path(name)
        if not path.is_file():
            problems.append(f"manquant   : {_rel(path)}")
            continue
        expected = render(name)
        with Image.open(path) as img:
            actual = img.convert("RGBA")
        if actual.size != expected.size or actual.tobytes() != expected.tobytes():
            problems.append(f"différent  : {_rel(path)}")
    if problems:
        print("Textures non à jour (lancer : python3 tools/gen_textures.py) :")
        for line in problems:
            print(f"  {line}")
        return 1
    print(f"OK : {len(TEXTURES)} texture(s) à jour.")
    return 0


def write_preview(out: Path, scale: int = 12) -> int:
    """Planche agrandie : chaque texture seule, puis en mosaïque 3x3 (raccords)."""
    names = list(TEXTURES)
    cell = SIZE * 3 * (scale // 3)
    gap = 6
    sheet = Image.new("RGBA", (cell * 2 + gap, len(names) * (cell + gap) - gap), (40, 40, 40, 255))
    for row, name in enumerate(names):
        img = render(name)
        y = row * (cell + gap)
        sheet.paste(img.resize((cell, cell), Image.Resampling.NEAREST), (0, y))
        tile = Image.new("RGBA", (SIZE * 3, SIZE * 3))
        for ty in range(3):
            for tx in range(3):
                tile.paste(img, (tx * SIZE, ty * SIZE))
        sheet.paste(tile.resize((cell, cell), Image.Resampling.NEAREST), (cell + gap, y))
    sheet.save(out, format="PNG")
    print(f"aperçu : {out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Génère les textures de blocs de Sovereign Frontier.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true",
                       help="vérifie que les PNG sur disque correspondent au générateur (code 1 sinon)")
    group.add_argument("--preview", metavar="FICHIER", type=Path,
                       help="écrit une planche agrandie des textures (n'écrit pas les PNG du mod)")
    args = parser.parse_args(argv)
    if args.check:
        return check_all()
    if args.preview:
        return write_preview(args.preview)
    return write_all()


if __name__ == "__main__":
    sys.exit(main())
