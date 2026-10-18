# Propriété : WP2
"""Contrôle des table /daily (data/<ns>/daily/streak.json) : 1 à 2 clés par jour, types connus.

Ébauche du socle : aucun contrôle pour l'instant.
"""

from __future__ import annotations

from .common import Context


def check(ctx: Context) -> list[tuple[str, str]]:
    return []
