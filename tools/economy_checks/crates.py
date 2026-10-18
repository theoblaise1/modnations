# Propriété : WP2
"""Contrôle des tables de caisse (data/<ns>/crates/) : tirages, entrées rares, objets interdits.

Ébauche du socle : aucun contrôle pour l'instant.
"""

from __future__ import annotations

from .common import Context


def check(ctx: Context) -> list[tuple[str, str]]:
    return []
