package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.tontine.codeList.CommissionType;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import com.africa.dinthialma_backend.tontine.remise.CalculRemise;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exemple de la spec B33 : 10 membres × 5 000 F × 10 semaines = 500 000 F prévus, commission 20 %,
 * un membre manque 2 semaines → 490 000 F collectés.
 */
@DisplayName("CalculRemise : options de remise du jackpot (B33)")
class CalculRemiseTest {

  private static final BigDecimal PREVU = new BigDecimal("500000");
  private static final BigDecimal COLLECTE = new BigDecimal("490000");

  private static TontineCommission commission(CommissionType type, String valeur) {
    return TontineCommission.builder().type(type).valeur(new BigDecimal(valeur)).build();
  }

  private static final List<TontineCommission> VINGT_POUR_CENT =
      List.of(commission(CommissionType.POURCENTAGE_JACKPOT, "20"));

  @Test
  @DisplayName("DISPONIBLE : 392 000 F au bénéficiaire, commission 98 000 F, reste 8 000 F")
  void disponible() {
    CalculRemise.Resultat r =
        CalculRemise.remise(ModeRemise.DISPONIBLE, PREVU, COLLECTE, VINGT_POUR_CENT);

    assertThat(r.possible()).isTrue();
    assertThat(r.montantNet()).isEqualByComparingTo("392000");
    assertThat(r.commission()).isEqualByComparingTo("98000");
    assertThat(r.avanceGestionnaire()).isEqualByComparingTo("0");
    assertThat(r.resteARecevoir()).isEqualByComparingTo("8000");
    assertThat(r.aRecuperer()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("ATTENDRE : refusée s'il manque quelque chose, identique à DISPONIBLE sinon")
  void attendre() {
    assertThat(
            CalculRemise.remise(ModeRemise.ATTENDRE, PREVU, COLLECTE, VINGT_POUR_CENT).possible())
        .isFalse();

    CalculRemise.Resultat complet =
        CalculRemise.remise(ModeRemise.ATTENDRE, PREVU, PREVU, VINGT_POUR_CENT);
    assertThat(complet.possible()).isTrue();
    assertThat(complet.montantNet()).isEqualByComparingTo("400000");
    assertThat(complet.commission()).isEqualByComparingTo("100000");
    assertThat(complet.resteARecevoir()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName(
      "AVANCE_COMMISSION : 400 000 F au bénéficiaire, commission 90 000 F, 10 000 F à récupérer")
  void avanceCommission() {
    CalculRemise.Resultat r =
        CalculRemise.remise(ModeRemise.AVANCE_COMMISSION, PREVU, COLLECTE, VINGT_POUR_CENT);

    assertThat(r.possible()).isTrue();
    assertThat(r.montantNet()).isEqualByComparingTo("400000");
    assertThat(r.commission()).isEqualByComparingTo("90000");
    assertThat(r.avanceGestionnaire()).isEqualByComparingTo("0");
    assertThat(r.resteARecevoir()).isEqualByComparingTo("0");
    assertThat(r.aRecuperer()).isEqualByComparingTo("10000");
  }

  @Test
  @DisplayName(
      "manque supérieur à la commission : AVANCE_COMMISSION impossible, AVANCE_GESTIONNAIRE"
          + " avance de sa poche")
  void avanceGestionnaire() {
    BigDecimal collecte = new BigDecimal("350000");

    assertThat(
            CalculRemise.remise(ModeRemise.AVANCE_COMMISSION, PREVU, collecte, VINGT_POUR_CENT)
                .possible())
        .isFalse();

    CalculRemise.Resultat r =
        CalculRemise.remise(ModeRemise.AVANCE_GESTIONNAIRE, PREVU, collecte, VINGT_POUR_CENT);
    assertThat(r.possible()).isTrue();
    assertThat(r.montantNet()).isEqualByComparingTo("400000");
    assertThat(r.commission()).isEqualByComparingTo("0");
    assertThat(r.avanceGestionnaire()).isEqualByComparingTo("50000");
    // 50 000 F de poche + 100 000 F de commission abandonnée = le manque de 150 000 F
    assertThat(r.aRecuperer()).isEqualByComparingTo("150000");
  }

  @Test
  @DisplayName("CAISSE : jamais possible tant que la caisse commune n'existe pas")
  void caisse() {
    assertThat(CalculRemise.remise(ModeRemise.CAISSE, PREVU, PREVU, VINGT_POUR_CENT).possible())
        .isFalse();
  }

  @Test
  @DisplayName(
      "frais fixes une fois par tour, frais d'adhésion ignorés ; collecté au-delà du prévu")
  void commissionsEtSurplus() {
    List<TontineCommission> commissions =
        List.of(
            commission(CommissionType.POURCENTAGE_JACKPOT, "10"),
            commission(CommissionType.FRAIS_FIXES_PAR_CYCLE, "2000"),
            commission(CommissionType.FRAIS_ADHESION, "5000"));

    assertThat(CalculRemise.commission(new BigDecimal("100000"), commissions))
        .isEqualByComparingTo("12000");
    assertThat(CalculRemise.net(new BigDecimal("100000"), commissions))
        .isEqualByComparingTo("88000");
    assertThat(CalculRemise.manquant(new BigDecimal("100"), new BigDecimal("150")))
        .isEqualByComparingTo("0");

    // Un membre suspendu a payé : collecté > prévu, rien ne manque, tout le collecté est remis
    CalculRemise.Resultat surplus =
        CalculRemise.remise(
            ModeRemise.AVANCE_COMMISSION,
            new BigDecimal("100000"),
            new BigDecimal("110000"),
            commissions);
    assertThat(surplus.possible()).isTrue();
    assertThat(surplus.montantNet()).isEqualByComparingTo("97000");
    assertThat(surplus.commission()).isEqualByComparingTo("13000");
    assertThat(surplus.aRecuperer()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName(
      "régularisation après DISPONIBLE : complément de 8 000 F au bénéficiaire, commission"
          + " 100 000 F")
  void regularisationDisponible() {
    CalculRemise.Resultat r =
        CalculRemise.apresRegularisation(
            new BigDecimal("392000"), new BigDecimal("400000"), PREVU, VINGT_POUR_CENT);

    assertThat(r.montantNet()).isEqualByComparingTo("400000");
    assertThat(r.commission()).isEqualByComparingTo("100000");
    assertThat(r.avanceGestionnaire()).isEqualByComparingTo("0");
    assertThat(r.resteARecevoir()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName(
      "régularisation après une avance : le bénéficiaire ne reçoit rien de plus, l'argent rembourse"
          + " l'avance puis la commission")
  void regularisationAvance() {
    // Avance de poche de 50 000 F (350 000 collectés, 400 000 remis) ; 30 000 F régularisés
    CalculRemise.Resultat partiel =
        CalculRemise.apresRegularisation(
            new BigDecimal("400000"),
            new BigDecimal("400000"),
            new BigDecimal("380000"),
            VINGT_POUR_CENT);
    assertThat(partiel.montantNet()).isEqualByComparingTo("400000");
    assertThat(partiel.commission()).isEqualByComparingTo("0");
    assertThat(partiel.avanceGestionnaire()).isEqualByComparingTo("20000");

    // Tout est régularisé : avance remboursée, commission complète
    CalculRemise.Resultat complet =
        CalculRemise.apresRegularisation(
            new BigDecimal("400000"), new BigDecimal("400000"), PREVU, VINGT_POUR_CENT);
    assertThat(complet.avanceGestionnaire()).isEqualByComparingTo("0");
    assertThat(complet.commission()).isEqualByComparingTo("100000");
  }

  @Test
  @DisplayName("tour remis avant B33 (net prévu inconnu) : complément calculé, reste à recevoir 0")
  void regularisationTourAncien() {
    CalculRemise.Resultat r =
        CalculRemise.apresRegularisation(
            new BigDecimal("1800"), null, new BigDecimal("3000"), VINGT_POUR_CENT);

    assertThat(r.montantNet()).isEqualByComparingTo("2400");
    assertThat(r.commission()).isEqualByComparingTo("600");
    assertThat(r.resteARecevoir()).isEqualByComparingTo("0");
  }
}
