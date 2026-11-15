package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Remise du jackpot dès que tous ont payé (B32, lot 3) : tontine AUTOMATIQUE en {@code TOUS_PAYES}
 * à deux membres ; le tour se clôture à la dernière validation. Jackpot prévu, « tous payés » et
 * tableau de bord sur le tour complet.
 */
@DisplayName("Remise TOUS_PAYES et jackpot prévu")
class RemiseTousPayesIT extends AbstractIntegrationTest {

  private static JsonNode tour(JsonNode page, int numero) {
    return StreamSupport.stream(page.path("content").spliterator(), false)
        .filter(c -> c.path("numeroCycle").asInt() == numero)
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName(
      "jackpot prévu et tableau de bord sur le tour complet ; la dernière validation clôture le"
          + " tour, désigne le gagnant et ouvre le tour suivant")
  void parcours() {
    Utilisateur gestionnaire = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    String g = gestionnaire.jeton();
    String tontineId =
        donnees
            .creerTontine(
                g,
                "Tous payés",
                Map.of(
                    "modeCycle", "AUTOMATIQUE",
                    "nombreMembres", 2,
                    "declenchementJackpot", "TOUS_PAYES"))
            .path("id")
            .asText();
    donnees.ajouterMembre(g, tontineId, awa.phone());
    String bintaId = donnees.ajouterMembre(g, tontineId, binta.phone()).path("id").asText();
    donnees.activer(g, tontineId);
    String cycles = "/v1/tontines/" + tontineId + "/cycles";
    String cotisations = "/v1/tontines/" + tontineId + "/cotisations";

    JsonNode premier = tour(api.get(cycles, g).data(), 1);
    String cycleId = premier.path("id").asText();
    assertThat(premier.path("statut").asText()).isEqualTo("EN_COURS");
    // 1 000 F × 2 membres actifs × 1 échéance
    assertThat(premier.path("montantJackpotPrevu").asInt()).isEqualTo(2000);
    assertThat(premier.path("tousPayes").asBoolean()).isFalse();

    // Awa déclare, le gestionnaire valide : il manque Binta, le tour reste ouvert
    String cotisationId =
        donnees.declarer(awa.jeton(), tontineId, cycleId, 1000).path("id").asText();
    TestDataFactory.attendre(api.put(cotisations + "/" + cotisationId + "/valider", g, null), 200);
    JsonNode apresAwa = api.get(cycles + "/" + cycleId, g).data();
    assertThat(apresAwa.path("statut").asText()).isEqualTo("EN_COURS");
    assertThat(apresAwa.path("tousPayes").asBoolean()).isFalse();

    JsonNode info =
        api.get("/v1/admin/my-dashboard", g)
            .data()
            .path("tontines")
            .path("content")
            .get(0)
            .path("cycleEnCours");
    assertThat(info.path("montantAttendu").asInt()).isEqualTo(2000);
    assertThat(info.path("montantJackpotPrevu").asInt()).isEqualTo(2000);
    assertThat(info.path("montantCollecte").asInt()).isEqualTo(1000);
    assertThat(info.path("membresAyantPaye").asInt()).isEqualTo(1);
    assertThat(info.path("membresActifs").asInt()).isEqualTo(2);
    assertThat(info.path("nombreEcheances").asInt()).isEqualTo(1);
    assertThat(info.path("numeroEcheanceCourante").asInt()).isEqualTo(1);
    assertThat(info.path("tousPayes").asBoolean()).isFalse();

    JsonNode monTour =
        api.get("/v1/me/summary", awa.jeton()).data().path("tontines").get(0).path("cycleEnCours");
    assertThat(monTour.path("montantJackpotPrevu").asInt()).isEqualTo(2000);
    assertThat(monTour.path("nombreEcheances").asInt()).isEqualTo(1);

    // Le gestionnaire encaisse Binta : tous ont payé, le tour est remis sans autre action
    TestDataFactory.attendre(
        api.post(
            cotisations + "/admin",
            g,
            Map.of(
                "membreId",
                bintaId,
                "cycleId",
                cycleId,
                "montant",
                1000,
                "methodePaiement",
                "CASH")),
        201);

    JsonNode tours = api.get(cycles, g).data();
    JsonNode remis = tour(tours, 1);
    assertThat(remis.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(remis.path("montantJackpot").asInt()).isEqualTo(2000);
    assertThat(remis.path("gagnants")).hasSize(1);
    assertThat(remis.path("tousPayes").asBoolean()).isFalse();
    assertThat(tour(tours, 2).path("statut").asText()).isEqualTo("EN_COURS");

    // Awa a un tour entièrement payé
    assertThat(
            api.get("/v1/me/summary", awa.jeton())
                .data()
                .path("tontines")
                .get(0)
                .path("toursPayes")
                .asInt())
        .isEqualTo(1);
  }
}
