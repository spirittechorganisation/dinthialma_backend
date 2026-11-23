package com.africa.dinthialma_backend.auth.service.interfaces;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.common.exception.CustomException;
import java.util.Optional;
import java.util.UUID;

/**
 * Service de gestion des sessions PIN (WEB + MOBILE).
 *
 * <p>Une session est créée lors de la première connexion complète (mot de passe). Elle est utilisée
 * pour la connexion rapide PIN : le refresh token Keycloak est stocké chiffré et échangé contre un
 * nouveau JWT à chaque connexion PIN.
 *
 * <p>Une session par (utilisateur × clientType) est maintenue. Si une session existe déjà pour ce
 * couple, elle est mise à jour (refresh token + TTL).
 */
public interface UserSessionService {

  /**
   * Crée ou met à jour la session d'un utilisateur pour un type de client.
   *
   * @param user propriétaire de la session
   * @param clientType WEB ou MOBILE
   * @param refreshToken refresh token Keycloak en clair (sera chiffré avant persistance)
   * @param deviceInfo user-agent (WEB) ou device_id (MOBILE), optionnel
   * @return la session persistée
   */
  UserSession createOrUpdateSession(
      User user, ClientType clientType, String refreshToken, String deviceInfo)
      throws CustomException;

  /**
   * Recherche la session active d'un utilisateur pour un type de client.
   *
   * @return session si elle existe et n'est pas expirée, sinon {@link Optional#empty()}
   */
  Optional<UserSession> findActiveSession(UUID userId, ClientType clientType);

  /**
   * Recherche n'importe quelle session active de l'utilisateur, tous types confondus. Utilisé en
   * fallback PIN quand le clientType demandé n'a pas de session mais un autre en a une.
   *
   * @return la session active la plus récente, sinon {@link Optional#empty()}
   */
  Optional<UserSession> findAnyActiveSession(UUID userId);

  /**
   * Révoque une session spécifique (déconnexion d'un appareil).
   *
   * @param sessionId identifiant de la session à révoquer
   */
  void revokeSession(UUID sessionId) throws CustomException;

  /**
   * Révoque toutes les sessions d'un utilisateur. Utilisé lors du reset PIN, reset mot de passe ou
   * désactivation du compte.
   *
   * @param userId identifiant de l'utilisateur
   */
  void revokeAllSessions(UUID userId);

  /**
   * Décrypte et retourne le refresh token en clair pour un appel Keycloak.
   *
   * @param session session dont on veut extraire le token
   * @return refresh token en clair
   */
  String decryptRefreshToken(UserSession session) throws CustomException;

  // ─── Sessions par appareil (B53) ─────────────────────────────────────

  /**
   * Crée ou met à jour la session de cet appareil ({@code appareilId} stable, gardé par le front).
   * Le refresh token est chiffré ; avec {@code confiance}, c'est un jeton hors ligne. Si la session
   * de l'appareil portait déjà un autre jeton hors ligne, il est révoqué chez Keycloak avant d'être
   * remplacé (rotation, pas de session hors ligne orpheline).
   */
  UserSession enregistrerSessionAppareil(
      User user,
      ClientType clientType,
      String appareilId,
      String refreshToken,
      String deviceInfo,
      boolean confiance)
      throws CustomException;

  /** Session active de cet appareil (B53). */
  Optional<UserSession> trouverSessionAppareil(UUID userId, String appareilId);

  /**
   * Après un {@code POST /v1/auth/refresh} : si l'ancien refresh token est celui d'une session de
   * l'utilisateur, la session reçoit le nouveau (rotation), pour que le PIN rejoue toujours le
   * jeton valide.
   */
  void synchroniserJeton(UUID userId, String ancienRefreshToken, String nouveauRefreshToken)
      throws CustomException;

  /** Trace le rejeu d'une session de confiance par le PIN (audit, sans valeur de jeton). */
  void tracerRejeu(UserSession session);
}
