package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
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
}
