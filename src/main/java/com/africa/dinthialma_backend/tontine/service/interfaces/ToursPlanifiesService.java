package com.africa.dinthialma_backend.tontine.service.interfaces;

import java.time.LocalDateTime;

/**
 * Traitement planifié des tours (B33, lot 3), toutes les 5 minutes, heure de Dakar : ouverture des
 * tours arrivés à leur date ({@code A_LA_DATE}), puis remise automatique des tours échus ({@code
 * remiseAutomatique}). Chaque tontine est traitée dans sa propre transaction : un échec n'empêche
 * pas les autres.
 */
public interface ToursPlanifiesService {

  /** Passage déclenché par le planificateur. */
  void planifie();

  /**
   * Un passage du traitement à l'instant {@code maintenant} (heure de Dakar).
   *
   * @return nombre de tours ouverts et de tours remis pendant ce passage
   */
  Bilan traiter(LocalDateTime maintenant);

  /** Résultat d'un passage. */
  record Bilan(int toursOuverts, int toursRemis) {}
}
