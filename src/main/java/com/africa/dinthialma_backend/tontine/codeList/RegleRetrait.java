package com.africa.dinthialma_backend.tontine.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Règle de retrait anticipé d'une tontine événementielle (B42). */
@Schema(
    description =
        "INTERDIT = aucun retrait avant la distribution finale (tontines existantes et rotatives) ;"
            + " LIBRE = le gestionnaire peut enregistrer des retraits (nouvelle événementielle)")
public enum RegleRetrait {
  INTERDIT,
  LIBRE
}
