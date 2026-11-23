package com.africa.dinthialma_backend.epargne.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Qui est à l'origine d'un retrait (B42). */
@Schema(
    description =
        "MEMBRE = demandé par le membre (lot 2) ; GESTIONNAIRE = saisi par le gestionnaire, argent"
            + " déjà remis")
public enum OrigineRetrait {
  MEMBRE,
  GESTIONNAIRE
}
