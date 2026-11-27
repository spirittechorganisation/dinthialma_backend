package com.africa.dinthialma_backend.contribution.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Origine d'une cotisation (B41). */
@Schema(
    description =
        "DECLARATION = déclarée par le membre ; SAISIE = encaissée par le gestionnaire ; REPRISE ="
            + " solde de départ recopié du cahier (tour de reprise n° 0, B41)")
public enum OrigineCotisation {
  DECLARATION,
  SAISIE,
  REPRISE
}
