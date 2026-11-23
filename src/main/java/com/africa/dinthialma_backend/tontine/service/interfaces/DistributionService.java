package com.africa.dinthialma_backend.tontine.service.interfaces;

import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.tontine.dto.DistributionFinaleResponse;
import java.util.UUID;

/** Relecture de la distribution finale d'une tontine événementielle (B51). */
public interface DistributionService {

  /**
   * Répartition finale enregistrée (clôture du dernier sous-cycle ou clôture forcée de la tontine).
   * Gestionnaire et SUPER_ADMIN : toutes les lignes ; membre : sa ligne et les totaux.
   *
   * @throws CustomException 404 tontine inconnue, 400 tontine non événementielle, 403 ni
   *     gestionnaire ni membre, 404 {@code DISTRIBUTION_NON_FAITE}
   */
  DistributionFinaleResponse lire(String keycloakId, UUID tontineId) throws CustomException;
}
