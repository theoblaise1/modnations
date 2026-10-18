# Propriété : WP3
"""Contrôle des modèles de contrat (data/<ns>/contracts/) : cibles, plafonds, livraison ≤ 0,5 × prix marchand.

Ébauche du socle : aucun contrôle pour l'instant.
"""

from __future__ import annotations

from .common import Context


def check(ctx: Context) -> list[tuple[str, str]]:
    return []
