"""Contrôles de contenu de l'économie (J4), appelés par ``tools/check_economy.py``.

Chaque module de contenu expose ``check(ctx: common.Context) -> list[tuple[str, str]]`` et renvoie ses
erreurs ``(sujet, message)`` ; il peut aussi signaler des avertissements par ``ctx.warn``. Le pilote fait
lui-même les contrôles du socle (tags d'interdiction, recettes, identifiants enregistrés).

Propriétaires : ``merchants`` (WP1), ``crates`` et ``daily`` (WP2), ``contracts`` (WP3),
``enterprises`` (WP4) ; ``common`` et ce fichier (WP0).
"""

# Ordre d'exécution des modules de contenu.
MODULES = ("merchants", "crates", "daily", "contracts", "enterprises")
