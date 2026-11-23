package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.auth.repository.UserSessionRepository;
import com.africa.dinthialma_backend.auth.service.impl.UserSessionServiceImpl;
import com.africa.dinthialma_backend.auth.service.interfaces.KeycloakAuthService;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.util.TokenEncryptionUtil;
import com.africa.dinthialma_backend.support.Fixtures;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserSessionServiceImpl")
class UserSessionServiceImplTest {

  @Mock private UserSessionRepository repository;
  @Mock private KeycloakAuthService keycloakAuthService;
  @Mock private AuditService auditService;
  @InjectMocks private UserSessionServiceImpl service;

  private User user;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "tokenEncryptionKey", "cle-des-tests-unitaires!!");
    user = Fixtures.user("Awa");
    lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  @Test
  @DisplayName("nouvelle session : jeton chiffré (jamais en clair), réversible, expiration posée")
  void creation() throws Exception {
    when(repository.findByUserIdAndClientType(user.getId(), ClientType.WEB))
        .thenReturn(Optional.empty());

    UserSession s = service.createOrUpdateSession(user, ClientType.WEB, "refresh-123", "Chrome");

    assertThat(s.getRefreshTokenHash()).isNotBlank().isNotEqualTo("refresh-123");
    assertThat(service.decryptRefreshToken(s)).isEqualTo("refresh-123");
    assertThat(s.getDeviceInfo()).isEqualTo("Chrome");
    assertThat(s.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(364));
  }

  @Test
  @DisplayName("session existante du même type : mise à jour, appareil conservé si non fourni")
  void miseAJour() throws Exception {
    UserSession existante =
        UserSession.builder()
            .id(UUID.randomUUID())
            .user(user)
            .clientType(ClientType.MOBILE)
            .refreshTokenHash("ancien")
            .deviceInfo("Pixel")
            .build();
    when(repository.findByUserIdAndClientType(user.getId(), ClientType.MOBILE))
        .thenReturn(Optional.of(existante));

    UserSession s = service.createOrUpdateSession(user, ClientType.MOBILE, "nouveau", " ");

    assertThat(s).isSameAs(existante);
    assertThat(s.getDeviceInfo()).isEqualTo("Pixel");
    assertThat(service.decryptRefreshToken(s)).isEqualTo("nouveau");
    assertThat(s.getLastUsedAt()).isNotNull();

    service.createOrUpdateSession(user, ClientType.MOBILE, "nouveau", "iPhone");
    assertThat(s.getDeviceInfo()).isEqualTo("iPhone");
  }

  @Test
  @DisplayName("déchiffrement d'un jeton corrompu -> 500")
  void dechiffrementImpossible() {
    UserSession s = UserSession.builder().id(UUID.randomUUID()).refreshTokenHash("xxx").build();
    assertThatThrownBy(() -> service.decryptRefreshToken(s))
        .isInstanceOfSatisfying(
            CustomException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
  }

  @Test
  @DisplayName("recherche de sessions actives : filtrées sur l'expiration")
  void sessionsActives() {
    UserSession s = UserSession.builder().id(UUID.randomUUID()).build();
    when(repository.findByUserIdAndClientTypeAndExpiresAtAfter(
            eq(user.getId()), eq(ClientType.WEB), any()))
        .thenReturn(Optional.of(s));
    when(repository.findTopByUserIdAndExpiresAtAfterOrderByExpiresAtDesc(eq(user.getId()), any()))
        .thenReturn(Optional.empty());

    assertThat(service.findActiveSession(user.getId(), ClientType.WEB)).contains(s);
    assertThat(service.findAnyActiveSession(user.getId())).isEmpty();
  }

  @Test
  @DisplayName("révocation : une session (inconnue -> 404) ou toutes")
  void revocation() throws Exception {
    UserSession s = UserSession.builder().id(UUID.randomUUID()).build();
    when(repository.findById(s.getId())).thenReturn(Optional.of(s));
    service.revokeSession(s.getId());
    verify(repository).delete(s);

    UUID inconnue = UUID.randomUUID();
    when(repository.findById(inconnue)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.revokeSession(inconnue)).isInstanceOf(NotFoundException.class);

    service.revokeAllSessions(user.getId());
    verify(repository).deleteAllByUserId(user.getId());
  }

  /** Jeton JWT non signé portant un sid (session Keycloak). */
  private static String jetonSid(String sid) {
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    return enc.encodeToString("{}".getBytes(StandardCharsets.UTF_8))
        + "."
        + enc.encodeToString(("{\"sid\":\"" + sid + "\"}").getBytes(StandardCharsets.UTF_8))
        + ".sig";
  }

  @Nested
  @DisplayName("B53 : sessions par appareil")
  class SessionsParAppareil {

    private static final String APPAREIL = "appareil-awa-0001";

    private UserSession existante(boolean confiance, String jeton) {
      UserSession s =
          UserSession.builder()
              .id(UUID.randomUUID())
              .user(user)
              .appareilId(APPAREIL)
              .clientType(ClientType.MOBILE)
              .confiance(confiance)
              .refreshTokenHash(TokenEncryptionUtil.encrypt(jeton, "cle-des-tests-unitaires!!"))
              .expiresAt(LocalDateTime.now().plusDays(10))
              .build();
      lenient()
          .when(repository.findByUserIdAndAppareilId(user.getId(), APPAREIL))
          .thenReturn(Optional.of(s));
      return s;
    }

    @Test
    @DisplayName(
        "création d'une session de confiance : jeton chiffré, appareil gardé, création auditée")
    void creation() throws Exception {
      when(repository.findByUserIdAndAppareilId(user.getId(), APPAREIL))
          .thenReturn(Optional.empty());

      UserSession s =
          service.enregistrerSessionAppareil(
              user, ClientType.MOBILE, APPAREIL, jetonSid("s1"), "Pixel", true);

      assertThat(s.getAppareilId()).isEqualTo(APPAREIL);
      assertThat(s.isConfiance()).isTrue();
      assertThat(s.getRefreshTokenHash()).doesNotContain("eyJ");
      assertThat(service.decryptRefreshToken(s)).isEqualTo(jetonSid("s1"));
      assertThat(s.getDeviceInfo()).isEqualTo("Pixel");
      verify(auditService)
          .log(eq(user), eq("user_sessions"), any(), eq("confiance"), eq(null), eq("CREEE"));
      verify(keycloakAuthService, never()).revoquerJeton(any());
    }

    @Test
    @DisplayName("rotation (même sid) : jeton remplacé, rien n'est révoqué ni re-audité")
    void rotation() throws Exception {
      UserSession s = existante(true, jetonSid("s1") + "a");

      service.enregistrerSessionAppareil(
          user, ClientType.MOBILE, APPAREIL, jetonSid("s1") + "b", null, true);

      assertThat(service.decryptRefreshToken(s)).isEqualTo(jetonSid("s1") + "b");
      verify(keycloakAuthService, never()).revoquerJeton(any());
      verify(auditService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "nouvelle connexion (autre sid) : l'ancien jeton hors ligne est révoqué chez Keycloak")
    void nouvelleSession() throws Exception {
      existante(true, jetonSid("ancienne"));

      service.enregistrerSessionAppareil(
          user, ClientType.MOBILE, APPAREIL, jetonSid("nouvelle"), null, true);

      verify(keycloakAuthService).revoquerJeton(jetonSid("ancienne"));
    }

    @Test
    @DisplayName("ancien jeton illisible (sans sid) : révoqué par prudence")
    void ancienSansSid() throws Exception {
      existante(true, "pas-un-jwt");

      service.enregistrerSessionAppareil(
          user, ClientType.MOBILE, APPAREIL, jetonSid("nouvelle"), null, true);

      verify(keycloakAuthService).revoquerJeton("pas-un-jwt");
    }

    @Test
    @DisplayName("session de l'appareil : expirée ignorée")
    void recherche() {
      UserSession s = existante(false, "x");
      assertThat(service.trouverSessionAppareil(user.getId(), APPAREIL)).contains(s);
      s.setExpiresAt(LocalDateTime.now().minusMinutes(1));
      assertThat(service.trouverSessionAppareil(user.getId(), APPAREIL)).isEmpty();
      s.setExpiresAt(null);
      assertThat(service.trouverSessionAppareil(user.getId(), APPAREIL)).isEmpty();
    }

    @Test
    @DisplayName("révocation d'une session de confiance : jeton révoqué chez Keycloak, audité")
    void revocation() throws Exception {
      UserSession s = existante(true, jetonSid("s1"));
      when(repository.findById(s.getId())).thenReturn(Optional.of(s));

      service.revokeSession(s.getId());

      verify(keycloakAuthService).revoquerJeton(jetonSid("s1"));
      verify(auditService).log(user, "user_sessions", s.getId(), "confiance", "CREEE", "REVOQUEE");
      verify(repository).delete(s);
    }

    @Test
    @DisplayName(
        "révocation de toutes les sessions : jetons hors ligne révoqués, jeton illisible ignoré")
    void revocationToutes() {
      UserSession s = existante(true, jetonSid("s1"));
      UserSession corrompue =
          UserSession.builder()
              .id(UUID.randomUUID())
              .user(user)
              .confiance(true)
              .refreshTokenHash("x")
              .build();
      when(repository.findByUserIdAndConfianceTrue(user.getId())).thenReturn(List.of(s, corrompue));

      service.revokeAllSessions(user.getId());

      verify(keycloakAuthService).revoquerJeton(jetonSid("s1"));
      verify(repository).deleteAllByUserId(user.getId());
    }

    @Test
    @DisplayName("rafraîchissement : la session qui portait l'ancien jeton reçoit le nouveau")
    void synchronisation() throws Exception {
      UserSession s = existante(true, "ancien");
      UserSession autre = existante(false, "autre");
      when(repository.findByUserIdOrderByLastUsedAtDesc(user.getId()))
          .thenReturn(List.of(autre, s));

      service.synchroniserJeton(user.getId(), "ancien", "nouveau");

      assertThat(service.decryptRefreshToken(s)).isEqualTo("nouveau");
      assertThat(service.decryptRefreshToken(autre)).isEqualTo("autre");

      service.synchroniserJeton(user.getId(), "inconnu", "x");
      assertThat(service.decryptRefreshToken(s)).isEqualTo("nouveau");
    }

    @Test
    @DisplayName("rejeu audité seulement pour une session de confiance")
    void rejeu() {
      UserSession confiance =
          UserSession.builder().id(UUID.randomUUID()).user(user).confiance(true).build();
      service.tracerRejeu(confiance);
      service.tracerRejeu(UserSession.builder().id(UUID.randomUUID()).user(user).build());
      verify(auditService)
          .log(user, "user_sessions", confiance.getId(), "confiance", "CREEE", "REJOUEE");
    }
  }
}
