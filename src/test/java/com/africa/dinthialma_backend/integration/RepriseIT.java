package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reprise d'une épargne déjà commencée sur un cahier (B41, lot 1) : soldes de départ, tour de
 * reprise n° 0, gel, et prise en compte dans le solde et la distribution finale.
 */
@DisplayName("Reprise d'une tontine déjà commencée")
class RepriseIT extends AbstractIntegrationTest {

  private record Epargne(
      Utilisateur gestionnaire, Utilisateur awa, String tontineId, String awaId, String bintaId) {

    String base() {
      return "/v1/tontines/" + tontineId;
    }
  }

  private Epargne epargne(String nom, boolean activer) {
    Utilisateur gestionnaire = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    Map<String, Object> corps = new HashMap<>();
    corps.put("tontineType", "EVENEMENTIELLE");
    corps.put("frequence", "JOURNALIERE");
    corps.put("dateEcheance", LocalDate.now().plusDays(3).toString());
    String g = gestionnaire.jeton();
    String tontineId = donnees.creerTontine(g, nom, corps).path("id").asText();
    String awaId = donnees.ajouterMembre(g, tontineId, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g, tontineId, binta.phone()).path("id").asText();
    if (activer) {
      donnees.activer(g, tontineId);
    }
    return new Epargne(gestionnaire, awa, tontineId, awaId, bintaId);
  }

  private static Map<String, Object> reprise(LocalDate cahier, Object... membreMontant) {
    List<Map<String, Object>> soldes = new java.util.ArrayList<>();
    for (int i = 0; i < membreMontant.length; i += 2) {
      soldes.add(Map.of("membreId", membreMontant[i], "montant", membreMontant[i + 1]));
    }
    return Map.of("dateDebutReelle", cahier.toString(), "soldes", soldes);
  }

  private List<JsonNode> tours(Epargne e) {
    JsonNode page =
        api.get(e.base() + "/cycles?size=50&sort=numeroCycle,asc", e.gestionnaire().jeton())
            .data()
            .path("content");
    return StreamSupport.stream(page.spliterator(), false).toList();
  }

  private int solde(Epargne e, String membreId) {
    return api.get(e.base() + "/membres/" + membreId + "/solde", e.gestionnaire().jeton())
        .data()
        .path("solde")
        .asInt();
  }

  private boolean aRecu(Utilisateur u, String type) {
    JsonNode notifs = api.get("/v1/notifications?size=50", u.jeton()).data().path("content");
    return StreamSupport.stream(notifs.spliterator(), false)
        .anyMatch(n -> type.equals(n.path("type").asText()));
  }

  @Test
  @DisplayName(
      "soldes de départ sur le tour n° 0, visibles du membre, comptés dans le solde ; gel,"
          + " message SOLDE_DEPART, puis distribution finale qui les inclut")
  void repriseJusquALaDistribution() {
    Epargne e = epargne("Epargne du cahier", true);
    String g = e.gestionnaire().jeton();
    LocalDate cahier = LocalDate.now().minusDays(21);

    JsonNode r =
        TestDataFactory.attendre(
                api.put(
                    e.base() + "/reprise",
                    g,
                    reprise(cahier, e.awaId(), 42000, e.bintaId(), 36000)),
                200)
            .data();
    assertThat(r.path("statut").asText()).isEqualTo("OUVERTE");
    assertThat(r.path("total").asInt()).isEqualTo(78000);
    assertThat(r.path("soldes")).hasSize(2);

    JsonNode tourZero = tours(e).get(0);
    assertThat(tourZero.path("numeroCycle").asInt()).isZero();
    assertThat(tourZero.path("statut").asText()).isEqualTo("TERMINE");
    assertThat(tourZero.path("dateDebut").asText()).isEqualTo(cahier.toString());
    assertThat(tours(e).get(1).path("statut").asText()).isEqualTo("EN_COURS");

    JsonNode vueMembre = api.get(e.base() + "/reprise", e.awa().jeton()).data();
    assertThat(vueMembre.path("soldes")).hasSize(1);
    assertThat(vueMembre.path("soldes").get(0).path("montant").asInt()).isEqualTo(42000);
    assertThat(api.get(e.base(), g).data().path("dateDebutReelle").asText())
        .isEqualTo(cahier.toString());

    // Le tour de reprise ne se paie pas ; la cotisation du jour s'ajoute au solde de départ
    Reponse surReprise =
        api.post(
            e.base() + "/cotisations",
            e.awa().jeton(),
            Map.of(
                "cycleId",
                tourZero.path("id").asText(),
                "montant",
                1000,
                "methodePaiement",
                "WAVE"));
    assertThat(surReprise.statut()).isEqualTo(400);
    assertThat(surReprise.code()).isEqualTo("TOUR_DE_REPRISE");
    TestDataFactory.attendre(
        api.post(
            e.base() + "/cotisations/admin",
            g,
            Map.of(
                "membreId",
                e.awaId(),
                "cycleId",
                tours(e).get(1).path("id").asText(),
                "montant",
                1000,
                "methodePaiement",
                "CASH")),
        201);
    assertThat(solde(e, e.awaId())).isEqualTo(43000);

    JsonNode figee =
        TestDataFactory.attendre(api.post(e.base() + "/reprise/figer", g, null), 200).data();
    assertThat(figee.path("statut").asText()).isEqualTo("FIGEE");
    assertThat(aRecu(e.awa(), "SOLDE_DEPART")).isTrue();
    Reponse apresGel = api.put(e.base() + "/reprise", g, reprise(cahier, e.awaId(), 50000));
    assertThat(apresGel.statut()).isEqualTo(400);
    assertThat(apresGel.code()).isEqualTo("REPRISE_FIGEE");

    TestDataFactory.attendre(api.put(e.base() + "/cloturer", g, Map.of("forcer", true)), 200);
    JsonNode distribution = api.get(e.base() + "/distribution", g).data();
    assertThat(distribution.path("totalCotise").asInt()).isEqualTo(79000);
    JsonNode partAwa =
        StreamSupport.stream(distribution.path("lignes").spliterator(), false)
            .filter(l -> e.awaId().equals(l.path("membreId").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(partAwa.path("montantNet").asInt()).isEqualTo(43000);
  }

  @Test
  @DisplayName(
      "reprise saisie en BROUILLON : l'activation génère quand même les tours ; premier retrait"
          + " = gel automatique ; rotative refusée")
  void brouillonPuisRetrait() {
    Epargne e = epargne("Epargne en brouillon", false);
    String g = e.gestionnaire().jeton();
    TestDataFactory.attendre(
        api.put(
            e.base() + "/reprise",
            g,
            reprise(LocalDate.now().minusDays(5), e.awaId(), 5000, e.bintaId(), 4000)),
        200);

    donnees.activer(g, e.tontineId());
    List<JsonNode> tours = tours(e);
    assertThat(tours).hasSize(5);
    assertThat(tours.get(0).path("numeroCycle").asInt()).isZero();
    assertThat(tours.get(1).path("statut").asText()).isEqualTo("EN_COURS");

    TestDataFactory.attendre(
        api.post(
            e.base() + "/retraits/admin",
            g,
            Map.of(
                "membreId",
                e.awaId(),
                "nature",
                "PARTIEL",
                "montant",
                2000,
                "methodePaiement",
                "CASH")),
        201);
    assertThat(api.get(e.base() + "/reprise", g).data().path("statut").asText()).isEqualTo("FIGEE");
    assertThat(solde(e, e.awaId())).isEqualTo(3000);

    String rotative = donnees.creerTontine(g, "Rotative").path("id").asText();
    Reponse refus =
        api.put("/v1/tontines/" + rotative + "/reprise", g, reprise(LocalDate.now().minusDays(5)));
    assertThat(refus.statut()).isEqualTo(400);
    assertThat(refus.code()).isEqualTo("REPRISE_NON_AUTORISEE");
  }
}
