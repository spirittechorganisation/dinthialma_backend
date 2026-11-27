package com.africa.dinthialma_backend.tontine.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Ouverture des tours aux cotisations (B33, lot 3) : réglage du gestionnaire, modifiable à tout
 * moment.
 */
@Schema(
    description =
        "A_LA_REMISE = le tour suivant ne s'ouvre qu'à la remise (clôture) du précédent"
            + " (fonctionnement historique, tontines existantes) ; A_LA_DATE = chaque tour s'ouvre"
            + " aussi à sa date de début, remis ou non (défaut des nouvelles tontines). En"
            + " événementielle A_LA_DATE, l'ouverture d'un sous-cycle clôture le précédent ; la"
            + " distribution finale reste manuelle.")
public enum OuvertureTours {
  A_LA_REMISE,
  A_LA_DATE
}
