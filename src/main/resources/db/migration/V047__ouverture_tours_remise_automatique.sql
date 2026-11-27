-- ===================================================================
-- V047 – Tours ouverts à leur date et remise automatique (B33, lot 3)
--
-- `tontines.ouverture_tours` (A_LA_REMISE | A_LA_DATE) : A_LA_REMISE =
-- le tour suivant s'ouvre à la remise du précédent (fonctionnement
-- historique) ; A_LA_DATE = chaque tour s'ouvre aussi à sa date.
-- Reprise : A_LA_REMISE pour les tontines existantes (comportement
-- inchangé) ; une nouvelle tontine est A_LA_DATE (valeur Java).
--
-- `tontines.remise_automatique` : remise du jackpot à l'échéance du
-- tour (rotatives), avec l'option de remise par défaut. Reprise : false.
--
-- `cycles_tontine.remise_auto_signalee_at` : remise automatique
-- impossible déjà signalée au gestionnaire pour ce tour (une seule fois).
-- Pas de DEFAULT ni de CHECK : valeurs fournies par l'application.
-- ===================================================================

ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS ouverture_tours VARCHAR(20);
UPDATE dinthialma.tontines SET ouverture_tours = 'A_LA_REMISE' WHERE ouverture_tours IS NULL;
ALTER TABLE dinthialma.tontines ALTER COLUMN ouverture_tours SET NOT NULL;

ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS remise_automatique BOOLEAN;
UPDATE dinthialma.tontines SET remise_automatique = FALSE WHERE remise_automatique IS NULL;
ALTER TABLE dinthialma.tontines ALTER COLUMN remise_automatique SET NOT NULL;

ALTER TABLE dinthialma.cycles_tontine ADD COLUMN IF NOT EXISTS remise_auto_signalee_at TIMESTAMP;

-- Recherche des tours à ouvrir (statut, date de début) et à remettre (statut, échéance)
CREATE INDEX IF NOT EXISTS idx_cycles_statut_date_debut ON dinthialma.cycles_tontine (statut, date_debut);
CREATE INDEX IF NOT EXISTS idx_cycles_statut_echeance ON dinthialma.cycles_tontine (statut, echeance_at);
