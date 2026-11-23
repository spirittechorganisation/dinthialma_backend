package com.africa.dinthialma_backend.epargne.dto;

import com.africa.dinthialma_backend.epargne.codeList.NatureRetrait;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Corps des requêtes de retrait anticipé (B42). */
public final class RetraitRequests {

  private RetraitRequests() {}

  @Schema(description = "Retrait déjà remis au membre, saisi par le gestionnaire (B42, lot 1)")
  @Getter
  @Setter
  public static class Saisir {

    @Schema(description = "UUID du membership (tontine_membres.id)")
    @NotNull(message = "membreId est obligatoire")
    private UUID membreId;

    @Schema(description = "PARTIEL (montant obligatoire) ou TOTAL (tout le solde)")
    @NotNull(message = "nature est obligatoire")
    private NatureRetrait nature;

    @Schema(
        description =
            "Montant remis, en francs entiers. Obligatoire en PARTIEL (≤ solde) ; en TOTAL,"
                + " facultatif et, s'il est fourni, égal au solde",
        example = "10000",
        nullable = true)
    @Positive(message = "Le montant doit être positif")
    private BigDecimal montant;

    @Schema(description = "Moyen de remise (code list METHODE_PAIEMENT)", example = "CASH")
    @NotBlank(message = "methodePaiement est obligatoire")
    @Size(max = 50, message = "methodePaiement trop long (50 caractères max)")
    private String methodePaiement;

    @Schema(description = "Référence de la transaction (Wave, OM…)", nullable = true)
    @Size(max = 100, message = "Référence trop longue (100 caractères max)")
    private String referenceTransaction;

    @Schema(description = "Motif ou note (optionnel)", example = "Frais de scolarité")
    @Size(max = 500, message = "Motif trop long (500 caractères max)")
    private String motif;

    @Schema(
        description =
            "Date réelle de la remise (heure de Dakar), pour reporter un retrait noté dans le"
                + " cahier : au plus maintenant, au plus tôt le jour de début de la tontine."
                + " Défaut : maintenant. Antérieure de plus de 24 h : aucune notification",
        example = "2026-11-28T17:30:00",
        nullable = true)
    private LocalDateTime dateRemise;
  }

  @Schema(description = "Annulation d'un retrait saisi par erreur")
  @Getter
  @Setter
  public static class Annuler {

    @Schema(description = "Motif de l'annulation (obligatoire)", example = "Saisi deux fois")
    @NotBlank(message = "motif est obligatoire")
    @Size(max = 500, message = "Motif trop long (500 caractères max)")
    private String motif;
  }
}
