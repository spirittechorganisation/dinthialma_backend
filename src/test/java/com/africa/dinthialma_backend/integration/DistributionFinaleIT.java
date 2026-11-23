package com.africa.dinthialma_backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.support.AbstractIntegrationTest;
import com.africa.dinthialma_backend.support.ApiClient.Reponse;
import com.africa.dinthialma_backend.support.TestDataFactory;
import com.africa.dinthialma_backend.support.TestDataFactory.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Répartition finale relisible (B51) : après la clôture du dernier sous-cycle comme après une
 * clôture forcée, avec les droits du gestionnaire, d'un membre et d'un tiers.
 */
@DisplayName("Distribution finale en lecture (B51)")
class DistributionFinaleIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  private static String tontine(String tontineId) {
    return "/v1/tontines/" + tontineId;
  }

  /**
   * Épargne journalière libre (≥ 2 000 F) de {@code jours} + 1 sous-cycles, à partir d'aujourd'hui.
   */
  private String epargne(Utilisateur g, String nom, int jours) {
    Map<String, Object> corps = new HashMap<>();
    corps.put("tontineType", "EVENEMENTIELLE");
    corps.put("rythmeIntervalle", 1);
    corps.put("rythmeUnite", "JOUR");
    corps.put("montantLibre", true);
    corps.put("montantMinimum", 2000);
    corps.put("dateDebut", LocalDate.now().toString());
    corps.put("dateEcheance", LocalDate.now().plusDays(jours).toString());
    return donnees.creerTontine(g.jeton(), nom, corps).path("id").asText();
  }

  /**
   * Recule toute la tontine de {@code jours} jours en base (l'API refuse une date de début passée)
   * : elle a « commencé » il y a {@code jours} jours.
   */
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

  private String cycle(Utilisateur g, String tontineId, int numero) {
    JsonNode page = api.get(tontine(tontineId) + "/cycles?size=50", g.jeton()).data();
    return StreamSupport.stream(page.path("content").spliterator(), false)
        .filter(c -> c.path("numeroCycle").asInt() == numero)
        .findFirst()
        .orElseThrow()
        .path("id")
        .asText();
  }

  private void encaisser(Utilisateur g, String tontineId, String cycleId, String membreId, int f) {
    TestDataFactory.attendre(
        api.post(
            tontine(tontineId) + "/cotisations/admin",
            g.jeton(),
            Map.of(
                "membreId", membreId, "cycleId", cycleId, "montant", f, "methodePaiement", "CASH")),
        201);
  }

  private JsonNode remettre(Utilisateur g, String tontineId, String cycleId) {
    return TestDataFactory.attendre(
            api.put(tontine(tontineId) + "/cycles/" + cycleId + "/cloturer", g.jeton(), null), 200)
        .data();
  }

  @Test
  @DisplayName(
      "clôture du dernier sous-cycle : répartition relue par le gestionnaire (toutes les lignes),"
          + " par un membre (sa ligne) et refusée à un tiers")
  void apresClotureNormale() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    Utilisateur binta = donnees.inscrire("Binta");
    Utilisateur tiers = donnees.inscrire("Tiers");
    String tid = epargne(g, "Épargne relue", 1);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    String bintaId = donnees.ajouterMembre(g.jeton(), tid, binta.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    reculer(tid, 1);

    Reponse avant = api.get(tontine(tid) + "/distribution", g.jeton());
    assertThat(avant.statut()).isEqualTo(404);
    assertThat(avant.code()).isEqualTo("DISTRIBUTION_NON_FAITE");

    String hier = cycle(g, tid, 1);
    encaisser(g, tid, hier, awaId, 44000);
    encaisser(g, tid, hier, bintaId, 40000);
    remettre(g, tid, hier);
    String aujourdHui = cycle(g, tid, 2);
    encaisser(g, tid, aujourdHui, awaId, 2000);
    encaisser(g, tid, aujourdHui, bintaId, 2000);
    assertThat(remettre(g, tid, aujourdHui).path("distributionParMembre")).hasSize(2);

    JsonNode toutes =
        TestDataFactory.attendre(api.get(tontine(tid) + "/distribution", g.jeton()), 200).data();
    assertThat(toutes.path("cycleId").asText()).isEqualTo(aujourdHui);
    assertThat(toutes.path("nombreMembres").asInt()).isEqualTo(2);
    assertThat(toutes.path("totalCotise").asInt()).isEqualTo(88000);
    assertThat(toutes.path("totalNet").asInt()).isEqualTo(88000);
    assertThat(toutes.path("dateDistribution").isMissingNode()).isFalse();
    assertThat(toutes.path("lignes")).hasSize(2);
    assertThat(toutes.path("lignes").get(0).path("statutMembre").asText()).isEqualTo("ACTIF");

    JsonNode sienne =
        TestDataFactory.attendre(api.get(tontine(tid) + "/distribution", awa.jeton()), 200).data();
    assertThat(sienne.path("lignes")).hasSize(1);
    assertThat(sienne.path("lignes").get(0).path("membreId").asText()).isEqualTo(awaId);
    assertThat(sienne.path("lignes").get(0).path("montantNet").asInt()).isEqualTo(46000);
    assertThat(sienne.path("totalCotise").asInt()).isEqualTo(88000);

    assertThat(api.get(tontine(tid) + "/distribution", tiers.jeton()).statut()).isEqualTo(403);

    // Le dernier sous-cycle relu porte aussi la répartition : toute pour le gestionnaire, la sienne
    // pour un membre
    String chemin = tontine(tid) + "/cycles/" + aujourdHui;
    assertThat(api.get(chemin, g.jeton()).data().path("distributionParMembre")).hasSize(2);
    JsonNode cycleBinta = api.get(chemin, binta.jeton()).data().path("distributionParMembre");
    assertThat(cycleBinta).hasSize(1);
    assertThat(cycleBinta.get(0).path("membreId").asText()).isEqualTo(bintaId);
    // Sous-cycle intermédiaire : champ absent (null non sérialisé)
    assertThat(
            api.get(tontine(tid) + "/cycles/" + hier, g.jeton())
                .data()
                .path("distributionParMembre")
                .isMissingNode())
        .isTrue();
  }

  @Test
  @DisplayName("clôture forcée : répartition dans la réponse de PUT /cloturer, puis relisible")
  void apresClotureForcee() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    Utilisateur awa = donnees.inscrire("Awa");
    String tid = epargne(g, "Épargne arrêtée", 30);
    String awaId = donnees.ajouterMembre(g.jeton(), tid, awa.phone()).path("id").asText();
    donnees.activer(g.jeton(), tid);
    encaisser(g, tid, cycle(g, tid, 1), awaId, 6000);

    JsonNode cloturee =
        TestDataFactory.attendre(
                api.put(tontine(tid) + "/cloturer", g.jeton(), Map.of("forcer", true)), 200)
            .data();
    assertThat(cloturee.path("distributionParMembre")).hasSize(1);
    assertThat(cloturee.path("distributionParMembre").get(0).path("montantNet").asInt())
        .isEqualTo(6000);

    JsonNode relue =
        TestDataFactory.attendre(api.get(tontine(tid) + "/distribution", awa.jeton()), 200).data();
    assertThat(relue.path("lignes").get(0).path("montantNet").asInt()).isEqualTo(6000);
  }

  @Test
  @DisplayName("tontine rotative → 400")
  void rotative() {
    Utilisateur g = donnees.inscrire("Gestionnaire");
    String tid = donnees.creerTontine(g.jeton(), "Rotative").path("id").asText();

    assertThat(api.get(tontine(tid) + "/distribution", g.jeton()).statut()).isEqualTo(400);
  }
}
