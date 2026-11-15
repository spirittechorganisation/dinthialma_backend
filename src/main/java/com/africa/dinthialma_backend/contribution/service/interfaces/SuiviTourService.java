package com.africa.dinthialma_backend.contribution.service.interfaces;

import com.africa.dinthialma_backend.contribution.dto.EtatTour;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Suivi d'un tour sur l'ensemble de ses échéances (B32, lot 3) : jackpot prévu, « tous payés »,
 * échéance courante. Partagé par les tours, le tableau de bord du gestionnaire, {@code /me} et le
 * QR.
 */
public interface SuiviTourService {

  /** État du tour à l'instant présent (heure de Dakar). */
  EtatTour etat(CycleTontine cycle);

  /** Vrai si le membre a une cotisation active (hors REFUSE) sur l'échéance courante du tour. */
  boolean aPayeEcheanceCourante(CycleTontine cycle, UUID membreId);

  /**
   * Nombre de tours entièrement payés (cotisation VALIDÉE sur chaque échéance) par membership. Les
   * memberships sans tour payé sont absents de la map.
   */
  Map<UUID, Long> toursPayes(List<UUID> membreIds, List<UUID> tontineIds);
}
