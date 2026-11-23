package com.africa.dinthialma_backend.auth.dto;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Schema(
    description =
        "Requête de connexion rapide par code PIN (WEB ou MOBILE)."
            + " Pré-requis : PIN configuré + session active existante (connexion mot de passe"
            + " préalable). Verrouillé après 5 tentatives incorrectes (déverrouillage 30 min).")
@Getter
@Setter
@NoArgsConstructor
public class PinLoginRequest {

  @Schema(
      description = "Numéro de téléphone (avec ou sans +) ou email – identifiant du compte",
      example = "221783703310")
  @NotBlank(message = "Le numéro de téléphone est obligatoire")
  private String phone;

  @Schema(description = "Code PIN à 6 chiffres", example = "123456")
  @NotBlank(message = "Le code PIN est obligatoire")
  @Pattern(regexp = "\\d{6}", message = "Le code PIN doit contenir exactement 6 chiffres")
  private String pin;

  @Schema(
      description = "Type de client : WEB (session 24h) ou MOBILE (session 30 jours)",
      example = "MOBILE",
      allowableValues = {"WEB", "MOBILE"})
  @NotNull(message = "Le type de client est obligatoire (WEB ou MOBILE)")
  private ClientType clientType;

  @Schema(
      description =
          "Informations appareil (optionnel) : user-agent navigateur (WEB) ou device_id (MOBILE)",
      example = "iPhone 15 / iOS 17")
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
