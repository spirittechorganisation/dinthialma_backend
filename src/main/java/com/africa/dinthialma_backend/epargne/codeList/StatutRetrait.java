package com.africa.dinthialma_backend.epargne.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Statut d'un retrait anticipé d'épargne (B42). */
@Schema(
    description =
        "DEMANDE (par le membre) → ACCEPTE (facultatif) → REMIS ; ou REFUSE (gestionnaire,"
            + " motif) / ANNULE (demande retirée, ou retrait remis saisi par erreur). Une saisie"
            + " directe du gestionnaire est créée REMIS")
public enum StatutRetrait {
  DEMANDE,
  ACCEPTE,
  REMIS,
  REFUSE,
  ANNULE
}
