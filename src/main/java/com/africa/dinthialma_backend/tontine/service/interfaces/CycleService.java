package com.africa.dinthialma_backend.tontine.service.interfaces;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.tontine.dto.BeneficiaireHistoriqueResponse;
import com.africa.dinthialma_backend.tontine.dto.CloturerCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse;
import com.africa.dinthialma_backend.tontine.dto.OpenCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.PropositionTourResponse;
import com.africa.dinthialma_backend.tontine.dto.SelectionnerBeneficiaireRequest;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Service métier pour la gestion des cycles de tontine. */
public interface CycleService {

  /**
   * Liste tous les cycles d'une tontine.
   *
   * <p>Accès : membres + admin + SUPER_ADMIN.
   */
  Page<CycleResponse> listCycles(String keycloakId, UUID tontineId, Pageable pageable)
      throws CustomException;

  /**
   * Récupère un cycle par son identifiant.
   *
   * <p>Accès : membres + admin + SUPER_ADMIN.
   */
  CycleResponse getCycle(String keycloakId, UUID tontineId, UUID cycleId) throws CustomException;

  /**
   * Propose le prochain tour d'une rotative en mode MANUEL (B32) : sa date de fin et ses N
   * échéances selon le rythme, à partir de {@code dateDebut} (défaut : aujourd'hui). Aucun effet en
   * base.
   *
   * <p>Réservé au créateur de la tontine et au SUPER_ADMIN.
   */
  PropositionTourResponse proposerTour(String keycloakId, UUID tontineId, LocalDate dateDebut)
      throws CustomException;

  /**
   * Ouvre un nouveau cycle manuellement (mode {@code MANUEL} uniquement).
   *
   * <p>Réservé au créateur de la tontine et au SUPER_ADMIN.
   */
  CycleResponse openCycle(String keycloakId, UUID tontineId, OpenCycleRequest request)
      throws CustomException;

  /**
   * Clôture un cycle en cours ({@code EN_COURS} → {@code TERMINE}).
   *
   * <p>Calcule le montant jackpot, enregistre la date de remise et met le cycle suivant en {@code
   * EN_COURS} si applicable. Réservé au créateur et au SUPER_ADMIN.
   */
  default CycleResponse closeCycle(String keycloakId, UUID tontineId, UUID cycleId)
      throws CustomException {
    return closeCycle(keycloakId, tontineId, cycleId, null);
  }

  /**
   * Clôture (remise du jackpot) avec une option de remise (B33). {@code request} null ou sans
   * {@code modeRemise} : réglage de la tontine. {@code ATTENDRE} avec un jackpot incomplet → 409
   * {@code REMISE_JACKPOT_INCOMPLETE} ; option inapplicable → {@code MODE_REMISE_INDISPONIBLE}.
   */
  CycleResponse closeCycle(
      String keycloakId, UUID tontineId, UUID cycleId, CloturerCycleRequest request)
      throws CustomException;

  /**
   * Remise du jackpot dès que tous ont payé (B32, {@code declenchementJackpot = TOUS_PAYES}),
   * appelée après chaque validation de cotisation. Rotative ACTIVE, tour EN_COURS et chaque membre
   * actif payé sur chaque échéance : en mode AUTOMATIQUE le tour est clôturé (comme {@link
   * #closeCycle}) ; en mode MANUEL le gestionnaire est notifié ({@code JACKPOT_PRET}).
   *
   * @return le tour clôturé, vide si aucune clôture n'a eu lieu
   */
  Optional<CycleResponse> remettreSiTousPayes(String keycloakId, UUID tontineId, UUID cycleId)
      throws CustomException;

  /**
   * Distribution finale immédiate d'une tontine événementielle clôturée avant son terme (B33) : les
   * sous-cycles pas encore commencés sont retirés logiquement et le sous-cycle en cours est
   * clôturé, ce qui calcule et enregistre la part de chaque membre sur les cotisations validées.
   * Sans effet sur une rotative ou si la distribution finale est déjà faite. Les droits sont
   * vérifiés par l'appelant.
   */
  void distribuerMaintenant(User caller, Tontine tontine);

  /**
   * Liste l'historique des bénéficiaires de jackpot d'une tontine (cycles TERMINÉ avec bénéficiaire
   * désigné), triés par numéro de cycle croissant.
   *
   * <p>Accès : membres + admin + SUPER_ADMIN.
   */
  Page<BeneficiaireHistoriqueResponse> listBeneficiaires(
      String keycloakId, UUID tontineId, Pageable pageable) throws CustomException;

  /**
   * Sélectionne le bénéficiaire du jackpot d'un cycle TERMINE (mode MANUEL).
   *
   * <p>Si {@code request.membreId} est fourni : désignation manuelle. Si null : sélection aléatoire
   * parmi les membres ACTIF n'ayant pas encore reçu de jackpot. Le membre sélectionné est marqué
   * {@code aRecuJackpot=true} et reçoit une notification WhatsApp. Réservé au créateur et au
   * SUPER_ADMIN.
   */
  CycleResponse selectionnerBeneficiaire(
      String keycloakId, UUID tontineId, UUID cycleId, SelectionnerBeneficiaireRequest request)
      throws CustomException;
}
