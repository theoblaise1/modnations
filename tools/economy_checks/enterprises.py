# Propriété : WP4
"""Contrôle des secteurs d'entreprise (``data/<ns>/enterprises/``), mêmes règles que le chargement en jeu
(``enterprise/SectorDefinition`` et ``SectorRegistry``) :

  * un fichier par secteur de ``enterprise/Sector.java`` (agricole, minier, logistique, industriel), dans l'espace
    de noms du mod ; tout autre fichier est ignoré en jeu, donc refusé ici ; un secteur absent serait indisponible ;
  * champs connus seulement ; ``domain`` = domaine de recherche ; ``requires`` = paliers existants ;
  * ``bonus_per_tier`` dans [0 ; 0,2] ; ``money_per_worker`` entier de 0 à 1 000 000 ;
  * au plus 8 produits, objets uniques, ``per_worker`` dans ]0 ; 64] ; le secteur produit quelque chose ;
  * aucun produit de ``#sovereignfrontier:interdit_caisses`` (donc de ``#restreint``) ni clé de caisse.
"""

from __future__ import annotations

import re
from pathlib import Path

from .common import (
    BANNED_FROM_LOOT, BONUS_PER_TIER_MAX, JAVA_REL, NAMESPACE, PER_WORKER_MAX, Context, ID_RE, is_int, is_number, show,
)

# Constantes de SectorDefinition (jamais lues dans la configuration du serveur).
MONEY_PER_WORKER_MAX = 1_000_000
MAX_PRODUCTS = 8
FIELDS = {"requires", "domain", "bonus_per_tier", "money_per_worker", "products"}
PRODUCT_FIELDS = {"item", "per_worker", "requires"}
DOMAIN_REL = JAVA_REL / "research" / "ResearchDomain.java"


def _domains(root: Path) -> set[str]:
    path = root / DOMAIN_REL
    if not path.is_file():
        return set()
    return set(re.findall(r'^\s*[A-Z_]+\("([a-z_]+)",\s*\d+\)', path.read_text(encoding="utf-8"), re.M))


def _check_requires(ctx: Context, value: object, subject: str, where: str) -> list[tuple[str, str]]:
    errors: list[tuple[str, str]] = []
    if not isinstance(value, list):
        return [(subject, f"{where} : liste d'identifiants de palier attendue, reçu {show(value)}")]
    for i, req in enumerate(value):
        if not isinstance(req, str) or not ID_RE.match(req):
            errors.append((subject, f"{where}[{i}] : identifiant de palier invalide {show(req)}"))
        elif ctx.research_ids and req not in ctx.research_ids:
            errors.append((subject, f"{where}[{i}] : palier de recherche inconnu « {req} »"))
    return errors


def _check_sector(ctx: Context, data: object, subject: str, domains: set[str]) -> list[tuple[str, str]]:
    if not isinstance(data, dict):
        return [(subject, "objet JSON attendu")]
    errors: list[tuple[str, str]] = []
    unknown = sorted(set(data) - FIELDS)
    if unknown:
        errors.append((subject, f"champ(s) inconnu(s) : {', '.join(unknown)} (le jeu rejette le fichier)"))
    errors += _check_requires(ctx, data.get("requires", []), subject, "requires")

    domain = data.get("domain")
    if "domain" not in data:
        errors.append((subject, "« domain » manquant"))
    elif not isinstance(domain, str) or (domains and domain not in domains):
        errors.append((subject, f"domaine de recherche inconnu {show(domain)} ({', '.join(sorted(domains))})"))

    bonus = data.get("bonus_per_tier", 0.0)
    if not is_number(bonus) or not 0.0 <= bonus <= BONUS_PER_TIER_MAX:
        errors.append((subject, f"bonus_per_tier hors de [0 ; {BONUS_PER_TIER_MAX}] : {show(bonus)}"))

    money = data.get("money_per_worker", 0)
    if not is_int(money) or not 0 <= money <= MONEY_PER_WORKER_MAX:
        errors.append((subject, f"money_per_worker : entier de 0 à {MONEY_PER_WORKER_MAX} attendu, reçu {show(money)}"))

    products = data.get("products", [])
    if not isinstance(products, list):
        return errors + [(subject, f"products : liste attendue, reçu {show(products)}")]
    if len(products) > MAX_PRODUCTS:
        errors.append((subject, f"{len(products)} produits (maximum {MAX_PRODUCTS})"))
    if money == 0 and not products:
        errors.append((subject, "le secteur ne produit rien (ni money_per_worker, ni products)"))
    seen: set[str] = set()
    keys = ctx.key_items()
    for i, product in enumerate(products):
        where = f"products[{i}]"
        if not isinstance(product, dict):
            errors.append((subject, f"{where} : objet {{item, per_worker, requires?}} attendu"))
            continue
        extra = sorted(set(product) - PRODUCT_FIELDS)
        if extra:
            errors.append((subject, f"{where} : champ(s) inconnu(s) : {', '.join(extra)}"))
        item = product.get("item")
        if "item" not in product:
            errors.append((subject, f"{where} : « item » manquant"))
        elif ctx.check_item_id(item, subject, f"{where}.item"):
            if item == "minecraft:air":
                errors.append((subject, f"{where} : « minecraft:air » interdit"))
            if item in seen:
                errors.append((subject, f"{where} : produit en double « {item} »"))
            seen.add(item)
            if ctx.banned_from_loot(item):
                errors.append((subject, f"{where} : objet interdit « {item} » (#{BANNED_FROM_LOOT}) dans un produit d'entreprise"))
            if item in keys:
                errors.append((subject, f"{where} : clé de caisse « {item} » : aucune source automatique de clés"))
        per = product.get("per_worker")
        if not is_number(per) or not 0.0 < per <= PER_WORKER_MAX:
            errors.append((subject, f"{where} : per_worker hors de ]0 ; {PER_WORKER_MAX}] : {show(per)}"))
        errors += _check_requires(ctx, product.get("requires", []), subject, f"{where}.requires")
    return errors


def check(ctx: Context) -> list[tuple[str, str]]:
    errors: list[tuple[str, str]] = []
    domains = _domains(ctx.root)
    if not domains:
        ctx.warn(DOMAIN_REL.as_posix(), "domaines de recherche introuvables : « domain » non vérifié")
    if not ctx.research_ids:
        ctx.warn("research/", "aucun palier de recherche : « requires » non vérifié")
    found: set[str] = set()
    for path in ctx.data_files("enterprises", namespace=None):
        subject = ctx.rel(path)
        folder = path.parent
        namespace = folder.parent.name
        if folder.name != "enterprises" or namespace != NAMESPACE or path.stem not in ctx.sectors:
            errors.append((subject, f"fichier ignoré par le jeu : seuls data/{NAMESPACE}/enterprises/<secteur>.json "
                                    f"sont lus ({', '.join(ctx.sectors)})"))
            continue
        data = ctx.load(path)
        if data is None:
            continue
        found.add(path.stem)
        errors += _check_sector(ctx, data, subject, domains)
    for sector in ctx.sectors:
        if sector not in found:
            errors.append((f"data/{NAMESPACE}/enterprises/{sector}.json",
                           f"secteur « {sector} » absent : il serait indisponible en jeu"))
    return errors
