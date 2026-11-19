package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Schema(
    description =
        "Points en suspens d'une tontine à clôturer (B33), renvoyés dans `data` du 409"
            + " `TONTINE_CLOTURE_A_CONFIRMER`. Informations de suivi : la clôture reste possible"
            + " avec `forcer: true`.")
@Getter
@Builder
public class ClotureEnSuspensResponse {

  @Schema(
      description =
          "Tours pas encore remis : en cours (EN_COURS) ou pas encore commencés (EN_ATTENTE)."
              + " Événementielle : sous-cycles restants avant la distribution finale.")
  private final List<Tour> toursNonRemis;

  @Schema(description = "Tours remis dont les gagnants restent à désigner (rotative).")
  private final List<Tour> toursSansGagnant;

  @Schema(
      description =
          "Membres actifs à qui il manque une cotisation validée sur une échéance passée"
              + " (rotative).")
  private final List<Retard> retards;

  @Schema(
      description =
          "Tours remis dont les bénéficiaires attendent encore un complément, ou dont l'avance du"
              + " gestionnaire n'est pas remboursée (rotative).")
  private final List<Complement> complements;

  @Schema(description = "Cotisations déclarées encore à valider ou à refuser.", example = "2")
  private final long cotisationsAValider;

  @Getter
  @Builder
  @Schema(name = "ClotureEnSuspensTour")
  public static class Tour {
    private final UUID cycleId;

    @Schema(example = "3")
    private final int numeroCycle;

    private final CycleStatut statut;
    private final LocalDate dateDebut;
    private final LocalDate dateFin;
  }

  @Getter
  @Builder
  @Schema(name = "ClotureEnSuspensRetard")
  public static class Retard {
    private final UUID membreId;
    private final String firstName;
    private final String lastName;

    @Schema(description = "Échéances passées sans cotisation validée.", example = "2")
    private final int echeancesManquees;

    @Schema(
        description = "Montant correspondant ; absent si le montant est libre.",
        example = "10000",
        nullable = true)
    private final BigDecimal montant;
  }

  @Getter
  @Builder
  @Schema(name = "ClotureEnSuspensComplement")
  public static class Complement {
    private final UUID cycleId;

    @Schema(example = "2")
    private final int numeroCycle;

    @Schema(description = "Net encore dû aux bénéficiaires du tour.", example = "8000")
    private final BigDecimal resteARecevoir;

    @Schema(description = "Avance du gestionnaire encore à rembourser.", example = "0")
    private final BigDecimal montantAvance;
  }
}
