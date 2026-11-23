package com.africa.dinthialma_backend.epargne.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Statut d'un retrait anticipé d'épargne (B42). */
@Schema(
    description =
        "DEMANDE → ACCEPTE → REMIS, ou REFUSE / ANNULE. Au lot 1, seul REMIS (saisie du"
            + " gestionnaire) et ANNULE (saisie erronée) sont utilisés")
public enum StatutRetrait {
  DEMANDE,
  ACCEPTE,
  REMIS,
  REFUSE,
  ANNULE
}
