package com.africa.dinthialma_backend.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "Résultat de la configuration du PIN (B53)")
@Getter
@Builder
public class PinSetupResponse {

  @Schema(
      description =
          "true si cet appareil est désormais de confiance (mot de passe fourni) : le PIN y suffit"
              + " pendant des mois ; false sinon (PIN utilisable tant que la session dure)",
      example = "true")
  private boolean confiance;
}
