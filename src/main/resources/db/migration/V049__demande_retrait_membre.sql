-- ===================================================================
-- V049 – Demande de retrait par le membre (B42, lot 2)
--
-- Le membre demande un retrait (DEMANDE, origine MEMBRE) ; le gestionnaire
-- l'accepte (ACCEPTE, facultatif), le remet (REMIS) ou le refuse (REFUSE,
-- motif) ; le membre peut l'annuler tant qu'il n'est pas remis (ANNULE).
--
-- Traçabilité de l'acceptation et du refus ; une seule demande en cours
-- (DEMANDE ou ACCEPTE) par membre, garantie par un index unique partiel.
-- Pas de DEFAULT ni de CHECK : valeurs fournies par l'application.
-- ===================================================================

ALTER TABLE dinthialma.retraits_epargne ADD COLUMN IF NOT EXISTS accepte_par UUID;
ALTER TABLE dinthialma.retraits_epargne ADD COLUMN IF NOT EXISTS date_acceptation TIMESTAMP;
ALTER TABLE dinthialma.retraits_epargne ADD COLUMN IF NOT EXISTS motif_refus VARCHAR(500);
ALTER TABLE dinthialma.retraits_epargne ADD COLUMN IF NOT EXISTS refuse_par UUID;
ALTER TABLE dinthialma.retraits_epargne ADD COLUMN IF NOT EXISTS date_refus TIMESTAMP;

ALTER TABLE dinthialma.retraits_epargne
    DROP CONSTRAINT IF EXISTS fk_retrait_accepte_par;
ALTER TABLE dinthialma.retraits_epargne
    ADD CONSTRAINT fk_retrait_accepte_par FOREIGN KEY (accepte_par) REFERENCES dinthialma.users (id);
ALTER TABLE dinthialma.retraits_epargne
    DROP CONSTRAINT IF EXISTS fk_retrait_refuse_par;
ALTER TABLE dinthialma.retraits_epargne
    ADD CONSTRAINT fk_retrait_refuse_par FOREIGN KEY (refuse_par) REFERENCES dinthialma.users (id);

-- Une seule demande en cours par membre
CREATE UNIQUE INDEX IF NOT EXISTS uq_retrait_en_cours_membre
    ON dinthialma.retraits_epargne (membre_id)
    WHERE deleted_at IS NULL AND statut IN ('DEMANDE', 'ACCEPTE');
