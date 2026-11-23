package com.africa.dinthialma_backend.auth.service.impl;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.auth.repository.UserSessionRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.KeycloakAuthService;
import com.africa.dinthialma_backend.auth.service.interfaces.UserSessionService;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.Constants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.util.TokenEncryptionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion des sessions PIN – crée, met à jour et révoque les sessions WEB/MOBILE.
 *
 * <p>Le refresh token Keycloak est chiffré avec AES-256-GCM avant persistance. La clé est fournie
 * via {@code app.security.token-encryption-key}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionServiceImpl implements UserSessionService {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TABLE = "user_sessions";
  private static final String CHAMP_CONFIANCE = "confiance";

  private final UserSessionRepository userSessionRepository;
  private final KeycloakAuthService keycloakAuthService;
  private final AuditService auditService;

  @Value("${app.security.token-encryption-key}")
  private String tokenEncryptionKey;

  // ─── Création / mise à jour ────────────────────────────────────────

  @Override
  @Transactional
  public UserSession createOrUpdateSession(
      User user, ClientType clientType, String refreshToken, String deviceInfo)
      throws CustomException {

    log.debug("createOrUpdateSession – userId={} clientType={}", user.getId(), clientType);

    // Chiffrement du refresh token avant stockage
    String encryptedToken = TokenEncryptionUtil.encrypt(refreshToken, tokenEncryptionKey);

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiresAt = computeExpiry(clientType, now);

    // Chercher une session existante (active ou expirée) pour ce couple user/client
    Optional<UserSession> existing =
        userSessionRepository.findByUserIdAndClientType(user.getId(), clientType);

    UserSession session;
    if (existing.isPresent()) {
      // Mise à jour de la session existante (refresh token + TTL)
      session = existing.get();
      session.setRefreshTokenHash(encryptedToken);
      session.setLastUsedAt(now);
      session.setExpiresAt(expiresAt);
      if (deviceInfo != null && !deviceInfo.isBlank()) {
        session.setDeviceInfo(deviceInfo);
      }
      log.debug("Session existante mise à jour – sessionId={}", session.getId());
    } else {
      // Nouvelle session
      session =
          UserSession.builder()
              .user(user)
              .clientType(clientType)
              .refreshTokenHash(encryptedToken)
              .deviceInfo(deviceInfo)
              .lastUsedAt(now)
              .expiresAt(expiresAt)
              .build();
      log.debug("Nouvelle session créée pour userId={} clientType={}", user.getId(), clientType);
    }

    UserSession saved = userSessionRepository.save(session);
    log.info(
        "Session PIN {} – userId={} clientType={} expiresAt={}",
        existing.isPresent() ? "mise à jour" : "créée",
        user.getId(),
        clientType,
        expiresAt);
    return saved;
  }

  // ─── Recherche ────────────────────────────────────────────────────

  @Override
  public Optional<UserSession> findActiveSession(UUID userId, ClientType clientType) {
    return userSessionRepository.findByUserIdAndClientTypeAndExpiresAtAfter(
        userId, clientType, LocalDateTime.now());
  }

  @Override
  public Optional<UserSession> findAnyActiveSession(UUID userId) {
    return userSessionRepository.findTopByUserIdAndExpiresAtAfterOrderByExpiresAtDesc(
        userId, LocalDateTime.now());
  }

  // ─── Révocation ───────────────────────────────────────────────────

  @Override
  @Transactional
  public void revokeSession(UUID sessionId) throws CustomException {
    UserSession session =
        userSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new NotFoundException("Session introuvable"));

    revoquerConfiance(session);
    userSessionRepository.delete(session);
    log.info("Session révoquée – sessionId={}", sessionId);
  }

  @Override
  @Transactional
  public void revokeAllSessions(UUID userId) {
    // Jetons hors ligne révoqués chez Keycloak (B53), pas seulement effacés en base
    userSessionRepository.findByUserIdAndConfianceTrue(userId).forEach(this::revoquerConfiance);
    userSessionRepository.deleteAllByUserId(userId);
    log.info("Toutes les sessions révoquées – userId={}", userId);
  }

  // ─── Déchiffrement ────────────────────────────────────────────────

  @Override
  public String decryptRefreshToken(UserSession session) throws CustomException {
    try {
      return TokenEncryptionUtil.decrypt(session.getRefreshTokenHash(), tokenEncryptionKey);
    } catch (Exception e) {
      log.error("Impossible de déchiffrer le refresh token pour sessionId={}", session.getId());
      throw new CustomException(
          org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
          "Erreur lors de la récupération de la session");
    }
  }

  // ─── Sessions par appareil (B53) ─────────────────────────────────────

  @Override
  @Transactional
  public UserSession enregistrerSessionAppareil(
      User user,
      ClientType clientType,
      String appareilId,
      String refreshToken,
      String deviceInfo,
      boolean confiance)
      throws CustomException {
    LocalDateTime now = LocalDateTime.now();
    Optional<UserSession> existante =
        userSessionRepository.findByUserIdAndAppareilId(user.getId(), appareilId);
    UserSession session =
        existante.orElseGet(() -> UserSession.builder().user(user).appareilId(appareilId).build());
    // Le jeton hors ligne d'une AUTRE session Keycloak (nouvelle connexion par mot de passe sur
    // cet appareil) est révoqué avant d'être remplacé. Une rotation (même sid) ne révoque rien :
    // ce serait supprimer la session hors ligne qui vient d'être rafraîchie.
    if (existante.isPresent() && session.isConfiance()) {
      String ancien = decryptRefreshToken(session);
      String ancienSid = sid(ancien);
      if (ancienSid == null || !ancienSid.equals(sid(refreshToken))) {
        keycloakAuthService.revoquerJeton(ancien);
      }
    }
    boolean creation = !session.isConfiance() && confiance;
    session.setClientType(clientType);
    session.setRefreshTokenHash(TokenEncryptionUtil.encrypt(refreshToken, tokenEncryptionKey));
    session.setConfiance(confiance);
    session.setLastUsedAt(now);
    session.setExpiresAt(computeExpiry(clientType, now));
    if (deviceInfo != null && !deviceInfo.isBlank()) {
      session.setDeviceInfo(deviceInfo);
    }
    UserSession saved = userSessionRepository.save(session);
    if (creation) {
      auditService.log(user, TABLE, saved.getId(), CHAMP_CONFIANCE, null, "CREEE");
    }
    log.info(
        "Session d'appareil {} – userId={} clientType={} confiance={}",
        existante.isPresent() ? "mise à jour" : "créée",
        user.getId(),
        clientType,
        confiance);
    return saved;
  }

  @Override
  public Optional<UserSession> trouverSessionAppareil(UUID userId, String appareilId) {
    return userSessionRepository
        .findByUserIdAndAppareilId(userId, appareilId)
        .filter(s -> s.getExpiresAt() != null && s.getExpiresAt().isAfter(LocalDateTime.now()));
  }

  @Override
  @Transactional
  public void synchroniserJeton(UUID userId, String ancienRefreshToken, String nouveauRefreshToken)
      throws CustomException {
    byte[] ancien = ancienRefreshToken.getBytes(StandardCharsets.UTF_8);
    for (UserSession session : userSessionRepository.findByUserIdOrderByLastUsedAtDesc(userId)) {
      byte[] stocke = decryptRefreshToken(session).getBytes(StandardCharsets.UTF_8);
      if (MessageDigest.isEqual(stocke, ancien)) {
        session.setRefreshTokenHash(
            TokenEncryptionUtil.encrypt(nouveauRefreshToken, tokenEncryptionKey));
        session.setLastUsedAt(LocalDateTime.now());
        userSessionRepository.save(session);
        log.info(
            "Jeton de session renouvelé après rafraîchissement – sessionId={}", session.getId());
        return;
      }
    }
  }

  @Override
  public void tracerRejeu(UserSession session) {
    if (session.isConfiance()) {
      auditService.log(
          session.getUser(), TABLE, session.getId(), CHAMP_CONFIANCE, "CREEE", "REJOUEE");
    }
  }

  /** Session de confiance : jeton hors ligne révoqué chez Keycloak, révocation auditée. */
  private void revoquerConfiance(UserSession session) {
    if (!session.isConfiance()) {
      return;
    }
    try {
      keycloakAuthService.revoquerJeton(decryptRefreshToken(session));
    } catch (CustomException e) {
      log.warn("Jeton hors ligne illisible, non révoqué – sessionId={}", session.getId());
    }
    auditService.log(
        session.getUser(), TABLE, session.getId(), CHAMP_CONFIANCE, "CREEE", "REVOQUEE");
  }

  // ─── Helpers ──────────────────────────────────────────────────────

  /**
   * Claim {@code sid} (session Keycloak) d'un jeton, lu sans vérifier la signature ; null sinon.
   */
  static String sid(String jeton) {
    try {
      String[] parts = jeton.split("\\.");
      if (parts.length < 2) {
        return null;
      }
      JsonNode payload =
          JSON.readTree(
              new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      return payload.hasNonNull("sid") ? payload.get("sid").asText() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private LocalDateTime computeExpiry(ClientType clientType, LocalDateTime from) {
    return switch (clientType) {
      case WEB -> from.plusDays(Constants.Session.WEB_TTL_DAYS);
      case MOBILE -> from.plusDays(Constants.Session.MOBILE_TTL_DAYS);
    };
  }
}
