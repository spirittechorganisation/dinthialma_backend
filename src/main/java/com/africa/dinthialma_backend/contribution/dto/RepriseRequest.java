package com.africa.dinthialma_backend.contribution.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Schema(
    description =
        "Reprise d'une tontine déjà commencée (B41) : date du cahier et solde de départ de chaque"
            + " membre. Remplace l'ensemble des soldes tant que la reprise n'est pas figée : un"
            + " membre absent de la liste perd son solde de départ.")
@Getter
@Setter
public class RepriseRequest {

  @Schema(
      description =
          "Date de début du cahier, antérieure à la date de début de la tontine (sans limite)",
      example = "2026-11-01")
  @NotNull(message = "dateDebutReelle est obligatoire")
  private LocalDate dateDebutReelle;

  @Schema(description = "Un solde par membre (au plus), liste vide permise")
  @NotNull(message = "soldes est obligatoire")
  @Size(max = 500, message = "500 soldes au plus")
  @Valid
  private List<Solde> soldes = new ArrayList<>();

  @Schema(description = "Solde de départ d'un membre, recopié du cahier")
  @Getter
  @Setter
  public static class Solde {

    @Schema(description = "UUID du membership (tontine_membres.id)")
    @NotNull(message = "membreId est obligatoire")
    private UUID membreId;

    @Schema(description = "Total déjà versé selon le cahier, en francs entiers", example = "42000")
    @NotNull(message = "montant est obligatoire")
    @Positive(message = "Le montant doit être positif")
    private BigDecimal montant;

    @Schema(description = "Note libre (ex. « cahier p. 3 »)", example = "Cahier page 3")
    @Size(max = 500, message = "500 caractères au plus")
    private String note;
  }
}
