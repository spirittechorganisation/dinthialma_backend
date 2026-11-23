package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.africa.dinthialma_backend.epargne.calcul.SoldeEpargne;
import com.africa.dinthialma_backend.epargne.calcul.SoldeEpargne.Part;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SoldeEpargne : solde d'un membre et parts finales avec retraits (B42)")
class SoldeEpargneTest {

  private static BigDecimal f(long francs) {
    return BigDecimal.valueOf(francs);
  }

  @Test
  @DisplayName("solde = cotisé validé − retiré ; montants absents comptés à 0")
  void solde() {
    assertThat(SoldeEpargne.solde(f(46000), f(10000))).isEqualByComparingTo("36000");
    assertThat(SoldeEpargne.solde(f(2000), null)).isEqualByComparingTo("2000");
    assertThat(SoldeEpargne.solde(null, null)).isEqualByComparingTo("0");
    assertThat(SoldeEpargne.solde(f(42000), f(42000))).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("sans commission : net = cotisé − retiré, membre soldé à 0")
  void partsSansCommission() {
    List<Part> parts =
        SoldeEpargne.parts(
            List.of(f(50000), f(46000), f(6000)),
            List.of(f(10000), f(46000), f(0)),
            BigDecimal.ZERO);

    assertThat(parts).extracting(Part::net).containsExactly(f(40000), f(0), f(6000));
    assertThat(parts).extracting(Part::commission).containsOnly(BigDecimal.ZERO);
    // Somme des nets + somme des retraits = total encaissé
    BigDecimal nets = parts.stream().map(Part::net).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(nets.add(f(56000))).isEqualByComparingTo("102000");
  }

  @Test
  @DisplayName("commission répartie au prorata de ce qui est réellement distribué")
  void commissionAuProrataDuDistribue() {
    // distribuable : 40 000 et 0 → toute la commission sur le premier
    List<Part> parts =
        SoldeEpargne.parts(List.of(f(50000), f(46000)), List.of(f(10000), f(46000)), f(4000));

    assertThat(parts.get(0).distribuable()).isEqualByComparingTo("40000");
    assertThat(parts.get(0).commission()).isEqualByComparingTo("4000");
    assertThat(parts.get(0).net()).isEqualByComparingTo("36000");
    assertThat(parts.get(1).commission()).isEqualByComparingTo("0");
    assertThat(parts.get(1).net()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("rien à distribuer : aucune commission, même de frais fixes")
  void rienADistribuer() {
    List<Part> parts =
        SoldeEpargne.parts(List.of(f(2000), f(3000)), List.of(f(2000), f(3000)), f(500));

    assertThat(parts).extracting(Part::commission).containsOnly(f(0));
    assertThat(parts).extracting(Part::net).containsOnly(f(0));
  }

  @Test
  @DisplayName("retiré au-delà du cotisé (donnée incohérente) : distribuable ramené à 0")
  void retraitSuperieur() {
    List<Part> parts = SoldeEpargne.parts(List.of(f(2000)), List.of(f(5000)), BigDecimal.ZERO);

    assertThat(parts.get(0).distribuable()).isEqualByComparingTo("0");
    assertThat(parts.get(0).net()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("base de la commission = somme distribuable")
  void base() {
    assertThat(
            SoldeEpargne.baseDistribuable(List.of(f(50000), f(46000)), List.of(f(10000), f(46000))))
        .isEqualByComparingTo("40000");
  }
}
