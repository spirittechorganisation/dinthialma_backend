package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(
    description =
        "Répartition finale d'une tontine ÉVÉNEMENTIELLE (B51), relue depuis l'historique"
            + " enregistré à la distribution. Le gestionnaire voit toutes les lignes ; un membre ne"
            + " voit que la sienne, avec les totaux de toute la tontine.")
@Getter
@Builder(toBuilder = true)
public class DistributionFinaleResponse {

  @Schema(description = "UUID de la tontine")
  private UUID tontineId;

  @Schema(description = "Dernier sous-cycle, dont la clôture a fait la distribution")
  private UUID cycleId;

  @Schema(description = "Date et heure de la distribution (heure de Dakar)")
  private LocalDateTime dateDistribution;

  @Schema(description = "Nombre de membres distribués (toutes les lignes)", example = "3")
  private int nombreMembres;

  @Schema(description = "Total des cotisations VALIDÉES de tous les membres", example = "92000")
  private BigDecimal totalCotise;

  @Schema(description = "Total des commissions prélevées", example = "0")
  private BigDecimal totalCommission;

  @Schema(description = "Total des retraits anticipés remis avant la distribution", example = "0")
  private BigDecimal totalRetire;

  @Schema(
      description =
          "Total remis aux membres à la distribution (totalNet + totalRetire + totalCommission ="
              + " totalCotise)",
      example = "92000")
  private BigDecimal totalNet;

  @Schema(description = "Lignes visibles par l'appelant (toutes, ou la sienne pour un membre)")
  private List<Ligne> lignes;

  @Schema(description = "Part d'un membre dans la distribution finale")
  @Getter
  @Builder
  public static class Ligne {

    @Schema(description = "UUID du membership (tontine_membres.id)")
    private UUID membreId;

    @Schema(description = "UUID de l'utilisateur")
    private UUID userId;

    @Schema(description = "Prénom", example = "Awa")
    private String firstName;

    @Schema(description = "Nom de famille", example = "Diop")
    private String lastName;

    @Schema(description = "Numéro de téléphone normalisé", example = "221770000001")
    private String phone;

    @Schema(
        description =
            "Statut actuel du membre dans la tontine (un membre SORTI ou retiré reçoit aussi sa"
                + " part, B43)")
    private MembreStatut statutMembre;

    @Schema(description = "Somme de ses cotisations VALIDÉES", example = "46000")
    private BigDecimal montantCotise;

    @Schema(description = "Part de commission prélevée", example = "0")
    private BigDecimal montantCommission;

    @Schema(description = "Retraits anticipés remis avant la distribution (B42)", example = "0")
    private BigDecimal montantRetire;

    @Schema(
        description = "Montant remis à la distribution = cotisé − commission − retiré",
        example = "46000")
    private BigDecimal montantNet;
  }
}
