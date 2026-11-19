package com.africa.dinthialma_backend.tontine.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Schema(
    description =
        "Corps facultatif de la clôture d'une tontine (B33). Sans corps, la clôture est refusée"
            + " (409 `TONTINE_CLOTURE_A_CONFIRMER`) s'il reste des points en suspens.")
@Getter
@Setter
public class CloturerTontineRequest {

  @Schema(
      description =
          "`true` : clôturer malgré les tours non remis, les retards ou les compléments restants"
              + " (confirmation du gestionnaire après le 409).",
      example = "true",
      nullable = true)
  private Boolean forcer;
}
