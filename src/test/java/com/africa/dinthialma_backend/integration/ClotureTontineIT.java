package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Clôture de la tontine (B33, lot 4) : suggestion quand le dernier tour est remis, confirmation
 * exigée s'il reste des points en suspens, effets de la clôture et suivi encore possible ensuite.
 */
@DisplayName("Clôture de la tontine")
class ClotureTontineIT extends AbstractIntegrationTest {

  @Autowired private DistributionEvenementielleRepository distributionRepository;

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

  // ─── B43 : distribution finale de toutes les cotisations validées ────────

  /** Épargne événementielle journalière : montant libre ≥ 2 000 F, commissions éventuelles. */
  private String epargne(Utilisateur g, String nom, int jours, Object commissions) {
    LocalDate aujourdHui = LocalDate.now();
    Map<String, Object> corps = new HashMap<>();
    corps.put("tontineType", "EVENEMENTIELLE");
    corps.put("rythmeIntervalle", 1);
    corps.put("rythmeUnite", "JOUR");
    corps.put("montantLibre", true);
    corps.put("montantMinimum", 2000);
    corps.put("dateDebut", aujourdHui.toString());
    corps.put("dateEcheance", aujourdHui.plusDays(jours).toString());
    if (commissions != null) {
      corps.put("commissions", commissions);
    }
    return donnees.creerTontine(g.jeton(), nom, corps).path("id").asText();
  }

  private String preInscrire(Utilisateur g, String tontineId, String prenom) {
    return TestDataFactory.attendre(
            api.post(
                tontine(tontineId) + "/membres",
                g.jeton(),
                Map.of(
                    "phone",
                    TestDataFactory.nouveauTelephone(),
                    "firstName",
                    prenom,
                    "lastName",
                    "Cahier")),
            201)
        .data()
        .path("id")
        .asText();
  }

  private String cycle(Utilisateur g, String tontineId, int numero) {
    JsonNode page = api.get(tontine(tontineId) + "/cycles?size=50", g.jeton()).data();
    return StreamSupport.stream(page.path("content").spliterator(), false)
        .filter(c -> c.path("numeroCycle").asInt() == numero)
        .findFirst()
        .orElseThrow()
        .path("id")
        .asText();
  }

  /** Part nette enregistrée par membre (distributions_evenementielles). */
  private Map<String, BigDecimal> partsNettes(String tontineId) {
    return distributionRepository.findAll().stream()
        .filter(d -> d.getTontine().getId().toString().equals(tontineId))
        .collect(
            Collectors.toMap(
                d -> d.getMembre().getId().toString(),
                d -> d.getMontantNet().stripTrailingZeros()));
  }

  private void clotureForcee(Utilisateur g, String tontineId) {
    TestDataFactory.attendre(
        api.put(tontine(tontineId) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200);
  }

  private static BigDecimal f(int francs) {
    return BigDecimal.valueOf(francs).stripTrailingZeros();
  }

  @Test
  @DisplayName(
      "B43 : SORTI (PATCH), supprimé (DELETE) et SUSPENDU reçoivent leurs cotisations validées ;"
          + " jackpot du dernier sous-cycle = total encaissé")
  void distributionTousStatuts() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid = epargne(g, "Épargne statuts", 30, null);
    String actif = preInscrire(g, tid, "Actif");
    String sorti = preInscrire(g, tid, "Sorti");
    String supprime = preInscrire(g, tid, "Supprime");
    String suspendu = preInscrire(g, tid, "Suspendu");
    donnees.activer(g.jeton(), tid);
    String jour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, actif, 10000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, sorti, 20000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, supprime, 30000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, suspendu, 40000), 201);
    String membres = tontine(tid) + "/membres/";
    TestDataFactory.attendre(
        api.patch(membres + sorti + "/statut", g.jeton(), Map.of("statut", "SORTI")), 200);
    TestDataFactory.attendre(api.delete(membres + supprime, g.jeton()), 200);
    TestDataFactory.attendre(
        api.patch(membres + suspendu + "/statut", g.jeton(), Map.of("statut", "SUSPENDU")), 200);

    clotureForcee(g, tid);

    assertThat(partsNettes(tid))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(actif, f(10000), sorti, f(20000), supprime, f(30000), suspendu, f(40000)));
    JsonNode dernier = api.get(tontine(tid) + "/cycles", g.jeton()).data().path("content").get(0);
    assertThat(dernier.path("montantJackpot").asInt()).isEqualTo(100000);
    assertThat(dernier.path("montantNet").asInt()).isEqualTo(100000);
  }

  @Test
  @DisplayName("B43 : commission de 10 % répartie au prorata, membres non actifs compris")
  void distributionAvecCommission() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid =
        epargne(
            g,
            "Épargne commission",
            30,
            List.of(Map.of("type", "POURCENTAGE_JACKPOT", "valeur", 10)));
    String actif = preInscrire(g, tid, "Actif");
    String sorti = preInscrire(g, tid, "Sorti");
    donnees.activer(g.jeton(), tid);
    String jour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, actif, 10000), 201);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, sorti, 30000), 201);
    TestDataFactory.attendre(
        api.patch(
            tontine(tid) + "/membres/" + sorti + "/statut", g.jeton(), Map.of("statut", "SORTI")),
        200);

    clotureForcee(g, tid);

    assertThat(partsNettes(tid))
        .containsExactlyInAnyOrderEntriesOf(Map.of(actif, f(9000), sorti, f(27000)));
    JsonNode dernier = api.get(tontine(tid) + "/cycles", g.jeton()).data().path("content").get(0);
    assertThat(dernier.path("montantJackpot").asInt()).isEqualTo(40000);
    assertThat(dernier.path("montantCommission").asInt()).isEqualTo(4000);
    assertThat(dernier.path("montantNet").asInt()).isEqualTo(36000);
  }

  @Test
  @DisplayName(
      "B43 : report du cahier (44 000 / 40 000 / 2 000), jour clôturé, une sortie, clôture"
          + " anticipée → chacune reçoit tout ce qu'elle a versé")
  void reportDuCahierPuisSortie() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid = epargne(g, "Épargne quartier", 40, null);
    String awa = preInscrire(g, tid, "Awa");
    String binta = preInscrire(g, tid, "Binta");
    String coumba = preInscrire(g, tid, "Coumba");
    donnees.activer(g.jeton(), tid);
    String jour1 = cycle(g, tid, 1);
    Map<String, Integer> reports = Map.of(awa, 44000, binta, 40000, coumba, 2000);
    reports.forEach(
        (membre, montant) ->
            TestDataFactory.attendre(encaisser(g.jeton(), tid, jour1, membre, montant), 201));
    remettre(g.jeton(), tid, jour1);
    TestDataFactory.attendre(
        api.patch(
            tontine(tid) + "/membres/" + coumba + "/statut", g.jeton(), Map.of("statut", "SORTI")),
        200);

    clotureForcee(g, tid);

    assertThat(partsNettes(tid))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(awa, f(44000), binta, f(40000), coumba, f(2000)));
  }

  // ─── B47 : pas de paiement d'avance sur un sous-cycle événementiel ───────

  @Test
  @DisplayName(
      "B47 : jour J clôturé, le sous-cycle de demain est EN_COURS mais refuse saisie et"
          + " déclaration (ECHEANCE_NON_OUVERTE)")
  void paiementJourNonCommence() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    String tid = epargne(g, "Pas d'avance", 5, null);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    remettre(g.jeton(), tid, cycle(g, tid, 1));
    String demain = cycle(g, tid, 2);
    assertThat(
            api.get(tontine(tid) + "/cycles/" + demain, g.jeton()).data().path("statut").asText())
        .isEqualTo("EN_COURS");

    Reponse saisie = encaisser(g.jeton(), tid, demain, awaId, 2000);
    assertThat(saisie.statut()).isEqualTo(400);
    assertThat(saisie.code()).isEqualTo("ECHEANCE_NON_OUVERTE");
    Reponse declaration =
        api.post(
            tontine(tid) + "/cotisations", awa.jeton(), Map.of("cycleId", demain, "montant", 2000));
    assertThat(declaration.statut()).isEqualTo(400);
    assertThat(declaration.code()).isEqualTo("ECHEANCE_NON_OUVERTE");
  }

  // ─── B45 : garde-fou de date à la clôture d'un tour ──────────────────────

  @Test
  @DisplayName(
      "B45 événementielle : jour pas commencé → TOUR_NON_COMMENCE ; dernier sous-cycle avant son"
          + " jour → CLOTURE_FINALE_PREMATUREE ; la clôture forcée reste possible")
  void gardeFouEvenementielle() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String journaliere = epargne(g, "Journalière", 2, null);
    preInscrire(g, journaliere, "Awa");
    donnees.activer(g.jeton(), journaliere);
    remettre(g.jeton(), journaliere, cycle(g, journaliere, 1));
    Reponse demain =
        api.put(
            tontine(journaliere) + "/cycles/" + cycle(g, journaliere, 2) + "/cloturer",
            g.jeton(),
            null);
    assertThat(demain.statut()).isEqualTo(400);
    assertThat(demain.code()).isEqualTo("TOUR_NON_COMMENCE");

    // Hebdomadaire sur 4 jours : un seul sous-cycle, commencé aujourd'hui, fini dans 3 jours
    LocalDate aujourdHui = LocalDate.now();
    String semaine =
        donnees
            .creerTontine(
                g.jeton(),
                "Semaine",
                Map.of(
                    "tontineType",
                    "EVENEMENTIELLE",
                    "frequence",
                    "HEBDOMADAIRE",
                    "montant",
                    500,
                    "dateDebut",
                    aujourdHui.toString(),
                    "dateEcheance",
                    aujourdHui.plusDays(3).toString()))
            .path("id")
            .asText();
    String awa = preInscrire(g, semaine, "Awa");
    donnees.activer(g.jeton(), semaine);
    String unique = cycle(g, semaine, 1);
    TestDataFactory.attendre(encaisser(g.jeton(), semaine, unique, awa, 500), 201);
    Reponse prematuree =
        api.put(tontine(semaine) + "/cycles/" + unique + "/cloturer", g.jeton(), null);
    assertThat(prematuree.statut()).isEqualTo(400);
    assertThat(prematuree.code()).isEqualTo("CLOTURE_FINALE_PREMATUREE");
    assertThat(prematuree.json().path("message").asText()).contains("Clôturer la tontine");
    assertThat(partsNettes(semaine)).isEmpty();

    clotureForcee(g, semaine);
    assertThat(partsNettes(semaine)).containsExactlyInAnyOrderEntriesOf(Map.of(awa, f(500)));
  }

  @Test
  @DisplayName(
      "B45 rotative : tour commencé clôturable avant son échéance ; tour suivant pas commencé"
          + " refusé tant que tous n'ont pas payé, permis ensuite")
  void gardeFouRotative() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    String tid =
        donnees
            .creerTontine(
                g.jeton(), "Rotative datée", Map.of("modeCycle", "AUTOMATIQUE", "nombreMembres", 2))
            .path("id")
            .asText();
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);

    // Tour 1 commencé aujourd'hui, échéance dans un mois, Binta n'a pas payé : remise permise
    String tour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour1, awaId, 1000), 201);
    remettre(g.jeton(), tid, tour1);

    // Tour 2 (le mois prochain), ouvert par la remise anticipée du tour 1
    String tour2 = cycle(g, tid, 2);
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour2, awaId, 1000), 201);
    Reponse incomplet = api.put(tontine(tid) + "/cycles/" + tour2 + "/cloturer", g.jeton(), null);
    assertThat(incomplet.statut()).isEqualTo(400);
    assertThat(incomplet.code()).isEqualTo("TOUR_NON_COMMENCE");

    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour2, bintaId, 1000), 201);
    assertThat(remettre(g.jeton(), tid, tour2).path("statut").asText()).isEqualTo("TERMINE");
  }

  @Test
  @DisplayName(
      "B45 rotative TOUS_PAYES AUTOMATIQUE : remise automatique d'un tour pas encore commencé")
  void remiseAutomatiqueAnticipee() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    String tid =
        donnees
            .creerTontine(
                g.jeton(),
                "Tous payés datée",
                Map.of(
                    "modeCycle", "AUTOMATIQUE",
                    "nombreMembres", 2,
                    "declenchementJackpot", "TOUS_PAYES"))
            .path("id")
            .asText();
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    for (int numero = 1; numero <= 2; numero++) {
      String tour = cycle(g, tid, numero);
      TestDataFactory.attendre(encaisser(g.jeton(), tid, tour, awaId, 1000), 201);
      TestDataFactory.attendre(encaisser(g.jeton(), tid, tour, bintaId, 1000), 201);
      assertThat(
              api.get(tontine(tid) + "/cycles/" + tour, g.jeton()).data().path("statut").asText())
          .isEqualTo("TERMINE");
    }
  }

  @Test
  @DisplayName(
      "B45 rotative MANUEL : tour ouvert pour plus tard refusé tant que tous n'ont pas payé,"
          + " remis par le gestionnaire une fois JACKPOT_PRET")
  void manuelTourFutur() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    String tid =
        donnees
            .creerTontine(g.jeton(), "Manuel daté", Map.of("declenchementJackpot", "TOUS_PAYES"))
            .path("id")
            .asText();
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    LocalDate debut = LocalDate.now().plusDays(7);
    String tour =
        TestDataFactory.attendre(
                api.post(
                    tontine(tid) + "/cycles",
                    g.jeton(),
                    Map.of(
                        "dateDebut", debut.toString(), "dateFin", debut.plusDays(30).toString())),
                201)
            .data()
            .path("id")
            .asText();
    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour, awaId, 1000), 201);
    assertThat(api.put(tontine(tid) + "/cycles/" + tour + "/cloturer", g.jeton(), null).code())
        .isEqualTo("TOUR_NON_COMMENCE");

    TestDataFactory.attendre(encaisser(g.jeton(), tid, tour, bintaId, 1000), 201);
    assertThat(aRecu(g, "JACKPOT_PRET")).isTrue();
    assertThat(remettre(g.jeton(), tid, tour).path("statut").asText()).isEqualTo("TERMINE");
  }
}
