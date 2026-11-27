-- ===================================================================
-- V048 – Reprise d'une tontine déjà commencée (B41, lot 1)
--
-- Un gestionnaire qui tenait sa tontine sur un cahier reporte le solde
-- de départ de chaque membre. Les soldes sont des cotisations VALIDE,
-- origine REPRISE, rattachées à un « tour de reprise » (numero_cycle 0,
-- TERMINE, sans échéance) qui couvre la période du cahier : elles entrent
-- dans les soldes, les récapitulatifs et la distribution finale, sans
-- rappel, ponctualité ni retard.
--
-- `tontines.date_debut_reelle` : date de début du cahier (affichée).
-- `tontines.reprise_figee_le`  : reprise figée (plus modifiable), à la
--   demande du gestionnaire, au premier retrait ou à la distribution.
-- `cotisations.origine` (DECLARATION | SAISIE | REPRISE). Reprise :
--   DECLARATION si la cotisation a été enregistrée par le membre
--   lui-même, SAISIE sinon.
-- Pas de DEFAULT ni de CHECK : valeurs fournies par l'application.
-- ===================================================================

ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS date_debut_reelle DATE;
ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS reprise_figee_le TIMESTAMP;

ALTER TABLE dinthialma.cotisations ADD COLUMN IF NOT EXISTS origine VARCHAR(20);

UPDATE dinthialma.cotisations c
SET origine = CASE WHEN c.enregistre_par = m.user_id THEN 'DECLARATION' ELSE 'SAISIE' END
FROM dinthialma.tontine_membres m
WHERE m.id = c.membre_id
  AND c.origine IS NULL;

ALTER TABLE dinthialma.cotisations ALTER COLUMN origine SET NOT NULL;
