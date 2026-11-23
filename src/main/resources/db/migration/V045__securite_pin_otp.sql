-- ===================================================================
-- V045 – Sécurité du PIN et des OTP (SE07)
--
-- `users.pin_echecs_cumules` : échecs de PIN depuis le dernier succès (ou
-- depuis la configuration du PIN). Le verrou de 15 min remet `pin_attempts`
-- à zéro, pas ce compteur : à 10, le PIN est invalidé et le retour au mot
-- de passe (ou à l'OTP) est obligatoire.
--
-- `otp_verifications.tentatives`, `phone_change_requests.tentatives` :
-- essais faux sur un code (5 au plus, puis le code est invalidé).
--
-- `envois_otp` : journal des demandes d'OTP par numéro, pour limiter à 3
-- envois par heure, tous motifs confondus.
--
-- Pas de DEFAULT ni de CHECK (valeurs fournies par l'application).
-- ===================================================================

ALTER TABLE dinthialma.users ADD COLUMN IF NOT EXISTS pin_echecs_cumules INTEGER;
UPDATE dinthialma.users SET pin_echecs_cumules = 0 WHERE pin_echecs_cumules IS NULL;
ALTER TABLE dinthialma.users ALTER COLUMN pin_echecs_cumules SET NOT NULL;

ALTER TABLE dinthialma.otp_verifications ADD COLUMN IF NOT EXISTS tentatives INTEGER;
UPDATE dinthialma.otp_verifications SET tentatives = 0 WHERE tentatives IS NULL;
ALTER TABLE dinthialma.otp_verifications ALTER COLUMN tentatives SET NOT NULL;

ALTER TABLE dinthialma.phone_change_requests ADD COLUMN IF NOT EXISTS tentatives INTEGER;
UPDATE dinthialma.phone_change_requests SET tentatives = 0 WHERE tentatives IS NULL;
ALTER TABLE dinthialma.phone_change_requests ALTER COLUMN tentatives SET NOT NULL;

CREATE TABLE IF NOT EXISTS dinthialma.envois_otp
(
    id         UUID        NOT NULL,
    phone      VARCHAR(30) NOT NULL,
    purpose    VARCHAR(30) NOT NULL,
    created_at TIMESTAMP   NOT NULL,
    updated_at TIMESTAMP   NOT NULL,

    CONSTRAINT pk_envois_otp PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_envois_otp_phone_date ON dinthialma.envois_otp (phone, created_at);
