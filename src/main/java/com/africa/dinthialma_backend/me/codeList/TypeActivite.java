package com.africa.dinthialma_backend.me.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Type d'une ligne du fil d'activité. */
@Schema(
    description =
        "COTISATION_DECLAREE (en attente), COTISATION_VALIDEE (déclarée par le membre puis"
            + " validée), COTISATION_ENCAISSEE (saisie par le gestionnaire), COTISATION_EN_RETARD,"
            + " COTISATION_REFUSEE, JACKPOT_RECU, DISTRIBUTION_RECUE, REPRISE (solde de départ"
            + " repris du cahier, daté du début du cahier, B41)")
public enum TypeActivite {
  COTISATION_DECLAREE,
  COTISATION_VALIDEE,
  COTISATION_ENCAISSEE,
  COTISATION_EN_RETARD,
  COTISATION_REFUSEE,
  JACKPOT_RECU,
  /** Part reçue à la clôture finale d'une tontine EVENEMENTIELLE (V034). */
  DISTRIBUTION_RECUE,
  /** Solde de départ repris du cahier (B41), daté du début du cahier. */
  REPRISE
}
