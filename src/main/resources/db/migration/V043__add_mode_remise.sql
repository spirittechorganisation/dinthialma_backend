-- ===================================================================
-- V043 – Options de remise du jackpot (B33, lot 2)
--
-- `tontines.mode_remise` : option de remise par défaut de la tontine
-- (ATTENDRE | DISPONIBLE | AVANCE_COMMISSION | AVANCE_GESTIONNAIRE | CAISSE).
-- Reprise : DISPONIBLE pour les tontines existantes (remise de ce qui est
-- validé : fonctionnement historique, aucun changement de comportement).
--
-- `cycles_tontine` : option retenue à la remise du tour, jackpot prévu
-- (brut et net) à cette date, avance du gestionnaire encore à rembourser.
-- Les tours remis avant V043 gardent ces colonnes vides (traités comme
-- DISPONIBLE, sans jackpot prévu connu).
-- ===================================================================

ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS mode_remise VARCHAR(30);

UPDATE dinthialma.tontines SET mode_remise = 'DISPONIBLE' WHERE mode_remise IS NULL;

ALTER TABLE dinthialma.tontines ALTER COLUMN mode_remise SET NOT NULL;

ALTER TABLE dinthialma.cycles_tontine ADD COLUMN IF NOT EXISTS mode_remise       VARCHAR(30);
ALTER TABLE dinthialma.cycles_tontine ADD COLUMN IF NOT EXISTS montant_prevu     NUMERIC(12, 2);
ALTER TABLE dinthialma.cycles_tontine ADD COLUMN IF NOT EXISTS montant_net_prevu NUMERIC(12, 2);
ALTER TABLE dinthialma.cycles_tontine ADD COLUMN IF NOT EXISTS montant_avance    NUMERIC(12, 2);
