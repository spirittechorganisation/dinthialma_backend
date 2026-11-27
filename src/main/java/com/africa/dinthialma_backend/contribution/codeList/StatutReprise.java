package com.africa.dinthialma_backend.contribution.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** État de la reprise d'une tontine déjà commencée (B41). */
@Schema(
    description =
        "AUCUNE = pas de reprise ; OUVERTE = soldes de départ modifiables ; FIGEE = plus"
            + " modifiables (à la demande du gestionnaire, au premier retrait ou à la distribution)")
public enum StatutReprise {
  AUCUNE,
  OUVERTE,
  FIGEE
}
