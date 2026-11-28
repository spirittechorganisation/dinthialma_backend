package com.africa.dinthialma_backend.epargne.dto;

import com.africa.dinthialma_backend.epargne.codeList.NatureRetrait;
import com.africa.dinthialma_backend.epargne.codeList.OrigineRetrait;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Retrait anticipé d'épargne (B42)")
@Getter
@Builder(toBuilder = true)
public class RetraitEpargneResponse {

  private UUID id;
  private UUID tontineId;

  @Schema(description = "UUID du membership (tontine_membres.id)")
  private UUID membreId;

  private UUID userId;
  private String firstName;
  private String lastName;

  private NatureRetrait nature;

  @Schema(description = "Montant remis (FCFA)", example = "10000")
  private BigDecimal montant;

  @Schema(description = "Frais retenus (FCFA, 0 au lot 1)", example = "0")
  private BigDecimal frais;

  private StatutRetrait statut;
  private OrigineRetrait origine;

  @Schema(example = "CASH")
  private String methodePaiement;

  private String referenceTransaction;
  private String motif;

  @Schema(description = "Date réelle de la remise (heure de Dakar)")
  private LocalDateTime dateRemise;

  @Schema(description = "Motif de l'annulation, si annulé", nullable = true)
  private String motifAnnulation;

  @Schema(description = "Date de l'annulation, si annulé", nullable = true)
  private LocalDateTime dateAnnulation;

  @Schema(
      description = "Acceptation de la demande par le gestionnaire (B42, lot 2)",
      nullable = true)
  private LocalDateTime dateAcceptation;

  @Schema(description = "Motif du refus de la demande (B42, lot 2)", nullable = true)
  private String motifRefus;

  @Schema(description = "Date du refus de la demande (B42, lot 2)", nullable = true)
  private LocalDateTime dateRefus;

  @Schema(
      description =
          "Solde du membre après l'opération (création ou annulation) ; absent dans les listes",
      example = "36000",
      nullable = true)
  private BigDecimal soldeRestant;

  private LocalDateTime createdAt;
}
