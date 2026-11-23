package com.africa.dinthialma_backend.epargne.service.interfaces;

import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.util.FichierExport;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.dto.RetraitRequests;
import com.africa.dinthialma_backend.epargne.dto.SoldeEpargneResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Retraits anticipés d'une épargne événementielle (B42, lot 1 : saisie du gestionnaire). */
public interface RetraitEpargneService {

  /**
   * Enregistre un retrait déjà remis au membre ({@code REMIS}, origine GESTIONNAIRE). Créateur ou
   * SUPER_ADMIN. {@code TOTAL} : montant = solde, le membre devient soldé. Notification {@code
   * RETRAIT_REMIS} sauf si la date de remise date de plus de 24 h.
   *
   * @throws CustomException {@code RETRAIT_NON_DISPONIBLE}, {@code RETRAIT_INTERDIT}, {@code
   *     MEMBRE_SOLDE}, {@code RETRAIT_SOLDE_INSUFFISANT} ({@code data.solde}), 400 date invalide
   */
  RetraitEpargneResponse enregistrer(
      String keycloakId, UUID tontineId, RetraitRequests.Saisir request) throws CustomException;

  /**
   * Retraits de la tontine, filtres {@code membreId} et {@code statuts}. Gestionnaire : tous ;
   * membre : les siens seulement ({@code membreId} ignoré).
   */
  Page<RetraitEpargneResponse> lister(
      String keycloakId,
      UUID tontineId,
      UUID membreId,
      List<StatutRetrait> statuts,
      Pageable pageable)
      throws CustomException;

  /** Solde d'épargne d'un membre : gestionnaire, SUPER_ADMIN ou le membre concerné. */
  SoldeEpargneResponse solde(String keycloakId, UUID tontineId, UUID membreId)
      throws CustomException;

  /**
   * Annule un retrait saisi par erreur ({@code ANNULE}, motif obligatoire), tant que la
   * distribution finale n'est pas faite. Un retrait total annulé rend le membre non soldé.
   *
   * @throws CustomException {@code RETRAIT_NON_ANNULABLE}
   */
  RetraitEpargneResponse annuler(
      String keycloakId, UUID tontineId, UUID retraitId, RetraitRequests.Annuler request)
      throws CustomException;

  /** Export CSV de tous les retraits de la tontine (gestionnaire, SUPER_ADMIN). */
  FichierExport exporter(String keycloakId, UUID tontineId, String format) throws CustomException;
}
