package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.adhesion.codeList.StatutDemande;
import com.africa.dinthialma_backend.adhesion.repository.DemandeAdhesionRepository;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ConflictException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.common.util.Fuseau;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.jackpot.codeList.StatutPriorite;
import com.africa.dinthialma_backend.jackpot.repository.PrioriteJackpotRepository;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.calendrier.EcheancesTour;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.ClotureEnSuspensResponse;
import com.africa.dinthialma_backend.tontine.dto.CloturerTontineRequest;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse;
import com.africa.dinthialma_backend.tontine.dto.TontineResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.mapper.TontineMapper;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.ClotureTontineService;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ClotureTontineServiceImpl implements ClotureTontineService {

  private static final String MOTIF_CLOTURE = "Tontine clôturée";

  private final AccessControl accessControl;
  private final TontineRepository tontineRepository;
  private final TontineMapper tontineMapper;
  private final CycleTontineRepository cycleRepository;
  private final CotisationRepository cotisationRepository;
  private final TontineMembreRepository membreRepository;
  private final DemandeAdhesionRepository demandeRepository;
  private final PrioriteJackpotRepository prioriteRepository;
  private final CycleService cycleService;
  private final AuditService auditService;
  private final NotificationService notificationService;

  @Override
  public TontineResponse cloturer(String keycloakId, UUID tontineId, CloturerTontineRequest request)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine =
        tontineRepository
            .findByIdAndDeletedAtIsNull(tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
    if (!accessControl.estSuperAdmin(caller)
        && !tontine.getCreePar().getId().equals(caller.getId())) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }
    TontineStatut ancien = tontine.getStatut();
    if (ancien != TontineStatut.ACTIVE && ancien != TontineStatut.SUSPENDUE) {
      throw new BadRequestException(
          ancien == TontineStatut.TERMINEE
              ? "Cette tontine est déjà clôturée"
              : "Une tontine en brouillon ne se clôture pas : supprimez-la",
          ErrorCode.TONTINE_NON_CLOTURABLE);
    }

    List<TontineMembre> actifs =
        membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, MembreStatut.ACTIF);
    ClotureEnSuspensResponse enSuspens = enSuspens(tontine, actifs);
    boolean forcer = request != null && Boolean.TRUE.equals(request.getForcer());
    if (!forcer && !estVide(enSuspens)) {
      throw new ConflictException(
          "Il reste des tours non remis, des retards, des compléments ou des paiements à valider :"
              + " confirmez la clôture avec forcer: true",
          ErrorCode.TONTINE_CLOTURE_A_CONFIRMER,
          details(enSuspens));
    }

    // Événementielle arrêtée avant son terme : chaque membre reçoit sa part tout de suite
    List<CycleResponse.MembreDistributionInfo> distribution =
        cycleService.distribuerMaintenant(caller, tontine);

    tontine.setStatut(TontineStatut.TERMINEE);
    Tontine saved = tontineRepository.save(tontine);
    auditService.log(
        caller, "tontines", tontineId, "statut", ancien.name(), TontineStatut.TERMINEE.name());
    fermerDemandes(caller, tontineId);
    annulerPriorites(caller, tontineId);
    notifierMembres(caller, saved, actifs);
    log.info(
        "Tontine clôturée – id={} {} → TERMINEE forcer={} par userId={}",
        tontineId,
        ancien,
        forcer,
        caller.getId());

    return tontineMapper
        .toResponse(
            saved,
            (int) membreRepository.countByTontine_IdAndDeletedAtIsNull(tontineId),
            caller.getId(),
            membreRepository
                .findByTontine_IdAndUser_IdAndDeletedAtIsNull(tontineId, caller.getId())
                .orElse(null))
        .toBuilder()
        // B51 : la répartition calculée par la clôture forcée est renvoyée, comme à la clôture
        // du dernier sous-cycle
        .distributionParMembre(distribution == null || distribution.isEmpty() ? null : distribution)
        .build();
  }

  // ─── Points en suspens ───────────────────────────────────────────────────

  private ClotureEnSuspensResponse enSuspens(Tontine tontine, List<TontineMembre> actifs) {
    UUID tontineId = tontine.getId();
    List<CycleTontine> tours =
        cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontineId);
    boolean rotative = tontine.getTontineType() == TontineType.ROTATIVE;
    return ClotureEnSuspensResponse.builder()
        .toursNonRemis(
            tours.stream()
                .filter(t -> t.getStatut() != CycleStatut.TERMINE)
                .map(ClotureTontineServiceImpl::tour)
                .toList())
        .toursSansGagnant(rotative ? toursSansGagnant(tours, actifs) : List.of())
        .retards(rotative ? retards(tontine, tours, actifs) : List.of())
        .complements(rotative ? complements(tours) : List.of())
        .cotisationsAValider(
            cotisationRepository.countByTontine_IdAndStatutAndDeletedAtIsNull(
                    tontineId, CotisationStatut.EN_ATTENTE)
                + cotisationRepository.countByTontine_IdAndStatutAndDeletedAtIsNull(
                    tontineId, CotisationStatut.EN_RETARD))
        .build();
  }

  private static boolean estVide(ClotureEnSuspensResponse enSuspens) {
    return enSuspens.getToursNonRemis().isEmpty()
        && enSuspens.getToursSansGagnant().isEmpty()
        && enSuspens.getRetards().isEmpty()
        && enSuspens.getComplements().isEmpty()
        && enSuspens.getCotisationsAValider() == 0;
  }

  private static Map<String, Object> details(ClotureEnSuspensResponse enSuspens) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("toursNonRemis", enSuspens.getToursNonRemis());
    details.put("toursSansGagnant", enSuspens.getToursSansGagnant());
    details.put("retards", enSuspens.getRetards());
    details.put("complements", enSuspens.getComplements());
    details.put("cotisationsAValider", enSuspens.getCotisationsAValider());
    return details;
  }

  private static ClotureEnSuspensResponse.Tour tour(CycleTontine tour) {
    return ClotureEnSuspensResponse.Tour.builder()
        .cycleId(tour.getId())
        .numeroCycle(tour.getNumeroCycle())
        .statut(tour.getStatut())
        .dateDebut(tour.getDateDebut())
        .dateFin(tour.getDateFin())
        .build();
  }

  /** Tours remis sans gagnant alors que des membres actifs attendent encore leur jackpot. */
  private static List<ClotureEnSuspensResponse.Tour> toursSansGagnant(
      List<CycleTontine> tours, List<TontineMembre> actifs) {
    if (actifs.stream().allMatch(TontineMembre::isARecuJackpot)) {
      return List.of();
    }
    return tours.stream()
        .filter(t -> t.getStatut() == CycleStatut.TERMINE)
        .filter(t -> t.getGagnants().stream().noneMatch(g -> g.getDeletedAt() == null))
        .map(ClotureTontineServiceImpl::tour)
        .toList();
  }

  /**
   * Membres actifs sans cotisation VALIDÉE sur une échéance passée : toutes les échéances d'un tour
   * remis, les échéances échues d'un tour en cours ; celles d'avant l'adhésion ne sont pas dues.
   */
  private List<ClotureEnSuspensResponse.Retard> retards(
      Tontine tontine, List<CycleTontine> tours, List<TontineMembre> actifs) {
    LocalDateTime maintenant = Fuseau.maintenant();
    Map<UUID, Integer> manquees = new HashMap<>();
    for (CycleTontine tour : tours) {
      List<EcheanceCycle> dues =
          tour.getStatut() == CycleStatut.EN_ATTENTE
              ? List.<EcheanceCycle>of()
              : EcheancesTour.actives(tour).stream()
                  .filter(
                      e ->
                          tour.getStatut() == CycleStatut.TERMINE
                              || EcheancesTour.echue(e, maintenant))
                  .toList();
      if (dues.isEmpty()) {
        continue;
      }
      Set<String> validees = new HashSet<>();
      for (Object[] ligne : cotisationRepository.findEcheanceMembreStatutByCycle(tour.getId())) {
        if (ligne[2] == CotisationStatut.VALIDE) {
          validees.add(ligne[0] + "|" + ligne[1]);
        }
      }
      for (EcheanceCycle echeance : dues) {
        for (TontineMembre membre : actifs) {
          if (due(membre, echeance)
              && !validees.contains(echeance.getId() + "|" + membre.getId())) {
            manquees.merge(membre.getId(), 1, Integer::sum);
          }
        }
      }
    }
    BigDecimal parMembre = tontine.getMontantAttenduParMembre();
    return actifs.stream()
        .filter(m -> manquees.containsKey(m.getId()))
        .map(
            m ->
                ClotureEnSuspensResponse.Retard.builder()
                    .membreId(m.getId())
                    .firstName(m.getUser().getFirstName())
                    .lastName(m.getUser().getLastName())
                    .echeancesManquees(manquees.get(m.getId()))
                    .montant(
                        parMembre != null
                            ? parMembre.multiply(BigDecimal.valueOf(manquees.get(m.getId())))
                            : null)
                    .build())
        .toList();
  }

  private static boolean due(TontineMembre membre, EcheanceCycle echeance) {
    return membre.getDateAdhesion() == null
        || !echeance.getEcheanceAt().toLocalDate().isBefore(membre.getDateAdhesion());
  }

  /** Tours remis dont le net prévu n'est pas atteint, ou dont l'avance n'est pas remboursée. */
  private static List<ClotureEnSuspensResponse.Complement> complements(List<CycleTontine> tours) {
    return tours.stream()
        .filter(t -> t.getStatut() == CycleStatut.TERMINE)
        .map(
            t ->
                ClotureEnSuspensResponse.Complement.builder()
                    .cycleId(t.getId())
                    .numeroCycle(t.getNumeroCycle())
                    .resteARecevoir(
                        t.getMontantNetPrevu() != null && t.getMontantNet() != null
                            ? t.getMontantNetPrevu()
                                .subtract(t.getMontantNet())
                                .max(BigDecimal.ZERO)
                            : BigDecimal.ZERO)
                    .montantAvance(
                        t.getMontantAvance() != null ? t.getMontantAvance() : BigDecimal.ZERO)
                    .build())
        .filter(c -> c.getResteARecevoir().signum() > 0 || c.getMontantAvance().signum() > 0)
        .toList();
  }

  // ─── Effets de la clôture ────────────────────────────────────────────────

  /** Demandes d'adhésion encore en attente : refusées d'office, sans notification. */
  private void fermerDemandes(User caller, UUID tontineId) {
    LocalDateTime maintenant = LocalDateTime.now();
    demandeRepository
        .findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, StatutDemande.EN_ATTENTE)
        .forEach(
            demande -> {
              demande.setStatut(StatutDemande.REFUSEE);
              demande.setMotifRefus(MOTIF_CLOTURE);
              demande.setTraiteePar(caller);
              demande.setDateTraitement(maintenant);
              demandeRepository.save(demande);
            });
  }

  /** Priorités de jackpot encore actives : annulées d'office, sans notification. */
  private void annulerPriorites(User caller, UUID tontineId) {
    LocalDateTime maintenant = LocalDateTime.now();
    prioriteRepository
        .findByTontine_IdAndStatutInAndDeletedAtIsNull(
            tontineId, List.of(StatutPriorite.EN_ATTENTE, StatutPriorite.ACCEPTEE))
        .forEach(
            priorite -> {
              priorite.setStatut(StatutPriorite.ANNULEE);
              priorite.setTraiteePar(caller);
              priorite.setDateTraitement(maintenant);
              prioriteRepository.save(priorite);
            });
  }

  /** Membres actifs prévenus de la clôture, sauf celui qui vient de clôturer. */
  private void notifierMembres(User caller, Tontine tontine, List<TontineMembre> actifs) {
    actifs.stream()
        .filter(m -> !m.getUser().getId().equals(caller.getId()))
        .forEach(
            m ->
                notificationService.notify(
                    m.getUser().getId(),
                    NotificationType.TONTINE_CLOTUREE,
                    "Tontine clôturée",
                    "La tontine "
                        + tontine.getNom()
                        + " a été clôturée par son gestionnaire. Votre historique reste"
                        + " consultable.",
                    tontine.getId()));
  }
}
