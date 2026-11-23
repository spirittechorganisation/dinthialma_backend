-- ===================================================================
-- V046 – Session longue par appareil de confiance (B53)
--
-- `appareil_id` : identifiant stable de l'appareil, généré une fois et
-- conservé par le front (application ou PWA). Une session par appareil
-- et par utilisateur. Les sessions existantes n'en ont pas : elles gardent
-- le fonctionnement d'avant (une session par type de client, sans jeton
-- hors ligne) jusqu'à la prochaine connexion par mot de passe.
--
-- `confiance` : la session porte un jeton hors ligne Keycloak (scope
-- offline_access), chiffré dans `refresh_token_hash`, que le PIN rejoue
-- sans dépendre de SSO Session Idle. Reprise : false.
--
-- Pas de DEFAULT ni de CHECK (valeurs fournies par l'application).
-- ===================================================================

ALTER TABLE dinthialma.user_sessions ADD COLUMN IF NOT EXISTS appareil_id VARCHAR(100);

ALTER TABLE dinthialma.user_sessions ADD COLUMN IF NOT EXISTS confiance BOOLEAN;
UPDATE dinthialma.user_sessions SET confiance = FALSE WHERE confiance IS NULL;
ALTER TABLE dinthialma.user_sessions ALTER COLUMN confiance SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_us_user_appareil
    ON dinthialma.user_sessions (user_id, appareil_id)
    WHERE appareil_id IS NOT NULL;
