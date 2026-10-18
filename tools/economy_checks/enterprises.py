# Propriété : WP4
"""Contrôle des secteurs d'entreprise (data/<ns>/enterprises/) : per_worker, bonus_per_tier, objets interdits.

Ébauche du socle : aucun contrôle pour l'instant.
"""

from __future__ import annotations

from .common import Context


def check(ctx: Context) -> list[tuple[str, str]]:
    return []
