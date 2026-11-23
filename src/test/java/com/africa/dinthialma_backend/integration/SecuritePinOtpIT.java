package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.common.constants.Constants;
import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verrouillage du PIN et limites des OTP (SE07), compteurs en base. */
@DisplayName("Sécurité du PIN et des OTP (SE07)")
class SecuritePinOtpIT extends AbstractIntegrationTest {

  private static final String PIN = "482915";

  @Autowired private JdbcTemplate jdbc;

  private Utilisateur avecPin() {
    Utilisateur u = donnees.inscrire("Pin");
    TestDataFactory.attendre(
        api.post("/v1/auth/pin/setup", u.jeton(), Map.of("pin", PIN, "confirmPin", PIN)), 200);
    return u;
  }

  private Reponse pin(Utilisateur u, String pin, String clientType) {
    return api.post(
        "/v1/auth/login-pin",
        null,
        Map.of("phone", u.phone(), "pin", pin, "clientType", clientType));
  }

  private String normalise(Utilisateur u) {
    return u.phone().replace("+", "");
  }

  @Test
  @DisplayName(
      "5 PIN faux → PIN_LOCKED (≈ 15 min) ; le bon PIN est refusé ; après le délai, accepté")
  void verrouillageEtDelai() {
    Utilisateur u = avecPin();
    for (int i = 1; i <= 4; i++) {
      assertThat(pin(u, "000001", "WEB").code()).isEqualTo("PIN_INVALID");
    }
    Reponse verrou = pin(u, "000001", "WEB");
    assertThat(verrou.statut()).isEqualTo(429);
    assertThat(verrou.code()).isEqualTo("PIN_LOCKED");
    assertThat(verrou.data().path("retryAfterSeconds").asLong()).isBetween(890L, 900L);
    assertThat(pin(u, PIN, "WEB").code()).isEqualTo("PIN_LOCKED");

    // Délai écoulé (simulé en base)
    jdbc.update(
        "UPDATE dinthialma.users SET pin_locked_until = now() - interval '1 minute' WHERE phone = ?",
        normalise(u));
    assertThat(pin(u, PIN, "WEB").statut()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT pin_echecs_cumules FROM dinthialma.users WHERE phone = ?",
                Integer.class,
                normalise(u)))
        .isZero();
  }

  @Test
  @DisplayName(
      "deux appareils en parallèle : 6 PIN faux simultanés → exactement 4 PIN_INVALID et 2"
          + " PIN_LOCKED, aucun échec perdu")
  void deuxAppareilsEnParallele() throws Exception {
    Utilisateur u = avecPin();
    ExecutorService pool = Executors.newFixedThreadPool(6);
    List<Future<String>> codes = new ArrayList<>();
    try {
      for (int i = 0; i < 6; i++) {
        String clientType = i % 2 == 0 ? "WEB" : "MOBILE";
        Callable<String> essai = () -> pin(u, "000001", clientType).code();
        codes.add(pool.submit(essai));
      }
      List<String> resultats = new ArrayList<>();
      for (Future<String> f : codes) {
        resultats.add(f.get());
      }
      assertThat(resultats).filteredOn("PIN_INVALID"::equals).hasSize(4);
      assertThat(resultats).filteredOn("PIN_LOCKED"::equals).hasSize(2);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT pin_echecs_cumules FROM dinthialma.users WHERE phone = ?",
                Integer.class,
                normalise(u)))
        .isEqualTo(5);
  }

  @Test
  @DisplayName("10ᵉ échec cumulé → PIN_DISABLED : PIN effacé, sessions révoquées")
  void invalidationApresDixEchecs() {
    Utilisateur u = avecPin();
    jdbc.update("UPDATE dinthialma.users SET pin_echecs_cumules = 9 WHERE phone = ?", normalise(u));

    Reponse r = pin(u, "000001", "WEB");

    assertThat(r.statut()).isEqualTo(401);
    assertThat(r.code()).isEqualTo("PIN_DISABLED");
    assertThat(pin(u, PIN, "WEB").code()).isEqualTo("PIN_NOT_CONFIGURED");
    assertThat(api.get("/v1/me/sessions", u.jeton()).data()).isEmpty();
  }

  @Test
  @DisplayName(
      "OTP : 3 envois par heure et par numéro, même inconnu (anti-énumération) ; 4ᵉ → 429"
          + " OTP_RATE_LIMITED")
  void limiteEnvois() {
    String inconnu = TestDataFactory.nouveauTelephone();
    for (int i = 1; i <= Constants.Otp.MAX_ENVOIS_PAR_HEURE; i++) {
      TestDataFactory.attendre(
          api.post("/v1/auth/forgot-password/send-otp", null, Map.of("phone", inconnu)), 200);
    }
    Reponse refuse = api.post("/v1/auth/forgot-password/send-otp", null, Map.of("phone", inconnu));
    assertThat(refuse.statut()).isEqualTo(429);
    assertThat(refuse.code()).isEqualTo("OTP_RATE_LIMITED");
    assertThat(refuse.data().path("retryAfterSeconds").asLong()).isPositive();

    // Tous motifs confondus : l'inscription compte déjà un envoi
    Utilisateur u = donnees.inscrire();
    TestDataFactory.attendre(
        api.post("/v1/auth/pin/reset/send-otp", null, Map.of("phone", u.phone())), 200);
    TestDataFactory.attendre(
        api.post("/v1/auth/forgot-password/send-otp", null, Map.of("phone", u.phone())), 200);
    assertThat(
            api.post("/v1/auth/forgot-password/send-otp", null, Map.of("phone", u.phone())).code())
        .isEqualTo("OTP_RATE_LIMITED");
  }

  @Test
  @DisplayName("OTP : au 5ᵉ code faux, OTP_TOO_MANY_ATTEMPTS ; le bon code n'est plus accepté")
  void essaisParCode() {
    Utilisateur u = donnees.inscrire();
    TestDataFactory.attendre(
        api.post("/v1/auth/forgot-password/send-otp", null, Map.of("phone", u.phone())), 200);
    String bon = donnees.otp(u.phone(), Constants.OtpPurpose.RESET_PASSWORD);
    String faux = bon.equals("000000") ? "111111" : "000000";
    String verifier = "/v1/auth/forgot-password/verify-otp";
    for (int i = 1; i < Constants.Otp.MAX_ESSAIS_PAR_CODE; i++) {
      assertThat(api.post(verifier, null, Map.of("phone", u.phone(), "code", faux)).code())
          .isEqualTo("OTP_INVALID");
    }
    assertThat(api.post(verifier, null, Map.of("phone", u.phone(), "code", faux)).code())
        .isEqualTo("OTP_TOO_MANY_ATTEMPTS");
    assertThat(api.post(verifier, null, Map.of("phone", u.phone(), "code", bon)).statut())
        .isEqualTo(400);
  }
}
