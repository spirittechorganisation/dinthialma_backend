package com.africa.dinthialma_backend.auth.service.impl;

import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.dto.LoginResponse;
import com.africa.dinthialma_backend.auth.dto.PinLoginRequest;
import com.africa.dinthialma_backend.auth.dto.PinResetRequest;
import com.africa.dinthialma_backend.auth.dto.PinSetupRequest;
import com.africa.dinthialma_backend.auth.dto.PinSetupResponse;
import com.africa.dinthialma_backend.auth.entity.OtpVerification;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.auth.repository.OtpVerificationRepository;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.KeycloakAuthService;
import com.africa.dinthialma_backend.auth.service.interfaces.OtpGardeService;
import com.africa.dinthialma_backend.auth.service.interfaces.PinService;
import com.africa.dinthialma_backend.auth.service.interfaces.UserSessionService;
import com.africa.dinthialma_backend.common.constants.Constants;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.exception.TooManyRequestsException;
import com.africa.dinthialma_backend.common.exception.UnAuthorizedException;
import com.africa.dinthialma_backend.common.util.OtpUtils;
import com.africa.dinthialma_backend.common.util.PhoneUtils;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gestion du code PIN – configuration, connexion rapide et réinitialisation.
 *
 * <p>Sécurité :
 *
 * <ul>
 *   <li>Argon2id pour le hachage (résistant aux attaques GPU et side-channel)
 *   <li>Verrouillage après {@code MAX_ATTEMPTS} tentatives incorrectes consécutives
 *   <li>Liste noire des PINs triviaux
 *   <li>Sessions révoquées lors du reset PIN
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinServiceImpl implements PinService {

  private final UserRepository userRepository;
  private final OtpVerificationRepository otpVerificationRepository;
  private final UserSessionService userSessionService;
  private final KeycloakAuthService keycloakAuthService;
  private final WhatsappService whatsappService;
  private final OtpGardeService otpGarde;
  private final UserRoleAssignmentRepository roleAssignmentRepository;

  /**
   * Encodeur Argon2id – paramètres renforcés pour les PINs courts (6 chiffres). saltLength=16,
   * hashLength=32, parallelism=1, memory=65536 (64 MB), iterations=3
   */
  private static final Argon2PasswordEncoder ARGON2 =
      new Argon2PasswordEncoder(16, 32, 1, 65536, 3);

  // ─── Configuration du PIN ─────────────────────────────────────────

  @Override
  @Transactional
  public PinSetupResponse setupPin(String keycloakId, PinSetupRequest request)
      throws CustomException {
    log.debug("Configuration PIN pour keycloakId={}", keycloakId);

    User user = findUserByKeycloakId(keycloakId);

    validatePinFormat(request.getPin());

    if (!request.getPin().equals(request.getConfirmPin())) {
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_PIN_MISMATCH, ErrorCode.PIN_MISMATCH);
    }

    // B53 : avec le mot de passe, cet appareil devient de confiance (jeton hors ligne), sauf
    // SUPER_ADMIN. Mot de passe faux → 401 INVALID_CREDENTIALS, le PIN n'est pas enregistré.
    LoginResponse horsLigne = jetonHorsLignePourPin(user, request);

    String pinHash = ARGON2.encode(request.getPin());
    user.setPinHash(pinHash);
    user.setPinAttempts(0);
    user.setPinEchecsCumules(0);
    user.setPinLockedUntil(null);
    user.setPinCreatedAt(LocalDateTime.now()); // ← horodatage pour expiration 90j
    userRepository.save(user);

    if (horsLigne != null) {
      userSessionService.enregistrerSessionAppareil(
          user,
          request.getClientType(),
          request.getAppareilId(),
          horsLigne.getRefreshToken(),
          request.getDeviceInfo(),
          true);
    }
    log.info("Code PIN configuré pour userId={} confiance={}", user.getId(), horsLigne != null);
    return PinSetupResponse.builder().confiance(horsLigne != null).build();
  }

  /** Jeton hors ligne obtenu avec le mot de passe fourni à la création du PIN, ou null. */
  private LoginResponse jetonHorsLignePourPin(User user, PinSetupRequest request)
      throws CustomException {
    if (request.getMotDePasse() == null || request.getMotDePasse().isBlank()) {
      return null;
    }
    if (request.getAppareilId() == null || request.getClientType() == null) {
      throw new BadRequestException(
          "appareilId et clientType sont obligatoires avec le mot de passe",
          ErrorCode.INVALID_PARAMETER);
    }
    if (roleAssignmentRepository.existsByUserIdAndRole(user.getId(), UserRole.SUPER_ADMIN)) {
      return null;
    }
    return keycloakAuthService.login(user.getPhone(), request.getMotDePasse(), true);
  }

  // ─── Connexion PIN ────────────────────────────────────────────────

  @Override
  @Transactional
  public LoginResponse loginWithPin(PinLoginRequest request) throws CustomException {
    String phone = PhoneUtils.normalize(request.getPhone());
    log.debug(
        "Tentative de connexion PIN pour phone={} clientType={}", phone, request.getClientType());

    // Relecture verrouillée : deux appareils en parallèle comptent leurs échecs sans en perdre
    User user =
        userRepository
            .findByPhoneForUpdate(phone)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.AUTH_USER_NOT_FOUND));

    verifierPinUtilisable(user);

    // Valider le PIN
    if (!ARGON2.matches(request.getPin(), user.getPinHash())) {
      handlePinFailure(user);
      return null; // jamais atteint – handlePinFailure lève toujours une exception
    }

    // PIN valide → remettre les compteurs à zéro
    user.setPinAttempts(0);
    user.setPinEchecsCumules(0);
    user.setPinLockedUntil(null);

    // B53 : uniquement la session de CET appareil ; jamais celle d'un autre appareil
    UserSession session = sessionPourPin(user, request);

    // Déchiffrer le refresh token (hors ligne pour une session de confiance) et le rejouer
    String refreshToken = userSessionService.decryptRefreshToken(session);

    LoginResponse newTokens;
    try {
      newTokens = keycloakAuthService.refreshToken(refreshToken);
    } catch (UnAuthorizedException e) {
      // Jeton refusé par Keycloak (expiré, révoqué, compte désactivé) : la session est purgée et
      // le front repasse par le mot de passe
      userSessionService.revokeSession(session.getId());
      log.warn(
          "Session Keycloak refusée – session supprimée userId={} clientType={}",
          user.getId(),
          session.getClientType());
      throw new UnAuthorizedException(
          ResponseMessageConstants.AUTH_SESSION_NOT_FOUND, ErrorCode.PIN_SESSION_EXPIRED);
    }

    // Rotation : la session de l'appareil reçoit le nouveau jeton
    if (session.getAppareilId() != null) {
      userSessionService.enregistrerSessionAppareil(
          user,
          request.getClientType(),
          session.getAppareilId(),
          newTokens.getRefreshToken(),
          request.getDeviceInfo(),
          session.isConfiance());
      userSessionService.tracerRejeu(session);
    } else {
      userSessionService.createOrUpdateSession(
          user, request.getClientType(), newTokens.getRefreshToken(), request.getDeviceInfo());
    }

    userRepository.save(user);

    log.info(
        "Connexion PIN réussie – userId={} clientType={}", user.getId(), request.getClientType());
    return newTokens;
  }

  /** Compte actif, PIN configuré, non expiré (90 jours) et non verrouillé. */
  private void verifierPinUtilisable(User user) throws CustomException {
    // Vérifier que le compte est actif
    if (!user.isActive()) {
      log.warn("Tentative de connexion PIN sur compte désactivé – userId={}", user.getId());
      throw new UnAuthorizedException(
          ResponseMessageConstants.AUTH_ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED);
    }

    // Vérifier que le PIN est configuré
    if (user.getPinHash() == null) {
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_PIN_NOT_CONFIGURED, ErrorCode.PIN_NOT_CONFIGURED);
    }

    verifierPinNonExpire(user);

    // Vérifier le verrouillage
    if (user.getPinLockedUntil() != null
        && LocalDateTime.now().isBefore(user.getPinLockedUntil())) {
      long minutesLeft =
          ChronoUnit.MINUTES.between(LocalDateTime.now(), user.getPinLockedUntil()) + 1;
      log.warn("PIN verrouillé pour userId={} – encore {} min", user.getId(), minutesLeft);
      throw new TooManyRequestsException(
          String.format(ResponseMessageConstants.AUTH_PIN_LOCKED, minutesLeft),
          ErrorCode.PIN_LOCKED,
          Math.max(1, ChronoUnit.SECONDS.between(LocalDateTime.now(), user.getPinLockedUntil())));
    }
  }

  private void verifierPinNonExpire(User user) throws CustomException {
    if (user.getPinCreatedAt() == null) {
      return;
    }
    long daysOld = ChronoUnit.DAYS.between(user.getPinCreatedAt(), LocalDateTime.now());
    if (daysOld >= Constants.Pin.EXPIRY_DAYS) {
      log.warn("PIN expiré pour userId={} ({}j)", user.getId(), daysOld);
      throw new BadRequestException(
          "Votre code PIN a expiré après "
              + Constants.Pin.EXPIRY_DAYS
              + " jours. Veuillez en configurer un nouveau via l'option 'Réinitialiser mon PIN'.",
          ErrorCode.PIN_EXPIRED);
    }
  }

  /**
   * Session de l'appareil qui demande (B53) : par {@code appareilId} s'il est fourni, sinon la
   * session d'avant B53 de ce type de client. Aucun repli sur un autre appareil : le PIN seul ne
   * suffit jamais sur un appareil sans session.
   */
  private UserSession sessionPourPin(User user, PinLoginRequest request) throws CustomException {
    Optional<UserSession> session =
        request.getAppareilId() != null
            ? userSessionService.trouverSessionAppareil(user.getId(), request.getAppareilId())
            : userSessionService.findActiveSession(user.getId(), request.getClientType());
    return session.orElseThrow(
        () -> {
          log.warn(
              "Aucune session pour cet appareil – userId={} clientType={}",
              user.getId(),
              request.getClientType());
          return new UnAuthorizedException(
              ResponseMessageConstants.AUTH_SESSION_NOT_FOUND, ErrorCode.PIN_SESSION_EXPIRED);
        });
  }

  // ─── Réinitialisation PIN via OTP ─────────────────────────────────

  @Override
  @Transactional
  public void sendResetPinOtp(String phone) throws CustomException {
    phone = PhoneUtils.normalize(phone);
    log.debug("Envoi OTP reset PIN pour : {}", phone);

    // Limite d'envoi appliquée avant le test d'existence : même réponse pour un numéro inconnu
    otpGarde.autoriserEnvoi(phone, Constants.OtpPurpose.RESET_PIN);

    if (!userRepository.existsByPhone(phone)) {
      // Anti-énumération : réponse silencieuse
      log.debug("Numéro {} inconnu – réponse silencieuse (anti-énumération PIN)", phone);
      return;
    }

    // Invalider l'OTP RESET_PIN précédent
    otpVerificationRepository
        .findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            phone, Constants.OtpPurpose.RESET_PIN)
        .ifPresent(
            existing -> {
              existing.setUsed(true);
              otpVerificationRepository.save(existing);
            });

    String code = OtpUtils.generateOtp();
    OtpVerification otp =
        OtpVerification.builder()
            .phone(phone)
            .code(code)
            .purpose(Constants.OtpPurpose.RESET_PIN)
            .expiresAt(LocalDateTime.now().plusMinutes(Constants.Otp.EXPIRY_MINUTES))
            .used(false)
            .verified(false)
            .build();

    otpVerificationRepository.save(otp);
    whatsappService.sendOtp(phone, code);
    log.info("OTP reset PIN envoyé au numéro : {}", phone);
  }

  @Override
  @Transactional
  public void verifyResetPinOtp(String phone, String code) throws CustomException {
    phone = PhoneUtils.normalize(phone);
    log.debug("Vérification OTP reset PIN pour : {}", phone);

    OtpVerification otp =
        otpVerificationRepository
            .findTopByPhoneAndPurposeAndUsedFalseOrderByCreatedAtDesc(
                phone, Constants.OtpPurpose.RESET_PIN)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        ResponseMessageConstants.AUTH_OTP_INVALID, ErrorCode.OTP_INVALID));

    if (otp.isExpired()) {
      log.warn("OTP reset PIN expiré pour : {}", phone);
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_OTP_INVALID, ErrorCode.OTP_EXPIRED);
    }
    if (!otp.getCode().equals(code)) {
      log.warn("OTP reset PIN incorrect pour : {}", phone);
      throw OtpGardeService.erreurCodeFaux(otpGarde.enregistrerEchec(otp));
    }

    otp.setVerified(true);
    otpVerificationRepository.save(otp);
    log.info("OTP reset PIN vérifié pour : {}", phone);
  }

  @Override
  @Transactional
  public void resetPin(PinResetRequest request) throws CustomException {
    String phone = PhoneUtils.normalize(request.getPhone());
    log.debug("Reset PIN pour : {}", phone);

    // Vérifier l'OTP vérifié non consommé
    OtpVerification otp =
        otpVerificationRepository
            .findTopByPhoneAndPurposeAndVerifiedTrueAndUsedFalseOrderByCreatedAtDesc(
                phone, Constants.OtpPurpose.RESET_PIN)
            .orElseThrow(
                () ->
                    new BadRequestException(
                        ResponseMessageConstants.AUTH_OTP_INVALID, ErrorCode.OTP_NOT_VERIFIED));

    User user =
        userRepository
            .findByPhone(phone)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.AUTH_USER_NOT_FOUND));

    validatePinFormat(request.getNewPin());

    if (!request.getNewPin().equals(request.getConfirmPin())) {
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_PIN_MISMATCH, ErrorCode.PIN_MISMATCH);
    }

    // Mettre à jour le PIN
    user.setPinHash(ARGON2.encode(request.getNewPin()));
    user.setPinAttempts(0);
    user.setPinEchecsCumules(0);
    user.setPinLockedUntil(null);
    user.setPinCreatedAt(LocalDateTime.now()); // ← renouvellement de l'horodatage
    userRepository.save(user);

    // Révoquer toutes les sessions (sécurité post-reset)
    userSessionService.revokeAllSessions(user.getId());
    log.info("Sessions révoquées après reset PIN – userId={}", user.getId());

    // Consommer l'OTP
    otp.setUsed(true);
    otpVerificationRepository.save(otp);

    log.info("PIN réinitialisé avec succès pour userId={}", user.getId());
  }

  // ─── Vérification configuration PIN ──────────────────────────────

  @Override
  public boolean isPinConfigured(String keycloakId) throws CustomException {
    User user = findUserByKeycloakId(keycloakId);
    return user.getPinHash() != null;
  }

  // ─── Helpers privés ───────────────────────────────────────────────

  /**
   * Incrémente les tentatives échouées : verrou de 15 min à la 5ᵉ consécutive ; PIN invalidé à la
   * 10ᵉ cumulée depuis le dernier succès (SE07), avec révocation des sessions de l'utilisateur.
   */
  private void handlePinFailure(User user) throws CustomException {
    int newAttempts = user.getPinAttempts() + 1;
    user.setPinAttempts(newAttempts);
    user.setPinEchecsCumules(user.getPinEchecsCumules() + 1);

    if (user.getPinEchecsCumules() >= Constants.Pin.MAX_ECHECS_CUMULES) {
      invaliderPin(user);
    }

    if (newAttempts >= Constants.Pin.MAX_ATTEMPTS) {
      LocalDateTime lockUntil =
          LocalDateTime.now().plusMinutes(Constants.Pin.LOCK_DURATION_MINUTES);
      user.setPinLockedUntil(lockUntil);
      user.setPinAttempts(0);
      userRepository.save(user);

      log.warn(
          "PIN verrouillé pour userId={} jusqu'à {} – {} tentatives échouées",
          user.getId(),
          lockUntil,
          Constants.Pin.MAX_ATTEMPTS);

      throw new TooManyRequestsException(
          String.format(
              ResponseMessageConstants.AUTH_PIN_LOCKED, Constants.Pin.LOCK_DURATION_MINUTES),
          ErrorCode.PIN_LOCKED,
          Constants.Pin.LOCK_DURATION_MINUTES * 60L);
    }

    userRepository.save(user);
    log.warn(
        "Tentative PIN incorrecte – userId={} attempt={}/{}",
        user.getId(),
        newAttempts,
        Constants.Pin.MAX_ATTEMPTS);
    throw new UnAuthorizedException(
        ResponseMessageConstants.AUTH_PIN_INVALID, ErrorCode.PIN_INVALID);
  }

  /**
   * PIN invalidé après trop d'échecs cumulés : il faut se reconnecter avec le mot de passe (ou
   * réinitialiser le PIN par OTP) puis en créer un nouveau. Toutes les sessions sont révoquées.
   */
  private void invaliderPin(User user) throws CustomException {
    user.setPinHash(null);
    user.setPinAttempts(0);
    user.setPinEchecsCumules(0);
    user.setPinLockedUntil(null);
    userRepository.save(user);
    userSessionService.revokeAllSessions(user.getId());
    log.warn(
        "PIN invalidé après {} échecs cumulés – userId={}",
        Constants.Pin.MAX_ECHECS_CUMULES,
        user.getId());
    throw new UnAuthorizedException(
        ResponseMessageConstants.AUTH_PIN_DISABLED, ErrorCode.PIN_DISABLED);
  }

  /** Valide le format du PIN : 6 chiffres, non trivial. */
  private void validatePinFormat(String pin) throws CustomException {
    if (pin == null || !pin.matches("\\d{6}")) {
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_PIN_INVALID_FORMAT, ErrorCode.PIN_INVALID_FORMAT);
    }
    if (Constants.Pin.FORBIDDEN_PINS.contains(pin)) {
      throw new BadRequestException(
          ResponseMessageConstants.AUTH_PIN_TOO_SIMPLE, ErrorCode.PIN_TOO_SIMPLE);
    }
  }

  private User findUserByKeycloakId(String keycloakId) throws CustomException {
    return userRepository
        .findByKeycloakId(keycloakId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.AUTH_USER_NOT_FOUND));
  }
}
