package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Régularisation des retards (B33, lot 1) : après la remise d'un tour, la déclaration restée en
 * attente se valide, et le membre qui n'avait rien déclaré peut encore payer ce tour.
 */
@DisplayName("Régularisation d'un retard après la remise")
class RegularisationIT extends AbstractIntegrationTest {

  private JsonNode aRegulariser(Utilisateur membre) {
    return api.get("/v1/me/summary", membre.jeton())
        .data()
        .path("tontines")
        .get(0)
        .path("echeancesARegulariser");
  }

  @Test
  @DisplayName(
      "tour remis : EN_RETARD validable, échéance impayée listée puis payée, doublon refusé (409)")
  void parcours() {
    Utilisateur gestionnaire = donnees.inscrire("Gestionnaire");
    Utilisateur ponctuel = donnees.inscrire("Ponctuel");
    Utilisateur enAttente = donnees.inscrire("EnAttente");
    Utilisateur absent = donnees.inscrire("Absent");
    String g = gestionnaire.jeton();
    String tontineId = donnees.creerTontine(g, "Retards").path("id").asText();
    donnees.ajouterMembre(g, tontineId, ponctuel.phone());
    donnees.ajouterMembre(g, tontineId, enAttente.phone());
    donnees.ajouterMembre(g, tontineId, absent.phone());
    donnees.activer(g, tontineId);
    String cycleId = donnees.ouvrirCycle(g, tontineId).path("id").asText();
    String cotisations = "/v1/tontines/" + tontineId + "/cotisations";

    String payee = donnees.declarer(ponctuel.jeton(), tontineId, cycleId, 1000).path("id").asText();
    TestDataFactory.attendre(api.put(cotisations + "/" + payee + "/valider", g, null), 200);
    String nonValidee =
        donnees.declarer(enAttente.jeton(), tontineId, cycleId, 1000).path("id").asText();
    assertThat(aRegulariser(absent)).isEmpty(); // tour encore ouvert : rien à régulariser

    TestDataFactory.attendre(
        api.put("/v1/tontines/" + tontineId + "/cycles/" + cycleId + "/cloturer", g, null), 200);

    // La déclaration restée en attente est passée EN_RETARD : elle se valide quand même
    assertThat(api.get(cotisations + "/" + nonValidee, g).data().path("statut").asText())
        .isEqualTo("EN_RETARD");
    JsonNode validee =
        TestDataFactory.attendre(api.put(cotisations + "/" + nonValidee + "/valider", g, null), 200)
            .data();
    assertThat(validee.path("statut").asText()).isEqualTo("VALIDE");
    assertThat(aRegulariser(enAttente)).isEmpty();

    // Le membre qui n'a rien déclaré voit l'échéance du tour remis, et la paie
    JsonNode due = aRegulariser(absent);
    assertThat(due).hasSize(1);
    assertThat(due.get(0).path("cycleId").asText()).isEqualTo(cycleId);
    assertThat(due.get(0).path("numeroCycle").asInt()).isEqualTo(1);
    assertThat(due.get(0).path("montant").asInt()).isEqualTo(1000);

    JsonNode regularisation = donnees.declarer(absent.jeton(), tontineId, cycleId, 1000);
    assertThat(regularisation.path("statut").asText()).isEqualTo("EN_ATTENTE");
    assertThat(regularisation.path("echeanceId").asText())
        .isEqualTo(due.get(0).path("echeanceId").asText());
    assertThat(aRegulariser(absent)).isEmpty();

    Reponse doublon =
        api.post(
            cotisations,
            absent.jeton(),
            Map.of("cycleId", cycleId, "montant", 1000, "methodePaiement", "WAVE"));
    assertThat(doublon.statut()).isEqualTo(409);
    assertThat(doublon.code()).isEqualTo("COTISATION_ALREADY_EXISTS");

    TestDataFactory.attendre(
        api.put(cotisations + "/" + regularisation.path("id").asText() + "/valider", g, null), 200);
    // Le tour remis ne change pas : le complément au bénéficiaire arrive avec les options de remise
    JsonNode tour = api.get("/v1/tontines/" + tontineId + "/cycles/" + cycleId, g).data();
    assertThat(tour.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(tour.path("montantJackpot").asInt()).isEqualTo(1000);
  }
}
