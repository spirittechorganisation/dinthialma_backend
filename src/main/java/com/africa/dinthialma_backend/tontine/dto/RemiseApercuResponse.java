package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(
    description =
        "Aperçu avant la remise du jackpot d'un tour (B33) : ce qui est prévu, collecté, manquant,"
            + " et le résultat de chaque option de remise. Montants de suivi : Dinthialma ne"
            + " détient aucun fonds.")
@Getter
@Builder
public class RemiseApercuResponse {

  @Schema(description = "UUID du tour")
  private UUID cycleId;

  @Schema(description = "Numéro du tour", example = "3")
  private int numeroCycle;

  @Schema(
      description = "Jackpot brut prévu (montant × membres ACTIFS × échéances)",
      example = "500000")
  private BigDecimal jackpotPrevu;

  @Schema(description = "Somme des cotisations VALIDÉES du tour", example = "490000")
  private BigDecimal collecte;

  @Schema(description = "Jackpot prévu − collecté (0 si tout est payé)", example = "10000")
  private BigDecimal manquant;

  @Schema(description = "Option de remise par défaut de la tontine", example = "DISPONIBLE")
  private ModeRemise modeRemiseParDefaut;

  @Schema(description = "Membres ACTIFS à qui il manque au moins une cotisation validée")
  private List<Retardataire> retardataires;

  @Schema(description = "Résultat de chaque option de remise, applicable ou non")
  private List<Option> options;

  @Schema(description = "Membre en retard sur le tour")
  @Getter
  @Builder
  public static class Retardataire {

    @Schema(description = "UUID du membre (tontine_membres.id)")
    private UUID membreId;

    @Schema(description = "Prénom", example = "Dieynaba")
    private String firstName;

    @Schema(description = "Nom", example = "Fall")
    private String lastName;

    @Schema(description = "Nombre d'échéances du tour sans cotisation validée", example = "2")
    private int echeancesManquees;

    @Schema(description = "Montant correspondant, en FCFA", example = "10000", nullable = true)
    private BigDecimal montant;
  }

  @Schema(description = "Résultat d'une option de remise")
  @Getter
  @Builder
  public static class Option {

    @Schema(description = "Option de remise", example = "AVANCE_COMMISSION")
    private ModeRemise modeRemise;

    @Schema(
        description =
            "false si l'option ne s'applique pas : ATTENDRE avec un manque, AVANCE_COMMISSION"
                + " quand la commission ne couvre pas le manque, CAISSE (pas encore disponible)")
    private boolean possible;

    @Schema(description = "Total remis aux bénéficiaires du tour", example = "400000")
    private BigDecimal montantNet;

    @Schema(description = "Part d'un bénéficiaire (total ÷ gagnants par tour)", example = "400000")
    private BigDecimal montantBeneficiaire;

    @Schema(description = "Commission perçue par le gestionnaire à la remise", example = "90000")
    private BigDecimal commission;

    @Schema(
        description = "Avance de poche du gestionnaire, au-delà de sa commission",
        example = "0")
    private BigDecimal avanceGestionnaire;

    @Schema(
        description = "Complément dû aux bénéficiaires quand les retards seront payés",
        example = "0")
    private BigDecimal resteARecevoir;

    @Schema(
        description =
            "Ce que les régularisations rendront au gestionnaire (avance de poche et commission"
                + " abandonnée)",
        example = "10000")
    private BigDecimal montantARecuperer;
  }
}
