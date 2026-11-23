-- ===================================================================
-- V044 – Retraits anticipés d'une épargne événementielle (B42, lot 1)
--
-- Un membre reprend tout ou partie de son épargne avant la fin. Au lot 1,
-- le gestionnaire saisit le retrait déjà remis (espèces, Wave…), avec sa
-- date réelle (report du cahier possible) : statut REMIS, origine
-- GESTIONNAIRE. Les statuts DEMANDE / ACCEPTE / REFUSE et l'origine
-- MEMBRE serviront au lot 2 (demande du membre).
--
-- solde = Σ cotisations VALIDE − Σ (retraits REMIS : montant + frais)
--
-- `mouvement_id` est réservé au journal des caisses (B29), sans FK.
-- Pas de DEFAULT ni de CHECK : valeurs fournies par l'application.
-- ===================================================================

CREATE TABLE IF NOT EXISTS dinthialma.retraits_epargne
(
    id                    UUID          NOT NULL,
    tontine_id            UUID          NOT NULL,
    membre_id             UUID          NOT NULL,
    nature                VARCHAR(20)   NOT NULL,
    montant               NUMERIC(12,2) NOT NULL,
    frais                 NUMERIC(12,2) NOT NULL,
    statut                VARCHAR(20)   NOT NULL,
    origine               VARCHAR(20)   NOT NULL,
    methode_paiement      VARCHAR(50),
    reference_transaction VARCHAR(100),
    motif                 VARCHAR(500),
    date_remise           TIMESTAMP,
    traite_par            UUID,
    motif_annulation      VARCHAR(500),
    annule_par            UUID,
    date_annulation       TIMESTAMP,
    mouvement_id          UUID,
    created_at            TIMESTAMP     NOT NULL,
    updated_at            TIMESTAMP     NOT NULL,
    deleted_at            TIMESTAMP,

    CONSTRAINT pk_retraits_epargne PRIMARY KEY (id),
    CONSTRAINT fk_retrait_tontine    FOREIGN KEY (tontine_id) REFERENCES dinthialma.tontines (id),
    CONSTRAINT fk_retrait_membre     FOREIGN KEY (membre_id)  REFERENCES dinthialma.tontine_membres (id),
    CONSTRAINT fk_retrait_traite_par FOREIGN KEY (traite_par) REFERENCES dinthialma.users (id),
    CONSTRAINT fk_retrait_annule_par FOREIGN KEY (annule_par) REFERENCES dinthialma.users (id)
);

CREATE INDEX IF NOT EXISTS idx_retrait_tontine_statut ON dinthialma.retraits_epargne (tontine_id, statut);
CREATE INDEX IF NOT EXISTS idx_retrait_membre ON dinthialma.retraits_epargne (membre_id);

-- Membre « soldé » après un retrait total : plus de cotisation, ni de rappel (pas de nouveau statut).
ALTER TABLE dinthialma.tontine_membres ADD COLUMN IF NOT EXISTS solde_le TIMESTAMP;

-- Part finale : retraits déduits (reprise : 0 pour les distributions déjà faites).
ALTER TABLE dinthialma.distributions_evenementielles ADD COLUMN IF NOT EXISTS montant_retire NUMERIC(12,2);
UPDATE dinthialma.distributions_evenementielles SET montant_retire = 0 WHERE montant_retire IS NULL;
ALTER TABLE dinthialma.distributions_evenementielles ALTER COLUMN montant_retire SET NOT NULL;

-- Règle de retrait de la tontine (INTERDIT | LIBRE). Reprise : INTERDIT pour toutes les tontines
-- existantes (comportement inchangé) ; une nouvelle événementielle est LIBRE (valeur Java).
ALTER TABLE dinthialma.tontines ADD COLUMN IF NOT EXISTS regle_retrait VARCHAR(20);
UPDATE dinthialma.tontines SET regle_retrait = 'INTERDIT' WHERE regle_retrait IS NULL;
ALTER TABLE dinthialma.tontines ALTER COLUMN regle_retrait SET NOT NULL;
