package com.africa.dinthialma_backend.auth.dto;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(description = "Corps de la requête de connexion par identifiant + mot de passe")
@Getter
@Setter
@NoArgsConstructor
public class LoginRequest {

  @Schema(
      description = "Téléphone (ex : 221783703310, avec ou sans +) ou adresse email",
      example = "221783703310")
  @NotBlank(message = "L'identifiant (téléphone ou email) est obligatoire")
  private String username;

  @Schema(description = "Mot de passe du compte", example = "MonMDP@2024")
  @NotBlank(message = "Le mot de passe est obligatoire")
  private String password;

  @Schema(
      description =
          "Type de client – détermine la durée de vie de la session PIN : WEB = 1 an, MOBILE = 1"
              + " an",
      example = "WEB",
      allowableValues = {"WEB", "MOBILE"})
  @NotNull(message = "Le type de client (WEB ou MOBILE) est obligatoire")
  private ClientType clientType;

  @Schema(
      description = "Informations sur l'appareil (optionnel) – ex. \"Chrome 124 / Windows 11\"",
      example = "Chrome 124 / Windows 11")
  private String deviceInfo;

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
