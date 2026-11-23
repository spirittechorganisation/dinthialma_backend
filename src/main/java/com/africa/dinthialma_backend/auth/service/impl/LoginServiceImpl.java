package com.africa.dinthialma_backend.auth.service.impl;

import com.africa.dinthialma_backend.auth.codeList.AccountStatus;
import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.dto.LoginRequest;
import com.africa.dinthialma_backend.auth.dto.LoginResponse;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.KeycloakAuthService;
import com.africa.dinthialma_backend.auth.service.interfaces.LoginService;
import com.africa.dinthialma_backend.auth.service.interfaces.UserSessionService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.UnAuthorizedException;
import com.africa.dinthialma_backend.common.util.PhoneUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Résout le {@code username} (téléphone OU email) vers le username Keycloak exact, authentifie via
 * ROPC, puis crée/met à jour la session PIN en base.
 *
 * <p>Flux complet :
 *
 * <ol>
 *   <li>Normalisation du téléphone ({@link PhoneUtils#normalize}).
 *   <li>Recherche en base : par téléphone → par email.
 *   <li>Vérifications du compte (actif, non supprimé).
 *   <li>Appel Keycloak ROPC → tokens JWT.
 *   <li>Persistance de la session PIN (refresh token chiffré AES-256-GCM).
 * </ol>
 *
 * <p>Fallback : si l'utilisateur est absent de la base (bootstrap partiel), le login Keycloak est
 * tenté directement – les tokens sont retournés mais aucune session PIN n'est créée.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

  private final UserRepository userRepository;
  private final KeycloakAuthService keycloakAuthService;
  private final UserSessionService userSessionService;
  private final UserRoleAssignmentRepository roleAssignmentRepository;

  @Override
  @Transactional
  public LoginResponse login(LoginRequest request) throws CustomException {
    String identifier = PhoneUtils.normalize(request.getUsername().trim());
    log.debug("[Login] Identifiant normalisé : {}", identifier);

    // ── 1. Recherche en base ──────────────────────────────────────────
    Optional<User> maybeUser = findUser(identifier);

    if (maybeUser.isPresent()) {
      User user = maybeUser.get();

      if (user.getDeletedAt() != null) {
        log.warn("[Login] Compte supprimé – identifiant : {}", identifier);
        throw new UnAuthorizedException("Identifiants incorrects", ErrorCode.INVALID_CREDENTIALS);
      }
      if (user.getAccountStatus() == AccountStatus.PRE_ENROLLED) {
        log.warn(
            "[Login] Tentative de connexion sur compte PRE_ENROLLED – userId : {}", user.getId());
        throw new CustomException(
            HttpStatus.FORBIDDEN,
            ResponseMessageConstants.AUTH_ACCOUNT_NOT_ACTIVATED,
            ErrorCode.ACCOUNT_PRE_ENROLLED);
      }
      if (!user.isActive()) {
        log.warn("[Login] Compte désactivé – userId : {}", user.getId());
        throw new CustomException(
            HttpStatus.FORBIDDEN,
            ResponseMessageConstants.AUTH_ACCOUNT_DISABLED,
            ErrorCode.ACCOUNT_DISABLED);
      }

      // ── 2. Authentification Keycloak ──────────────────────────────
      // B53 : appareil identifié + PIN configuré + pas SUPER_ADMIN → jeton hors ligne
      boolean confiance = sessionDeConfiancePossible(user, request.getAppareilId());
      log.debug("[Login] username Keycloak : {}", user.getPhone());
      LoginResponse tokens =
          confiance
              ? keycloakAuthService.login(user.getPhone(), request.getPassword(), true)
              : keycloakAuthService.login(user.getPhone(), request.getPassword());

      // ── 3. Création / mise à jour de la session PIN ───────────────
      if (request.getAppareilId() != null) {
        userSessionService.enregistrerSessionAppareil(
            user,
            request.getClientType(),
            request.getAppareilId(),
            tokens.getRefreshToken(),
            request.getDeviceInfo(),
            confiance);
      } else {
        userSessionService.createOrUpdateSession(
            user, request.getClientType(), tokens.getRefreshToken(), request.getDeviceInfo());
      }

      tokens.setPinConfigured(user.getPinHash() != null);
      log.info(
          "[Login] Connexion réussie – userId={} clientType={}",
          user.getId(),
          request.getClientType());
      return tokens;
    }

    // ── Fallback : base vide / bootstrap partiel ──────────────────────
    // Keycloak reste la source de vérité mais aucune session PIN n'est créée.
    log.warn(
        "[Login] Utilisateur '{}' absent de la base locale – tentative directe Keycloak (pas de session PIN)",
        identifier);
    return keycloakAuthService.login(identifier, request.getPassword());
  }

  /**
   * Session de confiance (jeton hors ligne) possible : appareil identifié, PIN configuré, compte
   * autre que SUPER_ADMIN (session courte, 2FA prévue en SE16).
   */
  private boolean sessionDeConfiancePossible(User user, String appareilId) {
    return appareilId != null
        && user.getPinHash() != null
        && !roleAssignmentRepository.existsByUserIdAndRole(user.getId(), UserRole.SUPER_ADMIN);
  }

  // ─── Rafraîchissement ────────────────────────────────────────────────

  @Override
  @Transactional
  public LoginResponse rafraichir(String refreshToken) throws CustomException {
    LoginResponse tokens = keycloakAuthService.refreshToken(refreshToken);
    String keycloakId = extractSubFromJwt(refreshToken);
    if (keycloakId != null && tokens.getRefreshToken() != null) {
      Optional<User> user = userRepository.findByKeycloakId(keycloakId);
      if (user.isPresent()) {
        userSessionService.synchroniserJeton(
            user.get().getId(), refreshToken, tokens.getRefreshToken());
      }
    }
    return tokens;
  }

  // ─── Déconnexion ─────────────────────────────────────────────────────

  @Override
  @Transactional
  public void logout(String refreshToken, ClientType clientType, String appareilId) {
    if (appareilId == null) {
      logout(refreshToken, clientType);
      return;
    }
    String keycloakId = extractSubFromJwt(refreshToken);
    if (keycloakId == null) {
      log.warn("[Logout] keycloakId illisible dans le refresh token – session non supprimée");
      return;
    }
    userRepository
        .findByKeycloakId(keycloakId)
        .flatMap(user -> userSessionService.trouverSessionAppareil(user.getId(), appareilId))
        .ifPresent(this::supprimerSession);
  }

  private void supprimerSession(UserSession session) {
    try {
      userSessionService.revokeSession(session.getId());
      log.info("[Logout] Session de l'appareil supprimée – sessionId={}", session.getId());
    } catch (CustomException e) {
      log.warn("[Logout] Impossible de révoquer la session {}", session.getId());
    }
  }

  @Override
  @Transactional
  public void logout(String refreshToken, ClientType clientType) {
    String keycloakId = extractSubFromJwt(refreshToken);
    if (keycloakId == null) {
      log.warn(
          "[Logout] Impossible d'extraire le keycloakId du refresh token – session PIN non supprimée");
      return;
    }

    userRepository
        .findByKeycloakId(keycloakId)
        .ifPresentOrElse(
            user -> {
              if (clientType != null) {
                userSessionService
                    .findActiveSession(user.getId(), clientType)
                    .ifPresent(
                        s -> {
                          try {
                            userSessionService.revokeSession(s.getId());
                          } catch (CustomException e) {
                            log.warn("[Logout] Impossible de révoquer la session {}", s.getId());
                          }
                        });
              } else {
                userSessionService.revokeAllSessions(user.getId());
              }
              log.info(
                  "[Logout] Session(s) PIN supprimée(s) – userId={} clientType={}",
                  user.getId(),
                  clientType);
            },
            () -> log.warn("[Logout] Utilisateur keycloakId={} introuvable en base", keycloakId));
  }

  // ─── Helpers ─────────────────────────────────────────────────────────

  /**
   * Décode le payload JWT sans vérification de signature pour extraire le claim {@code sub}
   * (keycloakId). Non utilisé à des fins d'autorisation — sert uniquement à identifier la session à
   * supprimer lors du logout.
   */
  private String extractSubFromJwt(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) return null;
      // Padding base64url → base64
      String padded = parts[1] + "=".repeat((4 - parts[1].length() % 4) % 4);
      byte[] decoded = Base64.getUrlDecoder().decode(padded);
      JsonNode payload = new ObjectMapper().readTree(decoded);
      String sub = payload.path("sub").asText(null);
      log.debug("[Logout] sub extrait du token : {}", sub);
      return sub;
    } catch (Exception e) {
      log.warn("[Logout] Erreur parsing JWT : {}", e.getMessage());
      return null;
    }
  }

  private Optional<User> findUser(String identifier) {
    Optional<User> byPhone = userRepository.findByPhone(identifier);
    if (byPhone.isPresent()) {
      log.debug("[Login] Résolution par téléphone");
      return byPhone;
    }
    if (identifier.contains("@")) {
      Optional<User> byEmail = userRepository.findByEmail(identifier);
      if (byEmail.isPresent()) {
        log.debug("[Login] Résolution par email");
        return byEmail;
      }
    }
    return Optional.empty();
  }
}
