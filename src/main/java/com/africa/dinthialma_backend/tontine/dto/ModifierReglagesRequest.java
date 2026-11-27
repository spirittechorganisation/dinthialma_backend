package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.OuvertureTours;
import com.africa.dinthialma_backend.tontine.codeList.RegleRetrait;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Schema(
    description =
        "Réglages de la tontine modifiables à tout moment, y compris en cours (B33). Champ absent"
            + " = inchangé.")
@Getter
@Setter
public class ModifierReglagesRequest {

  @Schema(
      description =
          "Option de remise du jackpot par défaut : ATTENDRE, DISPONIBLE, AVANCE_COMMISSION ou"
              + " AVANCE_GESTIONNAIRE. S'applique aux prochaines remises ; le gestionnaire peut"
              + " encore en choisir une autre à chaque remise.",
      example = "DISPONIBLE",
      nullable = true)
  private ModeRemise modeRemise;

  @Schema(
      description =
          "Règle de retrait anticipé (B42, événementielle uniquement) : INTERDIT ou LIBRE. Une"
              + " rotative → 400 RETRAIT_NON_DISPONIBLE.",
      example = "LIBRE",
      nullable = true)
  private RegleRetrait regleRetrait;

  @Schema(
      description =
          "Ouverture des tours (rotative et événementielle) : A_LA_DATE ou A_LA_REMISE. Passage à"
              + " A_LA_DATE : les tours dont la date est atteinte s'ouvrent au passage suivant (5"
              + " min au plus) ; retour à A_LA_REMISE : les tours déjà ouverts le restent.",
      example = "A_LA_DATE",
      nullable = true)
  private OuvertureTours ouvertureTours;

  @Schema(
      description =
          "Remise automatique du jackpot à l'échéance du tour (rotative seulement, sinon 400"
              + " REMISE_AUTOMATIQUE_NON_DISPONIBLE), avec l'option de remise par défaut.",
      example = "true",
      nullable = true)
  private Boolean remiseAutomatique;
}
