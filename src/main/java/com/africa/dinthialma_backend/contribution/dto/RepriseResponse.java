package com.africa.dinthialma_backend.contribution.dto;

import com.africa.dinthialma_backend.auth.codeList.AccountStatus;
import com.africa.dinthialma_backend.contribution.codeList.StatutReprise;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(
    description =
        "Reprise d'une tontine déjà commencée (B41). Le gestionnaire voit tous les soldes ; un"
            + " membre ne voit que le sien (et le total).")
@Getter
@Builder(toBuilder = true)
public class RepriseResponse {

  @Schema(description = "État de la reprise")
  private StatutReprise statut;

  @Schema(description = "Date de début du cahier ; null sans reprise", example = "2026-11-01")
  private LocalDate dateDebutReelle;

  @Schema(description = "Date du gel ; null tant que la reprise est ouverte")
  private LocalDateTime figeeLe;

  @Schema(description = "Tour de reprise (n° 0) qui porte les soldes ; null sans reprise")
  private UUID cycleId;

  @Schema(description = "Total des soldes de départ, en FCFA", example = "840000")
  private BigDecimal total;

  @Schema(description = "Nombre de membres ayant un solde de départ", example = "20")
  private int nombreMembres;

  @Schema(description = "Soldes de départ visibles par l'appelant")
  private List<SoldeDepart> soldes;

  @Schema(description = "Solde de départ d'un membre")
  @Getter
  @Builder
  public static class SoldeDepart {

    @Schema(description = "UUID de la cotisation de reprise")
    private UUID cotisationId;

    @Schema(description = "UUID du membership")
    private UUID membreId;

    private String firstName;

    private String lastName;

    @Schema(description = "Téléphone (sans +)")
    private String phone;

    @Schema(description = "Compte du membre (PRE_ENROLLED = sans application)")
    private AccountStatus accountStatus;

    @Schema(description = "Statut du membre dans la tontine")
    private MembreStatut statutMembre;

    @Schema(description = "Solde de départ, en FCFA", example = "42000")
    private BigDecimal montant;

    @Schema(description = "Note libre")
    private String note;
  }
}
