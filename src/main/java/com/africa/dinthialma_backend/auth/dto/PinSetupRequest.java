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
        "Requête de configuration initiale du code PIN à 6 chiffres."
            + " Nécessite un JWT valide (connexion mot de passe préalable).")
@Getter
@Setter
@NoArgsConstructor
public class PinSetupRequest {

  @Schema(description = "Code PIN à 6 chiffres", example = "123456")
  @NotBlank(message = "Le code PIN est obligatoire")
  @Pattern(regexp = "\\d{6}", message = "Le code PIN doit contenir exactement 6 chiffres")
  private String pin;

  @Schema(description = "Confirmation du code PIN (doit être identique à pin)", example = "123456")
  @NotBlank(message = "La confirmation du code PIN est obligatoire")
  @Pattern(regexp = "\\d{6}", message = "La confirmation doit contenir exactement 6 chiffres")
  private String confirmPin;

  @Schema(
      description =
          "Mot de passe du compte (B53, facultatif). Fourni une fois à la création du PIN, il rend"
              + " cet appareil « de confiance » : le PIN suffira ensuite pendant des mois. Exige"
              + " appareilId et clientType. Faux → 401 INVALID_CREDENTIALS (PIN non enregistré).",
      nullable = true)
  private String motDePasse;

  @Schema(
      description = "Identifiant stable de l'appareil (B53), voir /v1/auth/login",
      example = "6f1c2a9e-4b7d-4c1a-9e2f-0a1b2c3d4e5f",
      nullable = true)
  @Pattern(
      regexp = "[A-Za-z0-9._-]{8,100}",
      message = "appareilId : 8 à 100 caractères (lettres, chiffres, . _ -)")
  private String appareilId;

  @Schema(
      description = "Type de client de cet appareil (B53), avec motDePasse",
      allowableValues = {"WEB", "MOBILE"},
      nullable = true)
  private ClientType clientType;

  @Schema(description = "Informations appareil (optionnel)", nullable = true)
  private String deviceInfo;
}
