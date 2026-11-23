package com.africa.dinthialma_backend.auth.dto;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(
    description =
        "Requête de déconnexion. Le refresh_token est invalidé côté Keycloak"
            + " et la session PIN correspondante est supprimée en base.")
@Getter
@Setter
@NoArgsConstructor
public class LogoutRequest {

  @Schema(description = "Token de rafraîchissement à invalider")
  @NotBlank(message = "Le refresh token est obligatoire")
  private String refreshToken;

  @Schema(
      description =
          "Type de client concerné (WEB ou MOBILE). Si absent, toutes les sessions sont révoquées.",
      nullable = true,
      allowableValues = {"WEB", "MOBILE"})
  private ClientType clientType;

  @Schema(
      description =
          "Identifiant stable de l'appareil (B53), généré une fois par le front (UUID) et gardé"
              + " dans son stockage (localStorage, stockage sécurisé). Sans lui, aucune session de"
              + " confiance : le PIN ne survit pas à SSO Session Idle (30 min).",
      example = "6f1c2a9e-4b7d-4c1a-9e2f-0a1b2c3d4e5f",
      nullable = true)
  @Pattern(
      regexp = "[A-Za-z0-9._-]{8,100}",
      message = "appareilId : 8 à 100 caractères (lettres, chiffres, . _ -)")
  private String appareilId;
}
