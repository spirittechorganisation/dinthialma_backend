package com.africa.dinthialma_backend.contribution.service.interfaces;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.contribution.dto.RepriseRequest;
import com.africa.dinthialma_backend.contribution.dto.RepriseResponse;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import java.util.UUID;

/**
 * Reprise d'une tontine déjà commencée (B41, lot 1) : le gestionnaire reporte le solde de départ de
 * chaque membre, recopié du cahier. Les soldes sont des cotisations VALIDE d'origine REPRISE sur un
 * tour de reprise (n° 0, TERMINE, sans échéance) : ils comptent dans les soldes, récapitulatifs et
 * la distribution finale, sans rappel, ponctualité ni retard. Événementielles seulement.
 */
public interface RepriseService {

  /**
   * Reprise de la tontine : tous les soldes pour le gestionnaire et le SUPER_ADMIN, le sien (et le
   * total) pour un membre ; 403 pour un tiers.
   */
  RepriseResponse lire(String keycloakId, UUID tontineId) throws CustomException;

  /**
   * Enregistre la date du cahier et remplace les soldes de départ (créés, modifiés, retirés, chacun
   * audité), tant que la reprise n'est pas figée. Aucune notification. Créateur ou SUPER_ADMIN.
   */
  RepriseResponse enregistrer(String keycloakId, UUID tontineId, RepriseRequest request)
      throws CustomException;

  /**
   * Fige la reprise : soldes plus modifiables, chaque membre prévenu de son solde de départ (in-app
   * + WhatsApp). Sans effet si déjà figée. Créateur ou SUPER_ADMIN.
   */
  RepriseResponse figer(String keycloakId, UUID tontineId) throws CustomException;

  /**
   * Gel automatique quand l'argent sort (premier retrait, distribution finale) : fige une reprise
   * encore ouverte, sans effet sinon. Les droits sont vérifiés par l'appelant.
   */
  void figerSiOuverte(User caller, Tontine tontine);
}
