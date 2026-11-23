package com.africa.dinthialma_backend.epargne.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(
    description =
        "Solde d'épargne d'un membre (B42) = cotisations VALIDÉES − retraits REMIS (montant +"
            + " frais)")
@Getter
@Builder
public class SoldeEpargneResponse {

  @Schema(description = "UUID du membership (tontine_membres.id)")
  private UUID membreId;

  @Schema(description = "Total de ses cotisations VALIDÉES (FCFA)", example = "46000")
  private BigDecimal totalCotiseValide;

  @Schema(description = "Total de ses retraits remis, frais compris (FCFA)", example = "10000")
  private BigDecimal totalRetire;

  @Schema(description = "Solde disponible (FCFA)", example = "36000")
  private BigDecimal solde;

  @Schema(
      description = "Date du retrait total : membre soldé (plus de cotisation) ; null sinon",
      nullable = true)
  private LocalDateTime soldeLe;
}
