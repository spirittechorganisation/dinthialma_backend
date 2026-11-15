package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Options de remise du jackpot (B33, lot 2) : 5 membres × 1 000 F, commission de 20 % → jackpot
 * prévu 5 000 F brut, 4 000 F net. Aperçu, remise selon l'option, puis régularisation d'un retard
 * sur le tour remis (complément au bénéficiaire ou remboursement du gestionnaire).
 */
@DisplayName("Options de remise du jackpot")
class OptionsRemiseIT extends AbstractIntegrationTest {

  /** Tontine active à 5 membres, commission 20 %, premier tour ouvert. */
  private record Scenario(String jeton, String tontineId, String cycleId, List<String> membres) {

    String cycle() {
      return "/v1/tontines/" + tontineId + "/cycles/" + cycleId;
    }
  }

  private Scenario scenario(String nom) {
    Utilisateur gestionnaire = donnees.inscrire("Gestionnaire");
    String g = gestionnaire.jeton();
    String tontineId =
        donnees
            .creerTontine(
                g,
                nom,
                Map.of("commissions", List.of(Map.of("type", "POURCENTAGE_JACKPOT", "valeur", 20))))
            .path("id")
            .asText();
    List<String> membres = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      Utilisateur membre = donnees.inscrire("Membre" + i);
      membres.add(donnees.ajouterMembre(g, tontineId, membre.phone()).path("id").asText());
    }
    donnees.activer(g, tontineId);
    String cycleId = donnees.ouvrirCycle(g, tontineId).path("id").asText();
    return new Scenario(g, tontineId, cycleId, membres);
  }

  /** Encaissement par le gestionnaire (cotisation créée VALIDE). */
  private void encaisser(Scenario s, int indexMembre) {
    TestDataFactory.attendre(
        api.post(
            "/v1/tontines/" + s.tontineId() + "/cotisations/admin",
            s.jeton(),
            Map.of(
                "membreId",
                s.membres().get(indexMembre),
                "cycleId",
                s.cycleId(),
                "montant",
                1000,
                "methodePaiement",
                "CASH")),
        201);
  }

  private static JsonNode option(JsonNode apercu, String mode) {
    return StreamSupport.stream(apercu.path("options").spliterator(), false)
        .filter(o -> mode.equals(o.path("modeRemise").asText()))
        .findFirst()
        .orElseThrow();
  }

  private boolean aRecu(String jeton, String type) {
    JsonNode notifs = api.get("/v1/notifications?size=50", jeton).data().path("content");
    return StreamSupport.stream(notifs.spliterator(), false)
        .anyMatch(n -> type.equals(n.path("type").asText()));
  }

  @Test
  @DisplayName(
      "DISPONIBLE : aperçu, ATTENDRE et CAISSE refusés, remise de ce qui est validé, puis"
          + " complément au bénéficiaire à la régularisation")
  void remiseDisponiblePuisComplement() {
    Scenario s = scenario("Remise disponible");
    for (int i = 0; i < 4; i++) {
      encaisser(s, i);
    }

    JsonNode apercu = api.get(s.cycle() + "/remise/apercu", s.jeton()).data();
    assertThat(apercu.path("jackpotPrevu").asInt()).isEqualTo(5000);
    assertThat(apercu.path("collecte").asInt()).isEqualTo(4000);
    assertThat(apercu.path("manquant").asInt()).isEqualTo(1000);
    assertThat(apercu.path("modeRemiseParDefaut").asText()).isEqualTo("DISPONIBLE");
    assertThat(apercu.path("retardataires")).hasSize(1);
    assertThat(apercu.path("retardataires").get(0).path("membreId").asText())
        .isEqualTo(s.membres().get(4));
    assertThat(apercu.path("retardataires").get(0).path("montant").asInt()).isEqualTo(1000);
    assertThat(option(apercu, "ATTENDRE").path("possible").asBoolean()).isFalse();
    assertThat(option(apercu, "CAISSE").path("possible").asBoolean()).isFalse();
    JsonNode disponible = option(apercu, "DISPONIBLE");
    assertThat(disponible.path("possible").asBoolean()).isTrue();
    assertThat(disponible.path("montantNet").asInt()).isEqualTo(3200);
    assertThat(disponible.path("commission").asInt()).isEqualTo(800);
    assertThat(disponible.path("resteARecevoir").asInt()).isEqualTo(800);
    JsonNode avance = option(apercu, "AVANCE_COMMISSION");
    assertThat(avance.path("possible").asBoolean()).isTrue();
    assertThat(avance.path("montantNet").asInt()).isEqualTo(4000);
    assertThat(avance.path("commission").asInt()).isZero();
    assertThat(avance.path("montantARecuperer").asInt()).isEqualTo(1000);

    Reponse attendre =
        api.put(s.cycle() + "/cloturer", s.jeton(), Map.of("modeRemise", "ATTENDRE"));
    assertThat(attendre.statut()).isEqualTo(409);
    assertThat(attendre.code()).isEqualTo("REMISE_JACKPOT_INCOMPLETE");
    Reponse caisse = api.put(s.cycle() + "/cloturer", s.jeton(), Map.of("modeRemise", "CAISSE"));
    assertThat(caisse.statut()).isEqualTo(400);
    assertThat(caisse.code()).isEqualTo("MODE_REMISE_INDISPONIBLE");
    assertThat(api.get(s.cycle(), s.jeton()).data().path("statut").asText()).isEqualTo("EN_COURS");

    // Sans corps : réglage de la tontine (DISPONIBLE), remise de ce qui est validé
    JsonNode remis =
        TestDataFactory.attendre(api.put(s.cycle() + "/cloturer", s.jeton(), null), 200).data();
    assertThat(remis.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(remis.path("modeRemise").asText()).isEqualTo("DISPONIBLE");
    assertThat(remis.path("montantJackpot").asInt()).isEqualTo(4000);
    assertThat(remis.path("montantJackpotPrevu").asInt()).isEqualTo(5000);
    assertThat(remis.path("montantCommission").asInt()).isEqualTo(800);
    assertThat(remis.path("montantNet").asInt()).isEqualTo(3200);
    assertThat(remis.path("resteARecevoir").asInt()).isEqualTo(800);
    assertThat(remis.path("gagnants")).hasSize(1);
    assertThat(remis.path("gagnants").get(0).path("montantRecu").asInt()).isEqualTo(3200);

    // Le retardataire paie : le bénéficiaire reçoit le complément, la commission suit
    encaisser(s, 4);
    JsonNode regularise = api.get(s.cycle(), s.jeton()).data();
    assertThat(regularise.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(regularise.path("montantJackpot").asInt()).isEqualTo(5000);
    assertThat(regularise.path("montantCommission").asInt()).isEqualTo(1000);
    assertThat(regularise.path("montantNet").asInt()).isEqualTo(4000);
    assertThat(regularise.path("resteARecevoir").asInt()).isZero();
    assertThat(regularise.path("gagnants").get(0).path("montantRecu").asInt()).isEqualTo(4000);
    assertThat(aRecu(s.jeton(), "COMPLEMENT_A_REMETTRE")).isTrue();
  }

  @Test
  @DisplayName(
      "réglage AVANCE_COMMISSION insuffisant (400), AVANCE_GESTIONNAIRE à la remise : net complet,"
          + " avance tracée puis remboursée par les régularisations")
  void avanceDuGestionnaire() {
    Scenario s = scenario("Remise avancée");
    String reglages = "/v1/tontines/" + s.tontineId() + "/reglages";
    encaisser(s, 0);
    encaisser(s, 1);

    Reponse caisse = api.patch(reglages, s.jeton(), Map.of("modeRemise", "CAISSE"));
    assertThat(caisse.statut()).isEqualTo(400);
    assertThat(caisse.code()).isEqualTo("MODE_REMISE_INDISPONIBLE");
    JsonNode tontine =
        TestDataFactory.attendre(
                api.patch(reglages, s.jeton(), Map.of("modeRemise", "AVANCE_COMMISSION")), 200)
            .data();
    assertThat(tontine.path("modeRemise").asText()).isEqualTo("AVANCE_COMMISSION");

    // Collecté 2 000 F pour 4 000 F nets prévus : la commission ne couvre pas le manque
    Reponse insuffisant = api.put(s.cycle() + "/cloturer", s.jeton(), null);
    assertThat(insuffisant.statut()).isEqualTo(400);
    assertThat(insuffisant.code()).isEqualTo("MODE_REMISE_INDISPONIBLE");

    JsonNode remis =
        TestDataFactory.attendre(
                api.put(
                    s.cycle() + "/cloturer",
                    s.jeton(),
                    Map.of("modeRemise", "AVANCE_GESTIONNAIRE")),
                200)
            .data();
    assertThat(remis.path("modeRemise").asText()).isEqualTo("AVANCE_GESTIONNAIRE");
    assertThat(remis.path("montantNet").asInt()).isEqualTo(4000);
    assertThat(remis.path("montantCommission").asInt()).isZero();
    assertThat(remis.path("montantAvance").asInt()).isEqualTo(2000);
    assertThat(remis.path("resteARecevoir").asInt()).isZero();
    assertThat(remis.path("gagnants").get(0).path("montantRecu").asInt()).isEqualTo(4000);

    // Chaque régularisation rembourse d'abord l'avance, puis rend sa commission au gestionnaire
    encaisser(s, 2);
    JsonNode apresUne = api.get(s.cycle(), s.jeton()).data();
    assertThat(apresUne.path("montantAvance").asInt()).isEqualTo(1000);
    assertThat(apresUne.path("montantCommission").asInt()).isZero();
    encaisser(s, 3);
    encaisser(s, 4);
    JsonNode solde = api.get(s.cycle(), s.jeton()).data();
    assertThat(solde.path("montantJackpot").asInt()).isEqualTo(5000);
    assertThat(solde.path("montantAvance").asInt()).isZero();
    assertThat(solde.path("montantCommission").asInt()).isEqualTo(1000);
    assertThat(solde.path("montantNet").asInt()).isEqualTo(4000);
    assertThat(solde.path("gagnants").get(0).path("montantRecu").asInt()).isEqualTo(4000);
    assertThat(aRecu(s.jeton(), "COMPLEMENT_A_REMETTRE")).isFalse();
  }
}
