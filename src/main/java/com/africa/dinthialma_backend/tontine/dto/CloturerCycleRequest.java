package com.africa.dinthialma_backend.tontine.dto;

import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Schema(
    description =
        "Corps facultatif de la remise du jackpot (clôture d'un tour, B33). Sans corps, l'option"
            + " de remise par défaut de la tontine s'applique.")
@Getter
@Setter
public class CloturerCycleRequest {

  @Schema(
      description =
          "Option de remise pour ce tour ; absente = réglage de la tontine. Voir l'aperçu"
              + " GET /cycles/{cycleId}/remise/apercu pour les montants de chaque option.",
      example = "DISPONIBLE",
      nullable = true)
  private ModeRemise modeRemise;
}
