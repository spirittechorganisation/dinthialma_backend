package com.africa.dinthialma_backend.tontine.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Option de remise du jackpot quand il manque des cotisations (B33, tontines rotatives) : réglage
 * par défaut de la tontine, modifiable à chaque remise.
 */
@Schema(
    description =
        "ATTENDRE = pas de remise tant que le jackpot prévu n'est pas réuni ; DISPONIBLE = remise"
            + " de ce qui est validé, le complément suit à chaque régularisation ;"
            + " AVANCE_COMMISSION = le gestionnaire comble le manque sur sa commission ;"
            + " AVANCE_GESTIONNAIRE = le gestionnaire avance la différence (commission puis sa"
            + " poche) ; CAISSE = caisse commune (indisponible pour l'instant)")
public enum ModeRemise {
  ATTENDRE,
  DISPONIBLE,
  AVANCE_COMMISSION,
  AVANCE_GESTIONNAIRE,
  CAISSE
}
