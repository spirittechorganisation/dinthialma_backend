package com.africa.dinthialma_backend.tontine.remise;

import com.africa.dinthialma_backend.common.util.Montants;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import java.math.BigDecimal;
import java.util.List;

/**
 * Calcul de la remise d'un jackpot selon l'option retenue (B33), sans accès à la base. Montants en
 * francs entiers (S02).
 *
 * <p>Ex. jackpot prévu 500 000 F, commission 20 %, collecté 490 000 F (manque 10 000 F) :
 *
 * <ul>
 *   <li>{@code DISPONIBLE} : commission 98 000 F, bénéficiaires 392 000 F, reste à recevoir 8 000 F
 *       ;
 *   <li>{@code AVANCE_COMMISSION} : bénéficiaires 400 000 F (le net prévu), commission perçue 90
 *       000 F, les 10 000 F reviendront au gestionnaire ;
 *   <li>{@code AVANCE_GESTIONNAIRE} : comme ci-dessus, et si le collecté ne couvre pas le net
 *       prévu, le gestionnaire avance la différence de sa poche ;
 *   <li>{@code ATTENDRE} : pas de remise tant qu'il manque quelque chose.
 * </ul>
 */
public final class CalculRemise {

  private CalculRemise() {}

  /**
   * Résultat d'une remise (ou de son recalcul après une régularisation).
   *
   * @param possible l'option est applicable dans cette situation
   * @param montantNet total remis aux bénéficiaires
   * @param commission commission réellement perçue par le gestionnaire
   * @param avanceGestionnaire avance de poche du gestionnaire (au-delà de sa commission)
   * @param resteARecevoir complément encore dû aux bénéficiaires quand tout sera payé
   * @param aRecuperer ce que les régularisations rendront au gestionnaire (avance et commission)
   */
  public record Resultat(
      boolean possible,
      BigDecimal montantNet,
      BigDecimal commission,
      BigDecimal avanceGestionnaire,
      BigDecimal resteARecevoir,
      BigDecimal aRecuperer) {}

  /**
   * Commission due sur un jackpot brut : pourcentage du jackpot + frais fixes (une fois par tour) ;
   * les frais d'adhésion ne touchent pas le jackpot.
   */
  public static BigDecimal commission(BigDecimal brut, List<TontineCommission> commissions) {
    BigDecimal total = BigDecimal.ZERO;
    for (TontineCommission commission : commissions) {
      total =
          total.add(
              switch (commission.getType()) {
                case POURCENTAGE_JACKPOT -> Montants.pourcentage(brut, commission.getValeur());
                case FRAIS_FIXES_PAR_CYCLE -> commission.getValeur();
                case FRAIS_ADHESION -> BigDecimal.ZERO;
              });
    }
    return total;
  }

  /** Net revenant aux bénéficiaires sur un jackpot brut. */
  public static BigDecimal net(BigDecimal brut, List<TontineCommission> commissions) {
    return brut.subtract(commission(brut, commissions));
  }

  /** Manque à la remise : jackpot prévu − collecté, jamais négatif. */
  public static BigDecimal manquant(BigDecimal prevu, BigDecimal collecte) {
    return prevu.subtract(collecte).max(BigDecimal.ZERO);
  }

  /**
   * Remise d'un tour.
   *
   * @param prevu jackpot brut prévu (montant × membres actifs × échéances)
   * @param collecte somme des cotisations validées du tour
   */
  public static Resultat remise(
      ModeRemise mode, BigDecimal prevu, BigDecimal collecte, List<TontineCommission> commissions) {
    BigDecimal manquant = manquant(prevu, collecte);
    BigDecimal netCollecte = net(collecte, commissions);
    // Le net visé ne descend jamais sous le net du collecté (collecte supérieure au prévu)
    BigDecimal netPrevu = net(prevu.max(collecte), commissions).max(netCollecte);
    BigDecimal commissionPrevue = prevu.max(collecte).subtract(netPrevu);
    return switch (mode) {
      case ATTENDRE -> disponible(manquant.signum() == 0, collecte, netCollecte, netPrevu);
      case DISPONIBLE -> disponible(true, collecte, netCollecte, netPrevu);
      case AVANCE_COMMISSION ->
          avance(collecte.compareTo(netPrevu) >= 0, collecte, netPrevu, commissionPrevue);
      case AVANCE_GESTIONNAIRE -> avance(true, collecte, netPrevu, commissionPrevue);
      case CAISSE -> disponible(false, collecte, netCollecte, netPrevu);
    };
  }

  /**
   * État d'un tour déjà remis après une régularisation : les bénéficiaires gardent ce qu'ils ont
   * reçu, et touchent en plus le net du nouveau collecté s'il le dépasse ; le reste revient au
   * gestionnaire (remboursement de son avance, puis commission).
   *
   * @param dejaRemis total déjà remis aux bénéficiaires
   * @param collecte somme des cotisations validées du tour, régularisations comprises
   */
  public static Resultat apresRegularisation(
      BigDecimal dejaRemis,
      BigDecimal netPrevu,
      BigDecimal collecte,
      List<TontineCommission> commissions) {
    BigDecimal net = dejaRemis.max(net(collecte, commissions));
    BigDecimal commission = collecte.subtract(net).max(BigDecimal.ZERO);
    BigDecimal avance = net.subtract(collecte).max(BigDecimal.ZERO);
    BigDecimal reste =
        netPrevu == null ? BigDecimal.ZERO : netPrevu.subtract(net).max(BigDecimal.ZERO);
    return new Resultat(true, net, commission, avance, reste, avance);
  }

  private static Resultat disponible(
      boolean possible, BigDecimal collecte, BigDecimal netCollecte, BigDecimal netPrevu) {
    return new Resultat(
        possible,
        netCollecte,
        collecte.subtract(netCollecte),
        BigDecimal.ZERO,
        netPrevu.subtract(netCollecte).max(BigDecimal.ZERO),
        BigDecimal.ZERO);
  }

  private static Resultat avance(
      boolean possible, BigDecimal collecte, BigDecimal netPrevu, BigDecimal commissionPrevue) {
    BigDecimal commission = collecte.subtract(netPrevu).max(BigDecimal.ZERO);
    BigDecimal avancePoche = netPrevu.subtract(collecte).max(BigDecimal.ZERO);
    return new Resultat(
        possible,
        netPrevu,
        commission,
        avancePoche,
        BigDecimal.ZERO,
        commissionPrevue.subtract(commission).max(BigDecimal.ZERO).add(avancePoche));
  }
}
