package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.africa.dinthialma_backend.tontine.service.interfaces.ToursPlanifiesService;
import com.africa.dinthialma_backend.tontine.service.interfaces.ToursPlanifiesService.Bilan;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tours ouverts à leur date et remise automatique à l'échéance (B33, lot 3). Le traitement planifié
 * est appelé à l'instant voulu ({@link ToursPlanifiesService#traiter}).
 */
@DisplayName("Ouverture des tours à leur date et remise automatique")
class OuvertureToursIT extends AbstractIntegrationTest {

  @Autowired private ToursPlanifiesService toursPlanifies;

  private record Tontine(
      String jeton, String tontineId, List<Utilisateur> membres, List<JsonNode> tours) {

    String base() {
      return "/v1/tontines/" + tontineId;
    }

    LocalDate debut(int numero) {
      return LocalDate.parse(tours.get(numero - 1).path("dateDebut").asText());
    }

    String cycleId(int numero) {
      return tours.get(numero - 1).path("id").asText();
    }
  }

  /** Tontine activée (tours générés), avec 3 membres inscrits. */
  private Tontine activee(String nom, Map<String, Object> surcharges) {
    Utilisateur gestionnaire = donnees.inscrire("Gestionnaire");
    String g = gestionnaire.jeton();
    JsonNode creee = donnees.creerTontine(g, nom, surcharges);
    String tontineId = creee.path("id").asText();
    List<Utilisateur> membres = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      Utilisateur membre = donnees.inscrire("Membre" + i);
      donnees.ajouterMembre(g, tontineId, membre.phone());
      membres.add(membre);
    }
    donnees.activer(g, tontineId);
    return new Tontine(g, tontineId, membres, tours(g, tontineId));
  }

  private List<JsonNode> tours(String jeton, String tontineId) {
    JsonNode page =
        api.get("/v1/tontines/" + tontineId + "/cycles?size=50&sort=numeroCycle,asc", jeton)
            .data()
            .path("content");
    return StreamSupport.stream(page.spliterator(), false).toList();
  }

  private String statut(Tontine t, int numero) {
    return api.get(t.base() + "/cycles/" + t.cycleId(numero), t.jeton())
        .data()
        .path("statut")
        .asText();
  }

  private static Map<String, Object> rotativeHebdomadaire() {
    Map<String, Object> corps = new HashMap<>();
    corps.put("modeCycle", "AUTOMATIQUE");
    corps.put("frequence", "HEBDOMADAIRE");
    corps.put("nombreMembres", 3);
    return corps;
  }

  @Test
  @DisplayName(
      "rotative A_LA_DATE : le tour 2 s'ouvre à sa date sans remise du tour 1, se paie, puis la"
          + " remise automatique activée en cours remet le tour 1 échu")
  void rotativeALaDatePuisRemiseAutomatique() {
    Tontine t = activee("A la date", rotativeHebdomadaire());
    JsonNode detail = api.get(t.base(), t.jeton()).data();
    assertThat(detail.path("ouvertureTours").asText()).isEqualTo("A_LA_DATE");
    assertThat(detail.path("remiseAutomatique").asBoolean()).isFalse();
    assertThat(t.tours()).hasSize(3);

    // Base partagée entre les tests : le bilan compte aussi les tours des autres tontines
    Bilan ouverture = toursPlanifies.traiter(t.debut(2).atTime(0, 5));
    assertThat(ouverture.toursOuverts()).isPositive();
    assertThat(statut(t, 1)).isEqualTo("EN_COURS");
    assertThat(statut(t, 2)).isEqualTo("EN_COURS");
    assertThat(statut(t, 3)).isEqualTo("EN_ATTENTE");

    JsonNode resume =
        api.get("/v1/admin/my-dashboard", t.jeton()).data().path("tontines").path("content").get(0);
    assertThat(resume.path("cycleEnCours").path("cycleId").asText()).isEqualTo(t.cycleId(2));
    assertThat(resume.path("toursARemettre")).hasSize(1);
    assertThat(resume.path("toursARemettre").get(0).path("cycleId").asText())
        .isEqualTo(t.cycleId(1));

    // Le tour 1 n'est pas remis : les membres paient quand même le tour 2
    donnees.declarer(t.membres().get(0).jeton(), t.tontineId(), t.cycleId(2), 1000);

    JsonNode reglee =
        TestDataFactory.attendre(
                api.patch(t.base() + "/reglages", t.jeton(), Map.of("remiseAutomatique", true)),
                200)
            .data();
    assertThat(reglee.path("remiseAutomatique").asBoolean()).isTrue();

    Bilan remise = toursPlanifies.traiter(t.debut(2).atTime(0, 10));
    assertThat(remise.toursRemis()).isPositive();
    JsonNode tour1 = api.get(t.base() + "/cycles/" + t.cycleId(1), t.jeton()).data();
    assertThat(tour1.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(tour1.path("modeRemise").asText()).isEqualTo("DISPONIBLE");
    assertThat(tour1.path("gagnants")).hasSize(1);
    assertThat(statut(t, 2)).isEqualTo("EN_COURS");

    // Passage suivant : rien à refaire sur cette tontine
    toursPlanifies.traiter(t.debut(2).atTime(0, 15));
    assertThat(api.get(t.base() + "/cycles/" + t.cycleId(1), t.jeton()).data().path("gagnants"))
        .hasSize(1);
    assertThat(statut(t, 2)).isEqualTo("EN_COURS");
    assertThat(statut(t, 3)).isEqualTo("EN_ATTENTE");
  }

  @Test
  @DisplayName(
      "A_LA_REMISE : le tour suivant attend la remise ; passage à A_LA_DATE en cours : il s'ouvre"
          + " au passage suivant ; remise automatique refusée en événementielle")
  void aLaRemisePuisChangement() {
    Map<String, Object> corps = rotativeHebdomadaire();
    corps.put("ouvertureTours", "A_LA_REMISE");
    Tontine t = activee("A la remise", corps);

    toursPlanifies.traiter(t.debut(2).atTime(0, 5));
    assertThat(statut(t, 2)).isEqualTo("EN_ATTENTE");

    TestDataFactory.attendre(
        api.patch(t.base() + "/reglages", t.jeton(), Map.of("ouvertureTours", "A_LA_DATE")), 200);
    toursPlanifies.traiter(t.debut(2).atTime(0, 10));
    assertThat(statut(t, 2)).isEqualTo("EN_COURS");

    Map<String, Object> evt = new HashMap<>();
    evt.put("tontineType", "EVENEMENTIELLE");
    evt.put("frequence", "JOURNALIERE");
    evt.put("dateEcheance", LocalDate.now().plusDays(3).toString());
    String evtId = donnees.creerTontine(t.jeton(), "Evenement", evt).path("id").asText();
    Reponse refus =
        api.patch(
            "/v1/tontines/" + evtId + "/reglages", t.jeton(), Map.of("remiseAutomatique", true));
    assertThat(refus.statut()).isEqualTo(400);
    assertThat(refus.code()).isEqualTo("REMISE_AUTOMATIQUE_NON_DISPONIBLE");
  }

  @Test
  @DisplayName(
      "événementielle journalière A_LA_DATE : chaque sous-cycle s'ouvre à sa date et clôture le"
          + " précédent, la distribution finale reste manuelle")
  void evenementielleBascule() {
    Map<String, Object> corps = new HashMap<>();
    corps.put("tontineType", "EVENEMENTIELLE");
    corps.put("frequence", "JOURNALIERE");
    corps.put("dateEcheance", LocalDate.now().plusDays(3).toString());
    Tontine t = activee("Epargne journaliere", corps);
    assertThat(t.tours()).hasSize(4);
    donnees.declarer(t.membres().get(0).jeton(), t.tontineId(), t.cycleId(1), 1000);

    toursPlanifies.traiter(t.debut(3).atTime(0, 5));
    assertThat(statut(t, 1)).isEqualTo("TERMINE");
    assertThat(statut(t, 2)).isEqualTo("TERMINE");
    assertThat(statut(t, 3)).isEqualTo("EN_COURS");
    assertThat(statut(t, 4)).isEqualTo("EN_ATTENTE");

    toursPlanifies.traiter(t.debut(4).atTime(0, 5));
    assertThat(statut(t, 3)).isEqualTo("TERMINE");
    assertThat(statut(t, 4)).isEqualTo("EN_COURS");

    Reponse distribution = api.get(t.base() + "/distribution", t.jeton());
    assertThat(distribution.statut()).isEqualTo(404);
    assertThat(distribution.code()).isEqualTo("DISTRIBUTION_NON_FAITE");
  }
}
