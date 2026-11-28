package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Retraits anticipés saisis par le gestionnaire (B42, lot 1), sur le scénario du client : épargne
 * journalière commencée il y a quelques jours, reports du cahier, retraits partiel et total, puis
 * clôture finale du dernier jour.
 */
@DisplayName("Retraits anticipés d'épargne (B42)")
class RetraitsEpargneIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  private static String tontine(String tontineId) {
    return "/v1/tontines/" + tontineId;
  }

  /** Épargne journalière libre (≥ 2 000 F), {@code jours} + 1 sous-cycles depuis aujourd'hui. */
  private String epargne(Utilisateur g, String nom, int jours, Object commissions) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("tontineType", "EVENEMENTIELLE");
    corps.put("rythmeIntervalle", 1);
    corps.put("rythmeUnite", "JOUR");
    corps.put("montantLibre", true);
    corps.put("montantMinimum", 2000);
    corps.put("dateDebut", LocalDate.now().toString());
    corps.put("dateEcheance", LocalDate.now().plusDays(jours).toString());
    if (commissions != null) {
      corps.put("commissions", commissions);
    }
    return donnees.creerTontine(g.jeton(), nom, corps).path("id").asText();
  }

  /** Recule toute la tontine de {@code jours} jours en base : elle a commencé il y a N jours. */
  private void reculer(String tontineId, int jours) {
    jdbc.update(
        "UPDATE dinthialma.tontines SET date_debut = date_debut - ?, date_echeance = date_echeance"
            + " - ? WHERE id = ?::uuid",
        jours,
        jours,
        tontineId);
    jdbc.update(
        "UPDATE dinthialma.cycles_tontine SET date_debut = date_debut - ?, date_fin = date_fin - ?,"
            + " echeance_at = echeance_at - make_interval(days => ?) WHERE tontine_id = ?::uuid",
        jours,
        jours,
        jours,
        tontineId);
    jdbc.update(
        "UPDATE dinthialma.echeances_cycle SET date_debut = date_debut - ?, echeance_at ="
            + " echeance_at - make_interval(days => ?) WHERE cycle_id IN (SELECT id FROM"
            + " dinthialma.cycles_tontine WHERE tontine_id = ?::uuid)",
        jours,
        jours,
        tontineId);
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

  private Reponse encaisser(
      Utilisateur g, String tontineId, String cycleId, String membreId, int francs) {
    return api.post(
        tontine(tontineId) + "/cotisations/admin",
        g.jeton(),
        Map.of(
            "membreId",
            membreId,
            "cycleId",
            cycleId,
            "montant",
            francs,
            "methodePaiement",
            "CASH"));
  }

  private void remettre(Utilisateur g, String tontineId, String cycleId) {
    TestDataFactory.attendre(
        api.put(tontine(tontineId) + "/cycles/" + cycleId + "/cloturer", g.jeton(), null), 200);
  }

  private Reponse retirer(Utilisateur g, String tontineId, Map<String, Object> corps) {
    return api.post(tontine(tontineId) + "/retraits/admin", g.jeton(), corps);
  }

  private static Map<String, Object> retrait(
      String membreId, String nature, Integer montant, LocalDateTime date) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("membreId", membreId);
    corps.put("nature", nature);
    corps.put("methodePaiement", "CASH");
    if (montant != null) corps.put("montant", montant);
    if (date != null) corps.put("dateRemise", date.toString());
    return corps;
  }

  private long notificationsRetrait(Utilisateur u) {
    JsonNode notifs = api.get("/v1/notifications?size=50", u.jeton()).data().path("content");
    return StreamSupport.stream(notifs.spliterator(), false)
        .filter(n -> "RETRAIT_REMIS".equals(n.path("type").asText()))
        .count();
  }

  private JsonNode ligne(JsonNode lignes, String membreId) {
    return StreamSupport.stream(lignes.spliterator(), false)
        .filter(l -> l.path("membreId").asText().equals(membreId))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName(
      "scénario client : reports, retrait partiel daté d'hier sans notification, retrait total"
          + " (membre soldé), clôture finale : nets + retraits = total encaissé")
  void scenarioClient() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    String tid = epargne(g, "Épargne quartier", 3, null);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String binta = preInscrire(g, tid, "Binta");
    String coumba = preInscrire(g, tid, "Coumba");
    donnees.activer(g.jeton(), tid);
    assertThat(api.get(tontine(tid), g.jeton()).data().path("regleRetrait").asText())
        .isEqualTo("LIBRE");
    // Épargne commencée il y a 3 jours : jours 1 à 3 passés, jour 4 = aujourd'hui (dernier)
    reculer(tid, 3);

    // Jour 1 : reports du cahier ; jours 2 et 3 : 2 000 F chacune
    String jour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g, tid, jour1, awaId, 44000), 201);
    TestDataFactory.attendre(encaisser(g, tid, jour1, binta, 40000), 201);
    TestDataFactory.attendre(encaisser(g, tid, jour1, coumba, 2000), 201);
    remettre(g, tid, jour1);
    for (int jour = 2; jour <= 3; jour++) {
      String id = cycle(g, tid, jour);
      for (String membre : List.of(awaId, binta, coumba)) {
        TestDataFactory.attendre(encaisser(g, tid, id, membre, 2000), 201);
      }
      remettre(g, tid, id);
    }

    // Awa : retrait partiel de 10 000 F daté d'hier (report du cahier) → aucune notification
    LocalDateTime hier = LocalDate.now().minusDays(1).atStartOfDay();
    JsonNode partiel =
        TestDataFactory.attendre(retirer(g, tid, retrait(awaId, "PARTIEL", 10000, hier)), 201)
            .data();
    assertThat(partiel.path("statut").asText()).isEqualTo("REMIS");
    assertThat(partiel.path("soldeRestant").asInt()).isEqualTo(38000);
    assertThat(notificationsRetrait(awa)).isZero();

    // Binta : retrait total → soldée, plus aucune cotisation
    JsonNode total =
        TestDataFactory.attendre(retirer(g, tid, retrait(binta, "TOTAL", null, null)), 201).data();
    assertThat(total.path("montant").asInt()).isEqualTo(44000);
    assertThat(total.path("soldeRestant").asInt()).isZero();
    JsonNode soldeBinta = api.get(tontine(tid) + "/membres/" + binta + "/solde", g.jeton()).data();
    assertThat(soldeBinta.path("solde").asInt()).isZero();
    assertThat(soldeBinta.path("soldeLe").isMissingNode()).isFalse();
    String jour4 = cycle(g, tid, 4);
    Reponse refusee = encaisser(g, tid, jour4, binta, 2000);
    assertThat(refusee.statut()).isEqualTo(400);
    assertThat(refusee.code()).isEqualTo("MEMBRE_SOLDE");
    Reponse encore = retirer(g, tid, retrait(binta, "PARTIEL", 1000, null));
    assertThat(encore.code()).isEqualTo("MEMBRE_SOLDE");

    // Récap total et résumé du membre
    JsonNode recap =
        api.get(tontine(tid) + "/cotisations/recap-total", g.jeton()).data().path("content");
    assertThat(ligne(recap, awaId).path("totalRetire").asInt()).isEqualTo(10000);
    assertThat(ligne(recap, awaId).path("solde").asInt()).isEqualTo(38000);
    assertThat(ligne(recap, binta).path("solde").asInt()).isZero();
    assertThat(ligne(recap, binta).path("soldeLe").isMissingNode()).isFalse();
    JsonNode resume =
        StreamSupport.stream(
                api.get("/v1/me/summary", awa.jeton()).data().path("tontines").spliterator(), false)
            .filter(t -> t.path("tontineId").asText().equals(tid))
            .findFirst()
            .orElseThrow();
    assertThat(resume.path("totalRetire").asInt()).isEqualTo(10000);
    assertThat(resume.path("solde").asInt()).isEqualTo(38000);

    // Dernier jour : Awa et Coumba paient, puis clôture finale naturelle
    TestDataFactory.attendre(encaisser(g, tid, jour4, awaId, 2000), 201);
    TestDataFactory.attendre(encaisser(g, tid, jour4, coumba, 2000), 201);
    remettre(g, tid, jour4);

    JsonNode distribution =
        TestDataFactory.attendre(api.get(tontine(tid) + "/distribution", g.jeton()), 200).data();
    JsonNode lignes = distribution.path("lignes");
    assertThat(ligne(lignes, awaId).path("montantCotise").asInt()).isEqualTo(50000);
    assertThat(ligne(lignes, awaId).path("montantRetire").asInt()).isEqualTo(10000);
    assertThat(ligne(lignes, awaId).path("montantNet").asInt()).isEqualTo(40000);
    assertThat(ligne(lignes, binta).path("montantRetire").asInt()).isEqualTo(44000);
    assertThat(ligne(lignes, binta).path("montantNet").asInt()).isZero();
    assertThat(ligne(lignes, coumba).path("montantNet").asInt()).isEqualTo(8000);
    int encaisse = distribution.path("totalCotise").asInt();
    assertThat(encaisse).isEqualTo(102000);
    assertThat(distribution.path("totalNet").asInt() + distribution.path("totalRetire").asInt())
        .isEqualTo(encaisse);

    // Après la distribution : plus de retrait, plus d'annulation
    assertThat(retirer(g, tid, retrait(awaId, "PARTIEL", 1000, null)).code())
        .isEqualTo("RETRAIT_NON_DISPONIBLE");
    Reponse annulation =
        api.delete(
            tontine(tid) + "/retraits/" + partiel.path("id").asText(),
            g.jeton(),
            Map.of("motif", "Erreur"));
    assertThat(annulation.code()).isEqualTo("RETRAIT_NON_ANNULABLE");
  }

  @Test
  @DisplayName(
      "annulation d'un retrait total : la membre n'est plus soldée et peut cotiser ; notification"
          + " pour un retrait du jour ; liste et export ; droits")
  void annulationEtDroits() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    Utilisateur tiers = donnees.inscrire("Tiers");
    String tid = epargne(g, "Épargne annulation", 10, null);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    String jour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g, tid, jour1, awaId, 6000), 201);
    TestDataFactory.attendre(encaisser(g, tid, jour1, bintaId, 4000), 201);

    JsonNode total =
        TestDataFactory.attendre(retirer(g, tid, retrait(awaId, "TOTAL", null, null)), 201).data();
    assertThat(notificationsRetrait(awa)).isEqualTo(1);
    TestDataFactory.attendre(retirer(g, tid, retrait(bintaId, "PARTIEL", 1000, null)), 201);

    // Un membre ne voit que ses retraits ; le solde d'un autre lui est refusé ; un tiers rien
    JsonNode siens = api.get(tontine(tid) + "/retraits", binta.jeton()).data().path("content");
    assertThat(siens).hasSize(1);
    assertThat(siens.get(0).path("membreId").asText()).isEqualTo(bintaId);
    JsonNode tous = api.get(tontine(tid) + "/retraits", g.jeton()).data().path("content");
    assertThat(tous).hasSize(2);
    assertThat(api.get(tontine(tid) + "/membres/" + awaId + "/solde", binta.jeton()).statut())
        .isEqualTo(403);
    assertThat(api.get(tontine(tid) + "/retraits", tiers.jeton()).statut()).isEqualTo(403);
    assertThat(retirer(binta, tid, retrait(bintaId, "PARTIEL", 500, null)).statut()).isEqualTo(403);

    Reponse sansMotif =
        api.delete(tontine(tid) + "/retraits/" + total.path("id").asText(), g.jeton(), Map.of());
    assertThat(sansMotif.statut()).isEqualTo(400);
    JsonNode annule =
        TestDataFactory.attendre(
                api.delete(
                    tontine(tid) + "/retraits/" + total.path("id").asText(),
                    g.jeton(),
                    Map.of("motif", "Saisi par erreur")),
                200)
            .data();
    assertThat(annule.path("statut").asText()).isEqualTo("ANNULE");
    assertThat(annule.path("soldeRestant").asInt()).isEqualTo(6000);
    JsonNode solde = api.get(tontine(tid) + "/membres/" + awaId + "/solde", awa.jeton()).data();
    assertThat(solde.path("soldeLe").isMissingNode()).isTrue();
    // Plus soldée : la saisie passe le contrôle MEMBRE_SOLDE et bute sur l'unicité du jour (409)
    Reponse jourDejaPaye = encaisser(g, tid, jour1, awaId, 2000);
    assertThat(jourDejaPaye.statut()).isEqualTo(409);

    Reponse export = api.get(tontine(tid) + "/retraits/export", g.jeton());
    assertThat(export.statut()).isEqualTo(200);
    String csv = new String(export.octets(), StandardCharsets.UTF_8);
    assertThat(csv)
        .contains("Date de remise;Membre")
        .contains("ANNULE")
        .contains("Saisi par erreur");
  }

  @Test
  @DisplayName(
      "règle INTERDIT, solde insuffisant, rotative, tontine terminée ; règle modifiable dans les"
          + " réglages")
  void refus() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid = epargne(g, "Épargne règles", 10, null);
    String awa = preInscrire(g, tid, "Awa");
    donnees.activer(g.jeton(), tid);
    TestDataFactory.attendre(encaisser(g, tid, cycle(g, tid, 1), awa, 3000), 201);

    Reponse insuffisant = retirer(g, tid, retrait(awa, "PARTIEL", 3001, null));
    assertThat(insuffisant.statut()).isEqualTo(400);
    assertThat(insuffisant.code()).isEqualTo("RETRAIT_SOLDE_INSUFFISANT");
    assertThat(insuffisant.data().path("solde").decimalValue())
        .isEqualByComparingTo(new BigDecimal("3000"));

    TestDataFactory.attendre(
        api.patch(tontine(tid) + "/reglages", g.jeton(), Map.of("regleRetrait", "INTERDIT")), 200);
    assertThat(retirer(g, tid, retrait(awa, "PARTIEL", 1000, null)).code())
        .isEqualTo("RETRAIT_INTERDIT");

    String rotative = donnees.creerTontine(g.jeton(), "Rotative").path("id").asText();
    assertThat(
            api.patch(tontine(rotative) + "/reglages", g.jeton(), Map.of("regleRetrait", "LIBRE"))
                .code())
        .isEqualTo("RETRAIT_NON_DISPONIBLE");
    assertThat(retirer(g, rotative, retrait(awa, "PARTIEL", 1000, null)).code())
        .isEqualTo("RETRAIT_NON_DISPONIBLE");

    TestDataFactory.attendre(
        api.patch(tontine(tid) + "/reglages", g.jeton(), Map.of("regleRetrait", "LIBRE")), 200);
    TestDataFactory.attendre(
        api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200);
    assertThat(retirer(g, tid, retrait(awa, "PARTIEL", 1000, null)).code())
        .isEqualTo("RETRAIT_NON_DISPONIBLE");
  }

  @Test
  @DisplayName(
      "retrait total refusé tant qu'une cotisation attend sa validation"
          + " (RETRAIT_COTISATIONS_EN_ATTENTE), accepté une fois validée")
  void totalBloqueParCotisationEnAttente() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    String tid = epargne(g, "Épargne en attente", 10, null);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    String cotisationId =
        TestDataFactory.attendre(
                api.post(
                    tontine(tid) + "/cotisations",
                    awa.jeton(),
                    Map.of("cycleId", cycle(g, tid, 1), "montant", 5000)),
                201)
            .data()
            .path("id")
            .asText();

    Reponse bloque = retirer(g, tid, retrait(awaId, "TOTAL", null, null));
    assertThat(bloque.statut()).isEqualTo(400);
    assertThat(bloque.code()).isEqualTo("RETRAIT_COTISATIONS_EN_ATTENTE");
    assertThat(bloque.data().path("cotisationsEnAttente").asInt()).isEqualTo(1);

    TestDataFactory.attendre(
        api.put(tontine(tid) + "/cotisations/" + cotisationId + "/valider", g.jeton(), null), 200);
    JsonNode total =
        TestDataFactory.attendre(retirer(g, tid, retrait(awaId, "TOTAL", null, null)), 201).data();
    assertThat(total.path("montant").asInt()).isEqualTo(5000);
  }

  @Test
  @DisplayName(
      "commission de 10 % au prorata de ce qui est distribué : le membre soldé n'en paie pas")
  void commissionAuProrata() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid =
        epargne(
            g,
            "Épargne commission",
            10,
            List.of(Map.of("type", "POURCENTAGE_JACKPOT", "valeur", 10)));
    String awa = preInscrire(g, tid, "Awa");
    String binta = preInscrire(g, tid, "Binta");
    donnees.activer(g.jeton(), tid);
    String jour1 = cycle(g, tid, 1);
    TestDataFactory.attendre(encaisser(g, tid, jour1, awa, 30000), 201);
    TestDataFactory.attendre(encaisser(g, tid, jour1, binta, 20000), 201);
    TestDataFactory.attendre(retirer(g, tid, retrait(awa, "PARTIEL", 10000, null)), 201);
    TestDataFactory.attendre(retirer(g, tid, retrait(binta, "TOTAL", null, null)), 201);

    JsonNode cloture =
        TestDataFactory.attendre(
                api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200)
            .data();

    JsonNode parts = cloture.path("distributionParMembre");
    // Distribuable : Awa 20 000, Binta 0 → commission 2 000, toute sur Awa
    assertThat(ligne(parts, awa).path("montantCommission").asInt()).isEqualTo(2000);
    assertThat(ligne(parts, awa).path("montantNet").asInt()).isEqualTo(18000);
    assertThat(ligne(parts, binta).path("montantCommission").asInt()).isZero();
    assertThat(ligne(parts, binta).path("montantNet").asInt()).isZero();
    JsonNode dernier = api.get(tontine(tid) + "/cycles", g.jeton()).data().path("content").get(0);
    assertThat(dernier.path("montantJackpot").asInt()).isEqualTo(50000);
    assertThat(dernier.path("montantCommission").asInt()).isEqualTo(2000);
    assertThat(dernier.path("montantNet").asInt()).isEqualTo(18000);
  }

  private boolean aRecu(Utilisateur u, String type) {
    JsonNode notifs = api.get("/v1/notifications?size=50", u.jeton()).data().path("content");
    return StreamSupport.stream(notifs.spliterator(), false)
        .anyMatch(n -> type.equals(n.path("type").asText()));
  }

  @Test
  @DisplayName(
      "lot 2 : le membre demande, une seule demande à la fois, le gestionnaire accepte puis"
          + " remet ; refus avec motif ; le membre retire sa demande")
  void demandeDuMembre() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    String tontineId = epargne(g, "Epargne demandes", 3, null);
    String membreId = donnees.ajouterMembre(g.jeton(), tontineId, awa.phone()).path("id").asText();
    donnees.activer(g.jeton(), tontineId);
    TestDataFactory.attendre(encaisser(g, tontineId, cycle(g, tontineId, 1), membreId, 10000), 201);
    String retraits = tontine(tontineId) + "/retraits";

    JsonNode demande =
        TestDataFactory.attendre(
                api.post(
                    retraits,
                    awa.jeton(),
                    Map.of("nature", "PARTIEL", "montant", 4000, "motif", "Ordonnance")),
                201)
            .data();
    assertThat(demande.path("statut").asText()).isEqualTo("DEMANDE");
    assertThat(demande.path("origine").asText()).isEqualTo("MEMBRE");
    assertThat(aRecu(g, "RETRAIT_DEMANDE")).isTrue();
    Reponse seconde = api.post(retraits, awa.jeton(), Map.of("nature", "PARTIEL", "montant", 1000));
    assertThat(seconde.statut()).isEqualTo(409);
    assertThat(seconde.code()).isEqualTo("RETRAIT_DEJA_EN_COURS");

    JsonNode resume = api.get("/v1/me/summary", awa.jeton()).data().path("tontines").get(0);
    assertThat(resume.path("retraitEnCours").path("statut").asText()).isEqualTo("DEMANDE");
    JsonNode tableau =
        api.get("/v1/admin/my-dashboard", g.jeton()).data().path("tontines").path("content");
    assertThat(tableau.get(0).path("retraitsEnAttente").asInt()).isEqualTo(1);

    String id = demande.path("id").asText();
    TestDataFactory.attendre(api.put(retraits + "/" + id + "/accepter", g.jeton(), null), 200);
    assertThat(aRecu(awa, "RETRAIT_ACCEPTE")).isTrue();
    JsonNode remis =
        TestDataFactory.attendre(
                api.put(
                    retraits + "/" + id + "/remettre",
                    g.jeton(),
                    Map.of("methodePaiement", "WAVE", "referenceTransaction", "TX-1")),
                200)
            .data();
    assertThat(remis.path("statut").asText()).isEqualTo("REMIS");
    assertThat(remis.path("soldeRestant").asInt()).isEqualTo(6000);
    assertThat(aRecu(awa, "RETRAIT_REMIS")).isTrue();

    String refusee =
        TestDataFactory.attendre(api.post(retraits, awa.jeton(), Map.of("nature", "TOTAL")), 201)
            .data()
            .path("id")
            .asText();
    Reponse sansMotif = api.put(retraits + "/" + refusee + "/refuser", g.jeton(), Map.of());
    assertThat(sansMotif.statut()).isEqualTo(400);
    JsonNode refus =
        TestDataFactory.attendre(
                api.put(
                    retraits + "/" + refusee + "/refuser",
                    g.jeton(),
                    Map.of("motif", "Attendez la fin du mois")),
                200)
            .data();
    assertThat(refus.path("statut").asText()).isEqualTo("REFUSE");
    assertThat(aRecu(awa, "RETRAIT_REFUSE")).isTrue();

    String retiree =
        TestDataFactory.attendre(
                api.post(retraits, awa.jeton(), Map.of("nature", "PARTIEL", "montant", 1000)), 201)
            .data()
            .path("id")
            .asText();
    JsonNode annulee =
        TestDataFactory.attendre(api.delete(retraits + "/" + retiree, awa.jeton(), Map.of()), 200)
            .data();
    assertThat(annulee.path("statut").asText()).isEqualTo("ANNULE");
    assertThat(
            api.get(tontine(tontineId) + "/membres/" + membreId + "/solde", awa.jeton())
                .data()
                .path("solde")
                .asInt())
        .isEqualTo(6000);
  }
}
