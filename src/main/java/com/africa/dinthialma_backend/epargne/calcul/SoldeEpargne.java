package com.africa.dinthialma_backend.epargne.calcul;

import com.africa.dinthialma_backend.common.util.Montants;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculs purs de l'épargne événementielle avec retraits anticipés (B42), sans accès à la base.
 *
 * <ul>
 *   <li>solde d'un membre = cotisations VALIDÉES − retraits REMIS (montant + frais) ;
 *   <li>part finale : distribuable = cotisé − retiré ; la commission totale (calculée par
 *       l'appelant sur la somme distribuable) est répartie au prorata du distribuable ; net =
 *       distribuable − commission. Un membre soldé a une part à 0.
 * </ul>
 */
public final class SoldeEpargne {

  private SoldeEpargne() {}

  /** Part finale d'un membre. */
  public record Part(
      BigDecimal cotise,
      BigDecimal retire,
      BigDecimal distribuable,
      BigDecimal commission,
      BigDecimal net) {}

  /** Solde disponible : cotisé validé − retiré (montants absents = 0). */
  public static BigDecimal solde(BigDecimal cotiseValide, BigDecimal retire) {
    return zero(cotiseValide).subtract(zero(retire));
  }

  /** Ce qui reste à distribuer à un membre : cotisé − retiré, jamais négatif. */
  public static BigDecimal distribuable(BigDecimal cotise, BigDecimal retire) {
    return solde(cotise, retire).max(BigDecimal.ZERO);
  }

  /** Somme distribuable de tous les membres : base de la commission en pourcentage. */
  public static BigDecimal baseDistribuable(List<BigDecimal> cotises, List<BigDecimal> retires) {
    BigDecimal base = BigDecimal.ZERO;
    for (int i = 0; i < cotises.size(); i++) {
      base = base.add(distribuable(cotises.get(i), retires.get(i)));
    }
    return base;
  }

  /**
   * Parts finales, dans l'ordre des listes (même taille).
   *
   * @param commissionTotale commission de la tontine sur la somme distribuable ; ignorée s'il ne
   *     reste rien à distribuer
   */
  public static List<Part> parts(
      List<BigDecimal> cotises, List<BigDecimal> retires, BigDecimal commissionTotale) {
    List<BigDecimal> distribuables = new ArrayList<>(cotises.size());
    for (int i = 0; i < cotises.size(); i++) {
      distribuables.add(distribuable(cotises.get(i), retires.get(i)));
    }
    BigDecimal base = distribuables.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal commission = base.signum() > 0 ? zero(commissionTotale) : BigDecimal.ZERO;
    List<BigDecimal> commissions =
        commission.signum() == 0
            ? distribuables.stream().map(d -> BigDecimal.ZERO).toList()
            : Montants.repartirAuProrata(commission, distribuables);

    List<Part> parts = new ArrayList<>(cotises.size());
    for (int i = 0; i < cotises.size(); i++) {
      parts.add(
          new Part(
              zero(cotises.get(i)),
              zero(retires.get(i)),
              distribuables.get(i),
              commissions.get(i),
              distribuables.get(i).subtract(commissions.get(i))));
    }
    return parts;
  }

  private static BigDecimal zero(BigDecimal montant) {
    return montant != null ? montant : BigDecimal.ZERO;
  }
}
