-- =====================================================================
-- Contrôle B43 : cotisations VALIDÉES non distribuées (tontines ÉVÉNEMENTIELLES)
--
-- LECTURE SEULE : uniquement des SELECT, à lancer dans pgAdmin sur la base
-- de production (schéma dinthialma). Aucune écriture.
--
-- Bug corrigé par B43 : à la distribution finale, seuls les membres ACTIF
-- étaient retenus. Les cotisations VALIDE des membres SORTI, SUSPENDU ou
-- supprimés (deleted_at) n'étaient attribuées à personne.
--
-- Requête 1 : pour chaque tontine événementielle déjà distribuée (au moins
-- une ligne dans distributions_evenementielles), compare par membre le total
-- des cotisations VALIDE et la mise distribuée (montant_cotise), et liste
-- les écarts.
--
-- Requête 2 : tontines événementielles dont tous les sous-cycles sont
-- terminés, sans aucune ligne de distribution, mais avec des cotisations
-- VALIDE. Ce sont soit des tontines distribuées avant V034 (historique non
-- enregistré, faux positif), soit des tontines où seuls des membres non
-- actifs avaient cotisé (distribution vide à cause du bug).
--
-- Requête 3 : synthèse par tontine (total encaissé, total distribué, écart).
-- =====================================================================

-- ─── Requête 1 : écarts par membre ───────────────────────────────────────
WITH tontines_distribuees AS (
    SELECT DISTINCT d.tontine_id
    FROM dinthialma.distributions_evenementielles d
    WHERE d.deleted_at IS NULL
),
valide_par_membre AS (
    SELECT c.tontine_id, c.membre_id, SUM(c.montant) AS total_valide
    FROM dinthialma.cotisations c
    JOIN tontines_distribuees td ON td.tontine_id = c.tontine_id
    WHERE c.deleted_at IS NULL
      AND c.statut = 'VALIDE'
    GROUP BY c.tontine_id, c.membre_id
),
distribue_par_membre AS (
    SELECT d.tontine_id, d.membre_id,
           SUM(d.montant_cotise) AS total_distribue,
           SUM(d.montant_net)    AS net_distribue
    FROM dinthialma.distributions_evenementielles d
    WHERE d.deleted_at IS NULL
    GROUP BY d.tontine_id, d.membre_id
)
SELECT t.id                                   AS tontine_id,
       t.nom                                  AS tontine,
       t.statut                               AS statut_tontine,
       m.id                                   AS membre_id,
       u.first_name || ' ' || u.last_name     AS membre,
       u.phone                                AS telephone,
       m.statut                               AS statut_membre,
       m.deleted_at                           AS membre_supprime_le,
       COALESCE(v.total_valide, 0)            AS total_cotisations_valide,
       COALESCE(dp.total_distribue, 0)        AS mise_distribuee,
       COALESCE(dp.net_distribue, 0)          AS net_distribue,
       COALESCE(v.total_valide, 0) - COALESCE(dp.total_distribue, 0) AS montant_non_distribue
FROM valide_par_membre v
FULL OUTER JOIN distribue_par_membre dp
       ON dp.tontine_id = v.tontine_id AND dp.membre_id = v.membre_id
JOIN dinthialma.tontines t        ON t.id = COALESCE(v.tontine_id, dp.tontine_id)
JOIN dinthialma.tontine_membres m ON m.id = COALESCE(v.membre_id, dp.membre_id)
JOIN dinthialma.users u           ON u.id = m.user_id
WHERE t.tontine_type = 'EVENEMENTIELLE'
  AND COALESCE(v.total_valide, 0) <> COALESCE(dp.total_distribue, 0)
ORDER BY t.nom, montant_non_distribue DESC;

-- ─── Requête 2 : tontines terminées sans aucune distribution enregistrée ──
SELECT t.id          AS tontine_id,
       t.nom         AS tontine,
       t.statut      AS statut_tontine,
       t.created_at  AS creee_le,
       SUM(c.montant) AS total_cotisations_valide,
       COUNT(DISTINCT c.membre_id) AS membres_ayant_cotise
FROM dinthialma.tontines t
JOIN dinthialma.cotisations c
       ON c.tontine_id = t.id AND c.deleted_at IS NULL AND c.statut = 'VALIDE'
WHERE t.tontine_type = 'EVENEMENTIELLE'
  AND t.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM dinthialma.distributions_evenementielles d
                  WHERE d.tontine_id = t.id AND d.deleted_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM dinthialma.cycles_tontine cy
                  WHERE cy.tontine_id = t.id AND cy.deleted_at IS NULL
                    AND cy.statut <> 'TERMINE')
  AND EXISTS (SELECT 1 FROM dinthialma.cycles_tontine cy
              WHERE cy.tontine_id = t.id AND cy.deleted_at IS NULL)
GROUP BY t.id, t.nom, t.statut, t.created_at
ORDER BY t.created_at;

-- ─── Requête 3 : synthèse par tontine distribuée ─────────────────────────
SELECT t.id   AS tontine_id,
       t.nom  AS tontine,
       (SELECT COALESCE(SUM(c.montant), 0) FROM dinthialma.cotisations c
         WHERE c.tontine_id = t.id AND c.deleted_at IS NULL AND c.statut = 'VALIDE')
              AS total_encaisse_valide,
       (SELECT COALESCE(SUM(d.montant_cotise), 0) FROM dinthialma.distributions_evenementielles d
         WHERE d.tontine_id = t.id AND d.deleted_at IS NULL)
              AS total_mises_distribuees,
       (SELECT COALESCE(SUM(c.montant), 0) FROM dinthialma.cotisations c
         WHERE c.tontine_id = t.id AND c.deleted_at IS NULL AND c.statut = 'VALIDE')
     - (SELECT COALESCE(SUM(d.montant_cotise), 0) FROM dinthialma.distributions_evenementielles d
         WHERE d.tontine_id = t.id AND d.deleted_at IS NULL)
              AS ecart
FROM dinthialma.tontines t
WHERE t.tontine_type = 'EVENEMENTIELLE'
  AND EXISTS (SELECT 1 FROM dinthialma.distributions_evenementielles d
              WHERE d.tontine_id = t.id AND d.deleted_at IS NULL)
ORDER BY ecart DESC, t.nom;
