package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clôture de la tontine (B33, lot 4) : suggestion quand le dernier tour est remis, confirmation
 * exigée s'il reste des points en suspens, effets de la clôture et suivi encore possible ensuite.
 */
@DisplayName("Clôture de la tontine")
class ClotureTontineIT extends AbstractIntegrationTest {

  private static String tontine(String tontineId) {
    return "/v1/tontines/" + tontineId;
  }

  /** Encaissement par le gestionnaire (cotisation créée VALIDE). */
  private Reponse encaisser(
      String jeton, String tontineId, String cycleId, String membreId, int montant) {
    return api.post(
        tontine(tontineId) + "/cotisations/admin",
        jeton,
        Map.of(
            "membreId",
            membreId,
            "cycleId",
            cycleId,
            "montant",
            montant,
            "methodePaiement",
            "CASH"));
  }

  private JsonNode remettre(String jeton, String tontineId, String cycleId) {
    return TestDataFactory.attendre(
            api.put(tontine(tontineId) + "/cycles/" + cycleId + "/cloturer", jeton, null), 200)
        .data();
  }

  private boolean aRecu(Utilisateur utilisateur, String type) {
    JsonNode notifs =
        api.get("/v1/notifications?size=50", utilisateur.jeton()).data().path("content");
    return StreamSupport.stream(notifs.spliterator(), false)
        .anyMatch(n -> type.equals(n.path("type").asText()));
  }

  @Test
  @DisplayName(
      "rotative soldée : clôture suggérée au dernier tour remis, puis TERMINEE sans confirmation")
  void rotativeSoldee() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    String tid = donnees.creerTontine(g.jeton(), "Soldée").path("id").asText();
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);

    String tour1 = donnees.ouvrirCycle(g.jeton(), tid).path("id").asText();
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour1, awaId, 1000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour1, bintaId, 1000), 201);
    remettre(g.jeton(), tid, tour1);
    assertThat(aRecu(g, "TONTINE_PRETE_A_CLOTURER")).isFalse(); // Binta attend son jackpot

    String tour2 = donnees.ouvrirCycle(g.jeton(), tid).path("id").asText();
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour2, awaId, 1000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour2, bintaId, 1000), 201);
    remettre(g.jeton(), tid, tour2);
    assertThat(aRecu(g, "TONTINE_PRETE_A_CLOTURER")).isTrue();
    assertThat(api.get(tontine(tid), g.jeton()).data().path("statut").asText())
        .isEqualTo("ACTIVE"); // l'app suggère, elle ne clôture pas

    // Un membre ne clôture pas la tontine
    assertThat(api.put(tontine(tid) + "/cloturer", awa.jeton(), null).statut()).isEqualTo(403);

    JsonNode cloturee =
        TestDataFactory.attendre(api.put(tontine(tid) + "/cloturer", g.jeton(), null), 200).data();
    assertThat(cloturee.path("statut").asText()).isEqualTo("TERMINEE");
    assertThat(aRecu(awa, "TONTINE_CLOTUREE")).isTrue();
    assertThat(aRecu(binta, "TONTINE_CLOTUREE")).isTrue();

    Reponse encore = api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true));
    assertThat(encore.statut()).isEqualTo(400);
    assertThat(encore.code()).isEqualTo("TONTINE_NON_CLOTURABLE");
  }

  @Test
  @DisplayName(
      "rotative avec un retard : 409 TONTINE_CLOTURE_A_CONFIRMER détaillé, forcer → TERMINEE,"
          + " demande et priorité fermées, retard encore encaissable, plus aucun tour")
  void rotativeAvecRetard() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur ponctuelle = donnees.inscrire("Ponctuelle");
    Utilisateur retardataire = donnees.inscrire("Retardataire");
    Utilisateur candidat = donnees.inscrire("Candidat");
    String tid = donnees.creerTontine(g.jeton(), "Retard").path("id").asText();
    String ponctuelleId =
        donnees.ajouterMembre(g.jeton(), tid, ponctuelle.phone()).path("id").asText();
    String retardataireId =
        donnees.ajouterMembre(g.jeton(), tid, retardataire.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    String tour1 = donnees.ouvrirCycle(g.jeton(), tid).path("id").asText();
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour1, ponctuelleId, 1000), 201);

    // Brouillon voisin : il ne se clôture pas
    String brouillon = donnees.creerTontine(g.jeton(), "Brouillon").path("id").asText();
    assertThat(api.put(tontine(brouillon) + "/cloturer", g.jeton(), null).code())
        .isEqualTo("TONTINE_NON_CLOTURABLE");

    // Tour encore ouvert : listé comme non remis
    Reponse tourOuvert = api.put(tontine(tid) + "/cloturer", g.jeton(), null);
    assertThat(tourOuvert.statut()).isEqualTo(409);
    assertThat(tourOuvert.code()).isEqualTo("TONTINE_CLOTURE_A_CONFIRMER");
    assertThat(tourOuvert.data().path("toursNonRemis")).hasSize(1);
    assertThat(tourOuvert.data().path("toursNonRemis").get(0).path("cycleId").asText())
        .isEqualTo(tour1);
    assertThat(tourOuvert.data().path("toursNonRemis").get(0).path("statut").asText())
        .isEqualTo("EN_COURS");

    // Remise de ce qui est validé : 1 000 F sur 2 000 F prévus, la ponctuelle est servie
    remettre(g.jeton(), tid, tour1);
    String code =
        api.post(tontine(tid) + "/invitation", g.jeton(), null).data().path("code").asText();
    TestDataFactory.attendre(
        api.post("/v1/invitations/" + code + "/demandes", candidat.jeton(), Map.of()), 201);
    TestDataFactory.attendre(
        api.post(tontine(tid) + "/priorites", retardataire.jeton(), Map.of("motif", "Besoin")),
        201);

    Reponse aConfirmer = api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", false));
    assertThat(aConfirmer.statut()).isEqualTo(409);
    assertThat(aConfirmer.code()).isEqualTo("TONTINE_CLOTURE_A_CONFIRMER");
    JsonNode enSuspens = aConfirmer.data();
    assertThat(enSuspens.path("toursNonRemis")).isEmpty();
    assertThat(enSuspens.path("toursSansGagnant")).isEmpty();
    assertThat(enSuspens.path("retards")).hasSize(1);
    assertThat(enSuspens.path("retards").get(0).path("membreId").asText())
        .isEqualTo(retardataireId);
    assertThat(enSuspens.path("retards").get(0).path("echeancesManquees").asInt()).isEqualTo(1);
    assertThat(enSuspens.path("retards").get(0).path("montant").asInt()).isEqualTo(1000);
    assertThat(enSuspens.path("complements")).hasSize(1);
    assertThat(enSuspens.path("complements").get(0).path("cycleId").asText()).isEqualTo(tour1);
    assertThat(enSuspens.path("complements").get(0).path("resteARecevoir").asInt()).isEqualTo(1000);
    assertThat(enSuspens.path("cotisationsAValider").asInt()).isZero();
    assertThat(api.get(tontine(tid), g.jeton()).data().path("statut").asText()).isEqualTo("ACTIVE");

    JsonNode cloturee =
        TestDataFactory.attendre(
                api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200)
            .data();
    assertThat(cloturee.path("statut").asText()).isEqualTo("TERMINEE");
    assertThat(aRecu(retardataire, "TONTINE_CLOTUREE")).isTrue();

    // Demande d'adhésion refusée et priorité annulée d'office
    JsonNode demande = api.get("/v1/me/demandes", candidat.jeton()).data().path("content").get(0);
    assertThat(demande.path("statut").asText()).isEqualTo("REFUSEE");
    assertThat(
            api.get(tontine(tid) + "/priorites?statut=ANNULEE", g.jeton()).data().path("content"))
        .hasSize(1);
    assertThat(
            api.get(tontine(tid) + "/priorites?statut=EN_ATTENTE", g.jeton())
                .data()
                .path("content"))
        .isEmpty();

    // Après la clôture : le retard s'encaisse encore (suivi), le complément suit
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour1, retardataireId, 1000), 201);
    JsonNode regularise = api.get(tontine(tid) + "/cycles/" + tour1, g.jeton()).data();
    assertThat(regularise.path("montantJackpot").asInt()).isEqualTo(2000);
    assertThat(regularise.path("resteARecevoir").asInt()).isZero();

    // … mais plus aucun tour ne s'ouvre et la tontine ne se réactive pas
    Reponse nouveauTour =
        api.post(
            tontine(tid) + "/cycles",
            g.jeton(),
            Map.of(
                "dateDebut",
                LocalDate.now().toString(),
                "dateFin",
                LocalDate.now().plusDays(30).toString()));
    assertThat(nouveauTour.statut()).isEqualTo(400);
    assertThat(api.put(tontine(tid) + "/activer", g.jeton(), null).statut()).isEqualTo(400);
  }

  @Test
  @DisplayName(
      "événementielle arrêtée avant son terme : 409, puis forcer → distribution finale immédiate,"
          + " sous-cycles à venir retirés")
  void evenementielleAvantSonTerme() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur membre = donnees.inscrire("Membre");
    LocalDate aujourdHui = LocalDate.now();
    String tid =
        donnees
            .creerTontine(
                g.jeton(),
                "Événement écourté",
                Map.of(
                    "tontineType",
                    "EVENEMENTIELLE",
                    "frequence",
                    "JOURNALIERE",
                    "montant",
                    500,
                    "dateDebut",
                    aujourdHui.toString(),
                    "dateEcheance",
                    aujourdHui.plusDays(2).toString(),
                    "nomEvenement",
                    "Fête"))
            .path("id")
            .asText();
    String membreId = donnees.ajouterMembre(g.jeton(), tid, membre.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    JsonNode cycles = api.get(tontine(tid) + "/cycles", g.jeton()).data().path("content");
    assertThat(cycles).hasSize(3);
    String enCours = cycles.get(0).path("id").asText();
    TestDataFactory.attendre(encaisser(g.jeton(), tid, enCours, membreId, 500), 201);

    Reponse aConfirmer = api.put(tontine(tid) + "/cloturer", g.jeton(), null);
    assertThat(aConfirmer.statut()).isEqualTo(409);
    assertThat(aConfirmer.code()).isEqualTo("TONTINE_CLOTURE_A_CONFIRMER");
    assertThat(aConfirmer.data().path("toursNonRemis")).hasSize(3);
    assertThat(aConfirmer.data().path("retards")).isEmpty();

    JsonNode cloturee =
        TestDataFactory.attendre(
                api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200)
            .data();
    assertThat(cloturee.path("statut").asText()).isEqualTo("TERMINEE");

    JsonNode restants = api.get(tontine(tid) + "/cycles", g.jeton()).data().path("content");
    assertThat(restants).hasSize(1);
    assertThat(restants.get(0).path("statut").asText()).isEqualTo("TERMINE");
    assertThat(restants.get(0).path("montantNet").asInt()).isEqualTo(500);
    JsonNode recu = api.get("/v1/me/jackpots", membre.jeton()).data().path("recus").get(0);
    assertThat(recu.path("nature").asText()).isEqualTo("DISTRIBUTION_FINALE");
    assertThat(aRecu(membre, "DISTRIBUTION_FINALE")).isTrue();
    assertThat(aRecu(membre, "TONTINE_CLOTUREE")).isTrue();
    assertThat(aRecu(g, "TONTINE_PRETE_A_CLOTURER")).isFalse();

    // Distribution faite : plus aucune cotisation
    assertThat(encaisser(g.jeton(), tid, enCours, membreId, 500).statut()).isIn(400, 409);
  }
}
