package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.auth.entity.UserSession;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserSessionRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.UserSessionService;
import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Session longue par appareil de confiance (B53), contre le Keycloak de Testcontainers : jeton hors
 * ligne enregistré chiffré, rejoué par le PIN sans dépendre de la session SSO, révoqué chez
 * Keycloak à chaque déconnexion d'appareil.
 */
@DisplayName("Session longue par appareil de confiance (B53)")
class SessionAppareilIT extends AbstractIntegrationTest {

  private static final String PIN = "482915";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private UserSessionRepository sessionRepository;
  @Autowired private UserSessionService sessionService;
  @Autowired private UserRepository userRepository;
  @Autowired private Keycloak keycloak;

  @Value("${dinthialma.bootstrap.phone}")
  private String superAdminPhone;

  @Value("${dinthialma.bootstrap.password}")
  private String superAdminPassword;

  private static String appareil() {
    return "appareil-" + UUID.randomUUID();
  }

  private UUID userId(Utilisateur u) {
    return userRepository.findByPhone(u.phone().replace("+", "")).orElseThrow().getId();
  }

  private UserSession sessionDe(Utilisateur u, String appareilId) {
    return sessionRepository.findByUserIdAndAppareilId(userId(u), appareilId).orElseThrow();
  }

  private String jetonStocke(Utilisateur u, String appareilId) throws Exception {
    return sessionService.decryptRefreshToken(sessionDe(u, appareilId));
  }

  private static JsonNode payload(String jwt) throws Exception {
    return JSON.readTree(
        new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8));
  }

  /** Crée le PIN avec le mot de passe sur cet appareil : il devient de confiance. */
  private void pinDeConfiance(Utilisateur u, String appareilId, String clientType) {
    JsonNode r =
        TestDataFactory.attendre(
                api.post(
                    "/v1/auth/pin/setup",
                    u.jeton(),
                    Map.of(
                        "pin", PIN,
                        "confirmPin", PIN,
                        "motDePasse", TestDataFactory.MOT_DE_PASSE,
                        "appareilId", appareilId,
                        "clientType", clientType)),
                200)
            .data();
    assertThat(r.path("confiance").asBoolean()).isTrue();
  }

  private JsonNode connecter(Utilisateur u, String motDePasse, String appareilId) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("username", u.phone());
    corps.put("password", motDePasse);
    corps.put("clientType", "WEB");
    corps.put("appareilId", appareilId);
    return TestDataFactory.attendre(api.post("/v1/auth/login", null, corps), 200).data();
  }

  private Reponse pin(Utilisateur u, String appareilId, String clientType) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("phone", u.phone());
    corps.put("pin", PIN);
    corps.put("clientType", clientType);
    if (appareilId != null) corps.put("appareilId", appareilId);
    return api.post("/v1/auth/login-pin", null, corps);
  }

  private Reponse rafraichir(String refreshToken) {
    return api.post("/v1/auth/refresh", null, Map.of("refreshToken", refreshToken));
  }

  @Test
  @DisplayName(
      "PIN créé avec le mot de passe : jeton hors ligne chiffré ; la session SSO supprimée"
          + " (comme après SSO Session Idle), le PIN fonctionne encore")
  void survitALaSessionSso() throws Exception {
    Utilisateur u = donnees.inscrire("Confiance");
    String a = appareil();
    pinDeConfiance(u, a, "MOBILE");

    UserSession session = sessionDe(u, a);
    assertThat(session.isConfiance()).isTrue();
    assertThat(session.getRefreshTokenHash()).doesNotContain("eyJ");
    String horsLigne = jetonStocke(u, a);
    assertThat(payload(horsLigne).path("typ").asText()).isEqualTo("Offline");

    // Session SSO normale supprimée chez Keycloak : son refresh token ne vaut plus rien
    keycloak
        .realm("dinthialma")
        .deleteSession(payload(u.refreshToken()).path("sid").asText(), false);
    assertThat(rafraichir(u.refreshToken()).statut()).isEqualTo(401);

    Reponse ok = pin(u, a, "MOBILE");
    assertThat(ok.statut()).isEqualTo(200);
    assertThat(ok.data().path("access_token").asText()).isNotBlank();
    JsonNode sessions = api.get("/v1/me/sessions", ok.data().path("access_token").asText()).data();
    JsonNode celleCi =
        StreamSupport.stream(sessions.spliterator(), false)
            .filter(s -> a.equals(s.path("appareilId").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(celleCi.path("confiance").asBoolean()).isTrue();
    assertThat(celleCi.path("derniereUtilisation").asText()).isNotBlank();
  }

  @Test
  @DisplayName(
      "déconnexion à distance d'un appareil : jeton révoqué chez Keycloak, son PIN refusé"
          + " (PIN_SESSION_EXPIRED) ; l'autre appareil fonctionne")
  void deconnexionADistance() throws Exception {
    Utilisateur u = donnees.inscrire("Distance");
    String telephone = appareil();
    String ordinateur = appareil();
    pinDeConfiance(u, telephone, "MOBILE");
    JsonNode web = connecter(u, TestDataFactory.MOT_DE_PASSE, ordinateur);
    assertThat(sessionDe(u, ordinateur).isConfiance()).isTrue();
    String jetonTelephone = jetonStocke(u, telephone);

    UUID idTelephone = sessionDe(u, telephone).getId();
    TestDataFactory.attendre(
        api.delete("/v1/me/sessions/" + idTelephone, web.path("access_token").asText()), 200);

    Reponse refuse = pin(u, telephone, "MOBILE");
    assertThat(refuse.statut()).isEqualTo(401);
    assertThat(refuse.code()).isEqualTo("PIN_SESSION_EXPIRED");
    assertThat(rafraichir(jetonTelephone).statut()).isEqualTo(401);
    assertThat(pin(u, ordinateur, "WEB").statut()).isEqualTo(200);
  }

  @Test
  @DisplayName("changement de mot de passe : les autres appareils de confiance sont révoqués")
  void changementDeMotDePasse() throws Exception {
    Utilisateur u = donnees.inscrire("MotDePasse");
    String telephone = appareil();
    String ordinateur = appareil();
    pinDeConfiance(u, telephone, "MOBILE");
    JsonNode web = connecter(u, TestDataFactory.MOT_DE_PASSE, ordinateur);

    TestDataFactory.attendre(
        api.post(
            "/v1/me/password",
            web.path("access_token").asText(),
            Map.of(
                "ancienMotDePasse",
                TestDataFactory.MOT_DE_PASSE,
                "nouveauMotDePasse",
                "Nouveau@Mdp2026")),
        200);

    assertThat(pin(u, telephone, "MOBILE").code()).isEqualTo("PIN_SESSION_EXPIRED");
    assertThat(pin(u, ordinateur, "WEB").statut()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "rotation : après un rejeu, l'ancien jeton hors ligne est refusé ; un rafraîchissement met"
          + " à jour la session de l'appareil et le PIN fonctionne toujours")
  void rotation() throws Exception {
    Utilisateur u = donnees.inscrire("Rotation");
    String a = appareil();
    pinDeConfiance(u, a, "MOBILE");
    String t1 = jetonStocke(u, a);

    assertThat(pin(u, a, "MOBILE").statut()).isEqualTo(200);
    String t2 = jetonStocke(u, a);
    assertThat(t2).isNotEqualTo(t1);

    // Le front rafraîchit avec le dernier jeton reçu : la session de l'appareil suit
    Reponse r = rafraichir(t2);
    assertThat(r.statut()).isEqualTo(200);
    assertThat(jetonStocke(u, a)).isEqualTo(r.data().path("refresh_token").asText());
    assertThat(pin(u, a, "MOBILE").statut()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "rejeu d'un ancien jeton hors ligne : refusé, et Keycloak ferme la session (détection de"
          + " vol) → le PIN de l'appareil demande une nouvelle connexion")
  void rejeuDUnAncienJeton() throws Exception {
    Utilisateur u = donnees.inscrire("Rejeu");
    String a = appareil();
    pinDeConfiance(u, a, "MOBILE");
    String t1 = jetonStocke(u, a);
    assertThat(pin(u, a, "MOBILE").statut()).isEqualTo(200);

    assertThat(rafraichir(t1).statut()).isEqualTo(401);

    assertThat(pin(u, a, "MOBILE").code()).isEqualTo("PIN_SESSION_EXPIRED");
  }

  @Test
  @DisplayName(
      "SUPER_ADMIN : jamais de jeton hors ligne, ni à la connexion ni à la création du PIN")
  void superAdminSansJetonHorsLigne() throws Exception {
    Utilisateur admin = donnees.superAdmin();
    String a = appareil();
    JsonNode setup =
        TestDataFactory.attendre(
                api.post(
                    "/v1/auth/pin/setup",
                    admin.jeton(),
                    Map.of(
                        "pin", PIN,
                        "confirmPin", PIN,
                        "motDePasse", "peu-importe",
                        "appareilId", a,
                        "clientType", "WEB")),
                200)
            .data();
    assertThat(setup.path("confiance").asBoolean()).isFalse();

    Utilisateur adminTel = new Utilisateur(superAdminPhone, admin.jeton(), null, null, null);
    JsonNode login = connecter(adminTel, superAdminPassword, a);
    assertThat(payload(login.path("refresh_token").asText()).path("typ").asText())
        .isNotEqualTo("Offline");
    assertThat(sessionDe(adminTel, a).isConfiance()).isFalse();
  }

  @Test
  @DisplayName(
      "déploiement : une session d'avant B53 (sans appareilId) ou un appareil inconnu →"
          + " PIN_SESSION_EXPIRED dès que la session SSO n'existe plus")
  void sessionsAnciennes() throws Exception {
    Utilisateur u = donnees.inscrire("Ancienne");
    TestDataFactory.attendre(
        api.post("/v1/auth/pin/setup", u.jeton(), Map.of("pin", PIN, "confirmPin", PIN)), 200);
    keycloak
        .realm("dinthialma")
        .deleteSession(payload(u.refreshToken()).path("sid").asText(), false);

    Reponse ancienne = pin(u, null, "WEB");
    assertThat(ancienne.statut()).isEqualTo(401);
    assertThat(ancienne.code()).isEqualTo("PIN_SESSION_EXPIRED");
    assertThat(pin(u, appareil(), "WEB").code()).isEqualTo("PIN_SESSION_EXPIRED");

    // Une connexion par mot de passe avec appareilId rend l'appareil de confiance
    String a = appareil();
    connecter(u, TestDataFactory.MOT_DE_PASSE, a);
    assertThat(sessionDe(u, a).isConfiance()).isTrue();
    assertThat(pin(u, a, "WEB").statut()).isEqualTo(200);
  }

  @Test
  @DisplayName("compte désactivé par un admin : toutes ses sessions supprimées, jetons révoqués")
  void compteDesactive() throws Exception {
    Utilisateur u = donnees.inscrire("Desactive");
    String a = appareil();
    pinDeConfiance(u, a, "MOBILE");
    String horsLigne = jetonStocke(u, a);

    TestDataFactory.attendre(
        api.post(
            "/v1/admin/dashboard/users/" + userId(u) + "/disable",
            donnees.superAdmin().jeton(),
            null),
        200);

    assertThat(sessionRepository.findByUserIdAndAppareilId(userId(u), a)).isEmpty();
    assertThat(rafraichir(horsLigne).statut()).isEqualTo(401);
  }
}
