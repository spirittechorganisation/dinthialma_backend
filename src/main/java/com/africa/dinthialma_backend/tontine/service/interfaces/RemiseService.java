package com.africa.dinthialma_backend.tontine.service.interfaces;

import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.tontine.dto.RemiseApercuResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import java.util.UUID;

/** Options de remise du jackpot (B33) : aperçu avant remise et suites d'une régularisation. */
public interface RemiseService {

  /**
   * Aperçu avant la remise d'un tour EN_COURS d'une tontine rotative : jackpot prévu, collecté,
   * manquant, retardataires et résultat de chaque option. Sans effet en base.
   *
   * <p>Accès : créateur de la tontine, SUPER_ADMIN.
   */
  RemiseApercuResponse apercu(String keycloakId, UUID tontineId, UUID cycleId)
      throws CustomException;

  /**
   * Après la validation d'une cotisation sur un tour déjà remis (rotative, TERMINE) : recalcule le
   * tour. L'argent rembourse d'abord l'avance du gestionnaire, puis complète les bénéficiaires
   * (part nette), la commission correspondante revenant au gestionnaire. Les gagnants sont crédités
   * du complément et notifiés, le gestionnaire reçoit {@code COMPLEMENT_A_REMETTRE}. Sans effet sur
   * un tour non remis ou une tontine événementielle.
   */
  void regulariser(CycleTontine cycle);
}
