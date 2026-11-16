package com.africa.dinthialma_backend.tontine.service.interfaces;

import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.tontine.dto.CloturerTontineRequest;
import com.africa.dinthialma_backend.tontine.dto.TontineResponse;
import java.util.UUID;

/** Clôture d'une tontine par son gestionnaire (B33, lot 4). */
public interface ClotureTontineService {

  /**
   * Clôture la tontine ({@code ACTIVE} ou {@code SUSPENDUE} → {@code TERMINEE}). Créateur ou
   * SUPER_ADMIN.
   *
   * <p>S'il reste des tours non remis, des gagnants à désigner, des retards, des compléments ou des
   * paiements à valider, la clôture est refusée (409 {@code TONTINE_CLOTURE_A_CONFIRMER}, détail
   * dans {@code data}) tant que {@code forcer} n'est pas à {@code true}.
   *
   * <p>Effets : une événementielle dont la distribution finale n'est pas faite la reçoit
   * immédiatement ; les demandes d'adhésion en attente sont refusées et les priorités de jackpot
   * actives annulées ; les membres actifs sont notifiés ({@code TONTINE_CLOTUREE}). Les tours déjà
   * commencés se remettent encore et les retards s'encaissent toujours (suivi), mais plus aucun
   * tour ne s'ouvre.
   *
   * @param request corps facultatif ({@code forcer})
   * @throws CustomException {@code TONTINE_NON_CLOTURABLE} (BROUILLON ou déjà TERMINEE), {@code
   *     TONTINE_CLOTURE_A_CONFIRMER}
   */
  TontineResponse cloturer(String keycloakId, UUID tontineId, CloturerTontineRequest request)
      throws CustomException;
}
