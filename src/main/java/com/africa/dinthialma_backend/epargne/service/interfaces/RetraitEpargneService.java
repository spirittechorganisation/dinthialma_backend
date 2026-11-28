package com.africa.dinthialma_backend.epargne.service.interfaces;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.util.FichierExport;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.dto.RetraitRequests;
import com.africa.dinthialma_backend.epargne.dto.SoldeEpargneResponse;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
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
   * Annule une demande en cours (le membre lui-même ou le gestionnaire, motif facultatif), ou un
   * retrait remis saisi par erreur (gestionnaire, {@code ANNULE}, motif obligatoire), tant que la
   * distribution finale n'est pas faite. Un retrait total annulé rend le membre non soldé.
   *
   * @throws CustomException {@code RETRAIT_NON_ANNULABLE}
   */
  RetraitEpargneResponse annuler(
      String keycloakId, UUID tontineId, UUID retraitId, RetraitRequests.Annuler request)
      throws CustomException;

  /**
   * Demande de retrait par le membre lui-même (B42, lot 2) : {@code DEMANDE}, origine MEMBRE ;
   * gestionnaire prévenu (in-app + WhatsApp). Une seule demande en cours par membre ({@code
   * RETRAIT_DEJA_EN_COURS}) ; mêmes règles que la saisie (retraits ouverts, règle LIBRE, solde).
   */
  RetraitEpargneResponse demander(
      String keycloakId, UUID tontineId, RetraitRequests.Demander request) throws CustomException;

  /**
   * Accepte une demande ({@code DEMANDE} → {@code ACCEPTE}, facultatif avant la remise) ; membre
   * prévenu. Créateur ou SUPER_ADMIN ; sinon {@code RETRAIT_NOT_PENDING}.
   */
  RetraitEpargneResponse accepter(String keycloakId, UUID tontineId, UUID retraitId)
      throws CustomException;

  /**
   * Remet une demande ({@code DEMANDE} ou {@code ACCEPTE} → {@code REMIS}) : mêmes contrôles que la
   * saisie directe, montant revérifié (total = tout le solde à la remise). Créateur ou SUPER_ADMIN.
   */
  RetraitEpargneResponse remettreDemande(
      String keycloakId, UUID tontineId, UUID retraitId, RetraitRequests.Remettre request)
      throws CustomException;

  /**
   * Refuse une demande ({@code DEMANDE} ou {@code ACCEPTE} → {@code REFUSE}, motif obligatoire) ;
   * membre prévenu avec le motif. Créateur ou SUPER_ADMIN.
   */
  RetraitEpargneResponse refuser(
      String keycloakId, UUID tontineId, UUID retraitId, RetraitRequests.Refuser request)
      throws CustomException;

  /**
   * Distribution finale faite : les demandes encore en cours sont annulées d'office, sans
   * notification. Les droits sont vérifiés par l'appelant.
   */
  void annulerDemandesEnCours(User caller, Tontine tontine);

  /** Export CSV de tous les retraits de la tontine (gestionnaire, SUPER_ADMIN). */
  FichierExport exporter(String keycloakId, UUID tontineId, String format) throws CustomException;
}
