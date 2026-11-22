package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ConflictException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.util.Fuseau;
import com.africa.dinthialma_backend.common.util.Montants;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.codeList.Ponctualite;
import com.africa.dinthialma_backend.contribution.dto.EtatTour;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.interfaces.SuiviTourService;
import com.africa.dinthialma_backend.jackpot.service.interfaces.PrioriteJackpotService;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.dto.NotificationLiens;
import com.africa.dinthialma_backend.notification.service.SchedulerService;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.calendrier.CalendrierTours;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.DeclenchementJackpot;
import com.africa.dinthialma_backend.tontine.codeList.ModeCycle;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.OrdreBeneficiaire;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.BeneficiaireHistoriqueResponse;
import com.africa.dinthialma_backend.tontine.dto.CloturerCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse.MembreDistributionInfo;
import com.africa.dinthialma_backend.tontine.dto.OpenCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.PropositionTourResponse;
import com.africa.dinthialma_backend.tontine.dto.SelectionnerBeneficiaireRequest;
import com.africa.dinthialma_backend.tontine.entity.CycleGagnant;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import com.africa.dinthialma_backend.tontine.mapper.CycleMapper;
import com.africa.dinthialma_backend.tontine.remise.CalculRemise;
import com.africa.dinthialma_backend.tontine.repository.CycleGagnantRepository;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineCommissionRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CycleServiceImpl implements CycleService {

  private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final CycleTontineRepository cycleRepository;
  private final CycleMapper cycleMapper;
  private final CycleGagnantRepository cycleGagnantRepository;
  private final TontineRepository tontineRepository;
  private final TontineMembreRepository membreRepository;
  private final CotisationRepository cotisationRepository;
  private final TontineCommissionRepository commissionRepository;
  private final UserRepository userRepository;
  private final UserRoleAssignmentRepository roleAssignmentRepository;
  private final AuditService auditService;
  private final SchedulerService schedulerService;
  private final WhatsappService whatsappService;
  private final NotificationService notificationService;
  private final DistributionEvenementielleRepository distributionRepository;
  private final PrioriteJackpotService prioriteService;
  private final SuiviTourService suiviTour;

  // ─── Lecture ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Page<CycleResponse> listCycles(String keycloakId, UUID tontineId, Pageable pageable)
      throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertCanAccess(caller, tontine);

    return cycleRepository
        .findByTontine_IdAndDeletedAtIsNull(tontineId, pageable)
        .map(this::reponse);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<BeneficiaireHistoriqueResponse> listBeneficiaires(
      String keycloakId, UUID tontineId, Pageable pageable) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertCanAccess(caller, tontine);

    return cycleRepository
        .findTerminesAvecGagnants(tontineId, CycleStatut.TERMINE, pageable)
        .map(cycleMapper::toHistorique);
  }

  @Override
  @Transactional(readOnly = true)
  public CycleResponse getCycle(String keycloakId, UUID tontineId, UUID cycleId)
      throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertCanAccess(caller, tontine);

    CycleTontine cycle = findCycleById(tontineId, cycleId);
    return reponse(cycle);
  }

  // ─── Ouverture manuelle ──────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public PropositionTourResponse proposerTour(
      String keycloakId, UUID tontineId, LocalDate dateDebut) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertIsCreatorOrSuperAdmin(caller, tontine);
    if (tontine.getTontineType() != TontineType.ROTATIVE
        || tontine.getModeCycle() != ModeCycle.MANUEL) {
      throw new BadRequestException(
          "La proposition de tour concerne les tontines rotatives en mode MANUEL");
    }
    LocalDate debut = dateDebut != null ? dateDebut : LocalDate.now();
    List<CalendrierTours.PeriodeTour> echeances =
        CalendrierTours.echeancesDuTour(
            tontine.getRythme(),
            tontine.getCotisationsParTourEffectif(),
            debut,
            null,
            cycleRepository
                .findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(tontineId)
                .map(CycleTontine::getDateFin)
                .orElse(null));
    return PropositionTourResponse.builder()
        .dateDebut(debut)
        .dateFin(echeances.get(echeances.size() - 1).fin())
        .nombreEcheances(echeances.size())
        .echeances(echeances.stream().map(e -> Fuseau.versOffset(e.echeanceAt())).toList())
        .build();
  }

  @Override
  public CycleResponse openCycle(String keycloakId, UUID tontineId, OpenCycleRequest request)
      throws CustomException {

    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    if (tontine.getTontineType() == TontineType.EVENEMENTIELLE) {
      throw new BadRequestException(
          "Une tontine événementielle gère ses cycles automatiquement – l'ouverture manuelle n'est pas disponible");
    }
    if (tontine.getModeCycle() != ModeCycle.MANUEL) {
      throw new BadRequestException("L'ouverture manuelle n'est possible qu'en mode MANUEL");
    }
    if (tontine.getStatut() != TontineStatut.ACTIVE) {
      throw new BadRequestException("La tontine doit être ACTIVE pour ouvrir un cycle");
    }
    if (request.getDateFin() != null && request.getDateFin().isBefore(request.getDateDebut())) {
      throw new BadRequestException("La date de fin doit être postérieure à la date de début");
    }

    if (cycleRepository
        .findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, CycleStatut.EN_COURS)
        .isPresent()) {
      throw new BadRequestException(
          "Un cycle est déjà en cours. Clôturez-le avant d'en ouvrir un nouveau",
          ErrorCode.CYCLE_ALREADY_IN_PROGRESS);
    }

    Optional<CycleTontine> precedent =
        cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(tontineId);
    int nextNum = precedent.map(c -> c.getNumeroCycle() + 1).orElse(1);

    // Sans date de fin : les N échéances du rythme (B26/B32) ; date choisie : les échéances qui
    // tombent avant, la dernière ramenée sur cette date
    CycleTontine cycle =
        CalendrierTours.tour(
            tontine,
            nextNum,
            CalendrierTours.echeancesDuTour(
                tontine.getRythme(),
                tontine.getCotisationsParTourEffectif(),
                request.getDateDebut(),
                request.getDateFin(),
                precedent.map(CycleTontine::getDateFin).orElse(null)),
            CycleStatut.EN_COURS);

    CycleTontine saved = cycleRepository.save(cycle);
    auditService.logCreate(caller, "cycles_tontine", saved.getId());
    log.info(
        "Cycle manuel ouvert – tontineId={} cycleId={} num={}", tontineId, saved.getId(), nextNum);

    // Notification in-app à tous les membres actifs
    membreRepository
        .findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, MembreStatut.ACTIF)
        .forEach(
            m ->
                notificationService.notify(
                    m.getUser().getId(),
                    NotificationType.CYCLE_OUVERT,
                    "Cycle #" + nextNum + " ouvert",
                    "Le cycle #"
                        + nextNum
                        + " de la tontine "
                        + tontine.getNom()
                        + " est ouvert. Vous pouvez enregistrer votre cotisation.",
                    tontineId,
                    NotificationLiens.cycle(saved.getId(), m.getId())));

    return reponse(saved);
  }

  // ─── Clôture ─────────────────────────────────────────────────────────────

  @Override
  public CycleResponse closeCycle(
      String keycloakId, UUID tontineId, UUID cycleId, CloturerCycleRequest request)
      throws CustomException {

    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    CycleTontine cycle = findCycleById(tontineId, cycleId);

    if (cycle.getStatut() != CycleStatut.EN_COURS) {
      throw new BadRequestException("Seul un cycle EN_COURS peut être clôturé");
    }
    verifierDateCloture(tontine, cycle);

    CycleResponse reponse;
    if (tontine.getTontineType() == TontineType.EVENEMENTIELLE) {
      reponse = closeCycleEvenementielle(caller, tontine, tontineId, cycle);
    } else {
      ModeRemise mode =
          request != null && request.getModeRemise() != null
              ? request.getModeRemise()
              : tontine.getModeRemiseEffectif();
      reponse = closeCycleRotative(caller, tontine, tontineId, cycle, mode);
    }
    suggererCloture(tontine);
    return reponse;
  }

  /**
   * Garde-fou de date (B45, heure de Dakar).
   *
   * <ul>
   *   <li>tour pas encore commencé : refusé ({@code TOUR_NON_COMMENCE}), sauf en rotative quand
   *       tous les membres actifs ont déjà payé toutes les échéances du tour (remise anticipée,
   *       B32) ;
   *   <li>dernier sous-cycle d'une événementielle : refusé avant son dernier jour ({@code
   *       CLOTURE_FINALE_PREMATUREE}), la distribution finale étant irréversible ; la fin anticipée
   *       passe par la clôture de la tontine (confirmation {@code forcer}).
   * </ul>
   *
   * Une rotative commencée se clôture toujours avant son échéance (remise anticipée, options B33).
   */
  private void verifierDateCloture(Tontine tontine, CycleTontine cycle) throws CustomException {
    LocalDate aujourdHui = Fuseau.maintenant().toLocalDate();
    boolean commence = !cycle.getDateDebut().isAfter(aujourdHui);
    if (tontine.getTontineType() == TontineType.ROTATIVE) {
      if (!commence && !suiviTour.etat(cycle).tousPayes()) {
        throw new BadRequestException(
            "Ce tour commence le "
                + JOUR.format(cycle.getDateDebut())
                + " : il ne peut pas être clôturé avant, sauf si tous les membres ont déjà payé",
            ErrorCode.TOUR_NON_COMMENCE);
      }
      return;
    }
    if (!commence) {
      throw new BadRequestException(
          "Ce sous-cycle commence le "
              + JOUR.format(cycle.getDateDebut())
              + " : il ne peut pas être clôturé avant",
          ErrorCode.TOUR_NON_COMMENCE);
    }
    boolean dernier =
        cycleRepository
            .findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId())
            .stream()
            .noneMatch(c -> c.getStatut() == CycleStatut.EN_ATTENTE);
    if (dernier && cycle.getDateFin() != null && cycle.getDateFin().isAfter(aujourdHui)) {
      throw new BadRequestException(
          "C'est le dernier sous-cycle : sa clôture fait la distribution finale, qui ne peut pas"
              + " être faite avant le "
              + JOUR.format(cycle.getDateFin())
              + ". Pour arrêter la tontine plus tôt, utilisez « Clôturer la tontine » (une"
              + " confirmation vous sera demandée).",
          ErrorCode.CLOTURE_FINALE_PREMATUREE);
    }
  }

  // ─── Clôture de la tontine (B33) ─────────────────────────────────────────

  @Override
  public void distribuerMaintenant(User caller, Tontine tontine) {
    if (tontine.getTontineType() != TontineType.EVENEMENTIELLE) {
      return;
    }
    UUID tontineId = tontine.getId();
    List<CycleTontine> tours =
        cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontineId);
    LocalDateTime maintenant = LocalDateTime.now();
    tours.stream()
        .filter(tour -> tour.getStatut() == CycleStatut.EN_ATTENTE)
        .forEach(
            tour -> {
              tour.setDeletedAt(maintenant);
              tour.getEcheances().forEach(e -> e.setDeletedAt(maintenant));
              cycleRepository.save(tour);
            });
    // Plus aucun sous-cycle à venir : la clôture du sous-cycle en cours fait la distribution finale
    tours.stream()
        .filter(tour -> tour.getStatut() == CycleStatut.EN_COURS)
        .findFirst()
        .ifPresent(enCours -> closeCycleEvenementielle(caller, tontine, tontineId, enCours));
  }

  /**
   * Suggère la clôture de la tontine au gestionnaire quand plus rien ne reste à remettre. L'app ne
   * clôture jamais d'elle-même.
   */
  private void suggererCloture(Tontine tontine) {
    if (tontine.getStatut() == TontineStatut.TERMINEE || resteARemettre(tontine)) {
      return;
    }
    notificationService.notify(
        tontine.getCreePar().getId(),
        NotificationType.TONTINE_PRETE_A_CLOTURER,
        "Tontine prête à être clôturée",
        (tontine.getTontineType() == TontineType.EVENEMENTIELLE
                ? "La distribution finale de "
                : "Le dernier tour de ")
            + tontine.getNom()
            + (tontine.getTontineType() == TontineType.EVENEMENTIELLE ? " est faite" : " est remis")
            + " : vous pouvez clôturer la tontine.",
        tontine.getId());
  }

  /**
   * Vrai tant qu'un tour reste à remettre : tour en cours ou à venir ; en rotative, des membres
   * attendent encore leur jackpot (tour à ouvrir en mode MANUEL, ou gagnants à désigner).
   */
  private boolean resteARemettre(Tontine tontine) {
    UUID tontineId = tontine.getId();
    boolean tourRestant =
        cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontineId).stream()
            .anyMatch(c -> c.getStatut() != CycleStatut.TERMINE);
    if (tourRestant || tontine.getTontineType() != TontineType.ROTATIVE) {
      return tourRestant;
    }
    return !eligibles(tontineId).isEmpty()
        && (tontine.getModeCycle() == ModeCycle.MANUEL
            || cycleRepository.existsTermineSansGagnant(tontineId));
  }

  // ─── Remise TOUS_PAYES (B32) ─────────────────────────────────────────────

  @Override
  public Optional<CycleResponse> remettreSiTousPayes(
      String keycloakId, UUID tontineId, UUID cycleId) throws CustomException {
    Tontine tontine = findTontineById(tontineId);
    if (tontine.getTontineType() != TontineType.ROTATIVE
        || tontine.getDeclenchementJackpot() != DeclenchementJackpot.TOUS_PAYES
        || tontine.getStatut() != TontineStatut.ACTIVE) {
      return Optional.empty();
    }
    CycleTontine cycle = findCycleById(tontineId, cycleId);
    if (cycle.getStatut() != CycleStatut.EN_COURS || !suiviTour.etat(cycle).tousPayes()) {
      return Optional.empty();
    }
    if (tontine.getModeCycle() == ModeCycle.AUTOMATIQUE) {
      log.info(
          "Remise automatique (TOUS_PAYES) – tontineId={} cycleId={} num={}",
          tontineId,
          cycleId,
          cycle.getNumeroCycle());
      return Optional.of(closeCycle(keycloakId, tontineId, cycleId));
    }
    // Mode MANUEL : le gestionnaire garde la main sur la remise, il est seulement prévenu
    notificationService.notify(
        tontine.getCreePar().getId(),
        NotificationType.JACKPOT_PRET,
        "Jackpot prêt à être remis",
        "Tous les membres ont payé le tour #"
            + cycle.getNumeroCycle()
            + " · "
            + tontine.getNom()
            + ". Vous pouvez remettre le jackpot.",
        tontineId,
        NotificationLiens.cycle(cycleId, null));
    return Optional.empty();
  }

  // ─── Clôture ROTATIVE ────────────────────────────────────────────────────

  private CycleResponse closeCycleRotative(
      User caller, Tontine tontine, UUID tontineId, CycleTontine cycle, ModeRemise mode)
      throws CustomException {

    UUID cycleId = cycle.getId();

    BigDecimal jackpot =
        cotisationRepository
            .findByCycle_IdAndStatutAndDeletedAtIsNull(cycleId, CotisationStatut.VALIDE)
            .stream()
            .map(c -> c.getMontant() != null ? c.getMontant() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    List<TontineCommission> commissions =
        commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontineId);

    // Option de remise (B33) : DISPONIBLE = ce qui est validé (fonctionnement historique)
    BigDecimal prevu = suiviTour.etat(cycle).montantJackpotPrevu();
    if (prevu == null) {
      prevu = jackpot;
    }
    CalculRemise.Resultat remise = CalculRemise.remise(mode, prevu, jackpot, commissions);
    if (!remise.possible()) {
      throw remiseRefusee(mode, prevu, jackpot);
    }
    BigDecimal totalCommission = remise.commission();

    cycle.setStatut(CycleStatut.TERMINE);
    cycle.setMontantJackpot(jackpot);
    cycle.setMontantCommission(totalCommission);
    cycle.setMontantNet(remise.montantNet());
    cycle.setModeRemise(mode);
    cycle.setMontantPrevu(prevu);
    cycle.setMontantNetPrevu(remise.montantNet().add(remise.resteARecevoir()));
    cycle.setMontantAvance(remise.avanceGestionnaire());
    cycle.setDateRemise(LocalDate.now());

    cycleRepository.save(cycle);
    auditService.log(
        caller,
        "cycles_tontine",
        cycleId,
        "statut",
        CycleStatut.EN_COURS.name(),
        CycleStatut.TERMINE.name());

    List<TontineMembre> gagnantsMembres = resolveGagnants(tontine, tontineId);
    List<CycleGagnant> savedGagnants = new ArrayList<>();

    if (!gagnantsMembres.isEmpty()) {
      savedGagnants.addAll(enregistrerGagnants(cycle, gagnantsMembres));

      cycle.setGagnants(savedGagnants);
      prioriteService.marquerServies(cycle, gagnantsMembres);
      schedulerService.annoncerGagnants(cycle, savedGagnants);

      // Notification in-app jackpot : gagnant(s) + admin
      for (CycleGagnant g : savedGagnants) {
        String winnerName =
            g.getMembre().getUser().getFirstName() + " " + g.getMembre().getUser().getLastName();
        String montantStr = g.getMontantRecu().toPlainString() + " FCFA";
        notificationService.notify(
            g.getMembre().getUser().getId(),
            NotificationType.JACKPOT_DISTRIBUE,
            "Jackpot distribué 🎉",
            "Félicitations ! Vous recevez " + montantStr + " · " + tontine.getNom(),
            tontineId,
            NotificationLiens.cycle(cycle.getId(), g.getMembre().getId()));
        notificationService.notify(
            tontine.getCreePar().getId(),
            NotificationType.JACKPOT_DISTRIBUE,
            "Jackpot distribué",
            winnerName + " a reçu " + montantStr + " · " + tontine.getNom(),
            tontineId,
            NotificationLiens.cycle(cycle.getId(), g.getMembre().getId()));
      }
    }

    if (savedGagnants.isEmpty()) {
      notifierGagnantsADesigner(tontine, cycle);
    }

    cotisationRepository
        .findByCycle_IdAndStatutAndDeletedAtIsNull(cycleId, CotisationStatut.EN_ATTENTE)
        .forEach(
            c -> {
              c.setStatut(CotisationStatut.EN_RETARD);
              cotisationRepository.save(c);
              // Notification in-app admin — paiement en retard
              notificationService.notify(
                  tontine.getCreePar().getId(),
                  NotificationType.PAIEMENT_EN_RETARD,
                  "Paiement en retard",
                  c.getMembre().getUser().getFirstName()
                      + " "
                      + c.getMembre().getUser().getLastName()
                      + (Ponctualite.de(c) == Ponctualite.A_TEMPS
                          ? " a déclaré son paiement à temps, non validé avant la clôture · "
                          : " n'a pas cotisé · ")
                      + tontine.getNom(),
                  tontineId,
                  NotificationLiens.cotisation(c));
            });

    // Tontine clôturée (B33) : le tour se remet encore, mais plus aucun tour ne s'ouvre
    if (tontine.getModeCycle() == ModeCycle.AUTOMATIQUE
        && tontine.getStatut() != TontineStatut.TERMINEE) {
      activateNextCycle(tontineId, cycle.getNumeroCycle() + 1);
    }

    log.info(
        "Cycle ROTATIVE clôturé – tontineId={} cycleId={} jackpot={} commission={} net={}",
        tontineId,
        cycleId,
        jackpot,
        totalCommission,
        remise.montantNet());
    return reponse(cycle);
  }

  // ─── Clôture EVENEMENTIELLE ──────────────────────────────────────────────

  private CycleResponse closeCycleEvenementielle(
      User caller, Tontine tontine, UUID tontineId, CycleTontine cycle) {

    UUID cycleId = cycle.getId();

    BigDecimal jackpotCycle =
        cotisationRepository
            .findByCycle_IdAndStatutAndDeletedAtIsNull(cycleId, CotisationStatut.VALIDE)
            .stream()
            .map(c -> c.getMontant() != null ? c.getMontant() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    cycle.setStatut(CycleStatut.TERMINE);
    cycle.setMontantJackpot(jackpotCycle);
    cycle.setMontantCommission(BigDecimal.ZERO);
    cycle.setMontantNet(jackpotCycle);
    cycle.setDateRemise(LocalDate.now());

    cycleRepository.save(cycle);
    auditService.log(
        caller,
        "cycles_tontine",
        cycleId,
        "statut",
        CycleStatut.EN_COURS.name(),
        CycleStatut.TERMINE.name());

    cotisationRepository
        .findByCycle_IdAndStatutAndDeletedAtIsNull(cycleId, CotisationStatut.EN_ATTENTE)
        .forEach(
            c -> {
              c.setStatut(CotisationStatut.EN_RETARD);
              cotisationRepository.save(c);
            });

    // Vérifier s'il reste des cycles EN_ATTENTE
    boolean isLastCycle =
        cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontineId).stream()
            .noneMatch(c -> c.getStatut() == CycleStatut.EN_ATTENTE);

    List<MembreDistributionInfo> distribution;

    if (isLastCycle) {
      // Clôture finale : calcul de la distribution totale par membre
      List<TontineCommission> commissions =
          commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontineId);
      distribution = computeFinalDistribution(tontineId, commissions);

      if (!distribution.isEmpty()) {
        BigDecimal totalCagnotte =
            distribution.stream()
                .map(MembreDistributionInfo::getMontantCotise)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCommission =
            distribution.stream()
                .map(MembreDistributionInfo::getMontantCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Mettre à jour le dernier cycle avec les totaux de toute la collecte
        cycle.setMontantJackpot(totalCagnotte);
        cycle.setMontantCommission(totalCommission);
        cycle.setMontantNet(totalCagnotte.subtract(totalCommission));
        cycleRepository.save(cycle);
      }

      enregistrerDistribution(tontine, cycle, distribution);
      notifyFinalDistribution(tontine, distribution);

      // Notifications in-app distribution finale
      for (MembreDistributionInfo dist : distribution) {
        notificationService.notify(
            dist.getUserId(),
            NotificationType.DISTRIBUTION_FINALE,
            "Distribution finale 🎉",
            "Vous avez reçu "
                + dist.getMontantNet().toPlainString()
                + " FCFA · "
                + tontine.getNom(),
            tontineId,
            NotificationLiens.cycle(cycle.getId(), dist.getMembreId()));
      }
      BigDecimal cagnotteFinale =
          distribution.stream()
              .map(MembreDistributionInfo::getMontantCotise)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      notificationService.notify(
          tontine.getCreePar().getId(),
          NotificationType.DISTRIBUTION_FINALE,
          "Distribution finale terminée",
          "La tontine *"
              + tontine.getNom()
              + "* est clôturée. Cagnotte totale : "
              + cagnotteFinale.toPlainString()
              + " FCFA",
          tontineId,
          NotificationLiens.cycle(cycle.getId(), null));

      log.info(
          "Clôture finale EVENEMENTIELLE – tontineId={} membres={} cagnotte={}",
          tontineId,
          distribution.size(),
          distribution.stream()
              .map(MembreDistributionInfo::getMontantCotise)
              .reduce(BigDecimal.ZERO, BigDecimal::add));
    } else {
      activateNextCycle(tontineId, cycle.getNumeroCycle() + 1);
      distribution = null;
      log.info(
          "Sous-cycle EVENEMENTIELLE clôturé – tontineId={} cycleId={} num={}",
          tontineId,
          cycleId,
          cycle.getNumeroCycle());
    }

    return reponse(cycle).toBuilder().distributionParMembre(distribution).build();
  }

  /**
   * Calcule la distribution finale par membre sur l'ensemble des cycles d'une tontine
   * événementielle.
   *
   * <p>Pour chaque membre ayant au moins une cotisation VALIDE (tout statut, B43) : montantNet =
   * somme cotisations VALIDEES - quote-part commission. La commission POURCENTAGE_JACKPOT est
   * distribuée proportionnellement. La FRAIS_FIXES_PAR_CYCLE est répartie proportionnellement à la
   * mise. FRAIS_ADHESION est exclue du calcul de jackpot.
   */
  private List<MembreDistributionInfo> computeFinalDistribution(
      UUID tontineId, List<TontineCommission> commissions) {

    // B43 : chaque cotisation VALIDE revient à son membre, quel que soit son statut (SORTI,
    // SUSPENDU) ou sa suppression logique : le membre est lu sur la cotisation elle-même.
    Map<UUID, BigDecimal> totalParMembre = new LinkedHashMap<>();
    Map<UUID, TontineMembre> membreMap = new LinkedHashMap<>();
    for (Cotisation c :
        cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
            tontineId, CotisationStatut.VALIDE)) {
      totalParMembre.merge(c.getMembre().getId(), c.getMontant(), BigDecimal::add);
      membreMap.putIfAbsent(c.getMembre().getId(), c.getMembre());
    }
    if (totalParMembre.isEmpty()) return List.of();

    List<UUID> membreIds = new ArrayList<>(totalParMembre.keySet());
    List<BigDecimal> mises = membreIds.stream().map(totalParMembre::get).toList();
    BigDecimal totalCagnotte = mises.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<BigDecimal> commissionsParMembre =
        Montants.repartirAuProrata(commissionTotale(commissions, totalCagnotte), mises);

    List<MembreDistributionInfo> result = new ArrayList<>();
    for (int i = 0; i < membreIds.size(); i++) {
      TontineMembre m = membreMap.get(membreIds.get(i));
      result.add(
          MembreDistributionInfo.builder()
              .membreId(m.getId())
              .userId(m.getUser().getId())
              .firstName(m.getUser().getFirstName())
              .lastName(m.getUser().getLastName())
              .phone(m.getUser().getPhone())
              .montantCotise(mises.get(i))
              .montantCommission(commissionsParMembre.get(i))
              .montantNet(mises.get(i).subtract(commissionsParMembre.get(i)))
              .build());
    }
    return result;
  }

  /**
   * Commission totale de la tontine événementielle : POURCENTAGE_JACKPOT sur la cagnotte (arrondi
   * au franc) + FRAIS_FIXES_PAR_CYCLE ; FRAIS_ADHESION ignorés.
   */
  private static BigDecimal commissionTotale(
      List<TontineCommission> commissions, BigDecimal cagnotte) {
    BigDecimal total = BigDecimal.ZERO;
    for (TontineCommission comm : commissions) {
      switch (comm.getType()) {
        case POURCENTAGE_JACKPOT ->
            total = total.add(Montants.pourcentage(cagnotte, comm.getValeur()));
        case FRAIS_FIXES_PAR_CYCLE -> total = total.add(comm.getValeur());
        case FRAIS_ADHESION -> {
          /* ignoré : ne touche pas la cagnotte */
        }
      }
    }
    return total;
  }

  /** Conserve la part de chaque membre (V034) ; une seule distribution par tontine. */
  private void enregistrerDistribution(
      Tontine tontine, CycleTontine cycle, List<MembreDistributionInfo> distribution) {
    if (distribution.isEmpty()
        || distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId())) {
      return;
    }
    distributionRepository.saveAll(
        distribution.stream()
            .map(
                d ->
                    DistributionEvenementielle.builder()
                        .tontine(tontine)
                        .cycle(cycle)
                        .membre(membreRepository.getReferenceById(d.getMembreId()))
                        .montantCotise(d.getMontantCotise())
                        .montantCommission(d.getMontantCommission())
                        .montantNet(d.getMontantNet())
                        .build())
            .toList());
  }

  private void notifyFinalDistribution(Tontine tontine, List<MembreDistributionInfo> distribution) {
    for (MembreDistributionInfo dist : distribution) {
      try {
        whatsappService.sendDistributionFinale(
            dist.getPhone(),
            tontine.getNom(),
            dist.getMontantNet().toPlainString(),
            dist.getMontantCotise().toPlainString());
      } catch (Exception e) {
        log.warn(
            "Notif WA distribution finale non envoyée pour {} : {}",
            dist.getPhone(),
            e.getMessage());
      }
    }
    // Notif admin : résumé de la clôture
    try {
      BigDecimal cagnotteTotal =
          distribution.stream()
              .map(MembreDistributionInfo::getMontantCotise)
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      whatsappService.sendDistributionFinaleAdmin(
          tontine.getCreePar().getPhone(),
          tontine.getNom(),
          cagnotteTotal.toPlainString(),
          distribution.size());
    } catch (Exception e) {
      log.warn("Notif WA admin (clôture finale) non envoyée : {}", e.getMessage());
    }
  }

  // ─── Sélection bénéficiaire jackpot ──────────────────────────────────────

  @Override
  public CycleResponse selectionnerBeneficiaire(
      String keycloakId, UUID tontineId, UUID cycleId, SelectionnerBeneficiaireRequest request)
      throws CustomException {

    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(tontineId);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    CycleTontine cycle = cycleOuvertALaDesignation(tontine, cycleId);

    List<TontineMembre> eligibles = eligibles(tontineId);
    if (eligibles.isEmpty()) {
      throw new BadRequestException(
          "Aucun membre éligible : tous les membres actifs ont déjà reçu un jackpot");
    }

    List<UUID> membreIds = request.getMembreIds();
    List<TontineMembre> choisis =
        membreIds == null || membreIds.isEmpty()
            ? prioritairesPuis(tontineId, melanger(eligibles), tontine.getNombreGagnants())
            : membresDesignes(tontineId, membreIds);

    List<CycleGagnant> savedGagnants = enregistrerGagnants(cycle, choisis);
    for (TontineMembre membre : choisis) {
      auditService.log(
          caller, "cycles_tontine", cycleId, "gagnant_ajoute", null, membre.getId().toString());
    }

    cycle.setGagnants(savedGagnants);
    prioriteService.marquerServies(cycle, choisis);

    log.info(
        "Gagnants jackpot désignés (MANUEL) – cycleId={} nb={} tontineId={}",
        cycleId,
        choisis.size(),
        tontineId);

    schedulerService.annoncerGagnants(cycle, savedGagnants);

    try {
      String noms =
          choisis.stream()
              .map(m -> m.getUser().getFirstName() + " " + m.getUser().getLastName())
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      whatsappService.sendJackpotManuelAdmin(
          tontine.getCreePar().getPhone(), noms, cycle.getNumeroCycle(), tontine.getNom());
    } catch (Exception e) {
      log.warn("Notif WA admin (jackpot manuel) non envoyée : {}", e.getMessage());
    }

    suggererCloture(tontine);
    return reponse(cycle);
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

  /**
   * Tour enrichi du suivi par échéance : jackpot prévu (celui retenu à la remise pour un tour remis
   * depuis B33), « tous payés » (B32) et reste à recevoir des bénéficiaires (B33).
   */
  private CycleResponse reponse(CycleTontine cycle) {
    EtatTour etat = suiviTour.etat(cycle);
    BigDecimal reste =
        cycle.getMontantNetPrevu() != null && cycle.getMontantNet() != null
            ? cycle.getMontantNetPrevu().subtract(cycle.getMontantNet()).max(BigDecimal.ZERO)
            : null;
    return cycleMapper.toResponse(cycle).toBuilder()
        .montantJackpotPrevu(
            cycle.getMontantPrevu() != null ? cycle.getMontantPrevu() : etat.montantJackpotPrevu())
        .tousPayes(etat.tousPayes())
        .resteARecevoir(reste)
        .build();
  }

  /** ATTENDRE avec un jackpot incomplet → 409 ; option inapplicable → 400. */
  private static CustomException remiseRefusee(
      ModeRemise mode, BigDecimal prevu, BigDecimal collecte) {
    if (mode == ModeRemise.ATTENDRE) {
      return new ConflictException(
          "Le jackpot prévu n'est pas réuni : il manque "
              + CalculRemise.manquant(prevu, collecte).toPlainString()
              + " FCFA. Choisissez une autre option de remise ou attendez les retardataires",
          ErrorCode.REMISE_JACKPOT_INCOMPLETE);
    }
    return new BadRequestException(
        mode == ModeRemise.CAISSE
            ? "La remise sur la caisse commune n'est pas encore disponible"
            : "La commission du tour ne couvre pas le manque : choisissez AVANCE_GESTIONNAIRE",
        ErrorCode.MODE_REMISE_INDISPONIBLE);
  }

  /** Tontine ROTATIVE à désignation après clôture, cycle TERMINE et encore sans gagnant. */
  private CycleTontine cycleOuvertALaDesignation(Tontine tontine, UUID cycleId)
      throws CustomException {
    if (tontine.getTontineType() == TontineType.EVENEMENTIELLE) {
      throw new BadRequestException(
          "Les tontines événementielles n'ont pas de bénéficiaire unique – chaque membre récupère sa mise");
    }
    CycleTontine cycle = findCycleById(tontine.getId(), cycleId);
    if (cycle.getStatut() != CycleStatut.TERMINE) {
      throw new BadRequestException(
          "La sélection du bénéficiaire n'est possible que sur un cycle TERMINE");
    }
    if (!OrdreBeneficiaire.designationApresCloture(tontine.getOrdreBeneficiaire())) {
      throw new BadRequestException(
          "La désignation des gagnants après clôture n'est disponible que pour les tontines"
              + " avec ordreBeneficiaire = MANUEL ou HYBRIDE");
    }
    if (cycleGagnantRepository.existsByCycle_IdAndDeletedAtIsNull(cycle.getId())) {
      throw new BadRequestException("Les gagnants ont déjà été désignés pour ce cycle");
    }
    return cycle;
  }

  /**
   * Enregistre les gagnants du tour : le net est partagé en francs entiers, somme exacte (le franc
   * de reste va aux premiers désignés), et chaque membre est marqué comme servi.
   */
  private List<CycleGagnant> enregistrerGagnants(CycleTontine cycle, List<TontineMembre> gagnants) {
    List<BigDecimal> parts = Montants.repartir(cycle.getMontantNet(), gagnants.size());
    List<CycleGagnant> enregistres = new ArrayList<>();
    for (int i = 0; i < gagnants.size(); i++) {
      TontineMembre membre = gagnants.get(i);
      enregistres.add(
          cycleGagnantRepository.save(
              CycleGagnant.builder()
                  .cycle(cycle)
                  .membre(membre)
                  .montantRecu(parts.get(i))
                  .build()));
      membre.setARecuJackpot(true);
      membre.setDateJackpot(LocalDateTime.now());
      membreRepository.save(membre);
    }
    return enregistres;
  }

  /** Membres ACTIFS n'ayant pas encore reçu de jackpot. */
  private List<TontineMembre> eligibles(UUID tontineId) {
    return membreRepository
        .findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, MembreStatut.ACTIF)
        .stream()
        .filter(m -> !m.isARecuJackpot())
        .toList();
  }

  private static List<TontineMembre> melanger(List<TontineMembre> membres) {
    List<TontineMembre> melanges = new ArrayList<>(membres);
    Collections.shuffle(melanges);
    return melanges;
  }

  private static List<TontineMembre> parOrdreDePassage(List<TontineMembre> membres) {
    return membres.stream()
        .sorted(
            Comparator.comparingInt(
                    (TontineMembre m) ->
                        m.getOrdreJackpot() != null ? m.getOrdreJackpot() : Integer.MAX_VALUE)
                .thenComparing(TontineMembre::getCreatedAt))
        .toList();
  }

  /**
   * Les {@code nombre} premiers gagnants : priorités accordées d'abord (ordre d'acceptation), puis
   * les autres éligibles dans l'ordre de la règle convenue.
   */
  private List<TontineMembre> prioritairesPuis(
      UUID tontineId, List<TontineMembre> selonRegle, int nombre) {
    List<TontineMembre> file = new ArrayList<>(prioriteService.prioritairesEligibles(tontineId));
    Set<UUID> prioritaires = file.stream().map(TontineMembre::getId).collect(Collectors.toSet());
    selonRegle.stream().filter(m -> !prioritaires.contains(m.getId())).forEach(file::add);
    return file.stream().limit(nombre).toList();
  }

  /** Tour clôturé en MANUEL / HYBRIDE avec des éligibles : le gestionnaire doit désigner. */
  private void notifierGagnantsADesigner(Tontine tontine, CycleTontine cycle) {
    if (!OrdreBeneficiaire.designationApresCloture(tontine.getOrdreBeneficiaire())
        || eligibles(tontine.getId()).isEmpty()) {
      return;
    }
    notificationService.notify(
        tontine.getCreePar().getId(),
        NotificationType.GAGNANTS_A_DESIGNER,
        "Gagnants à désigner",
        "Le tour #"
            + cycle.getNumeroCycle()
            + " de "
            + tontine.getNom()
            + " est clôturé : désignez le ou les gagnants (tirage au sort ou choix).",
        tontine.getId(),
        NotificationLiens.cycle(cycle.getId(), null));
  }

  /** Membres choisis par le gestionnaire : sans doublon, ACTIF et sans jackpot déjà reçu. */
  private List<TontineMembre> membresDesignes(UUID tontineId, List<UUID> membreIds)
      throws CustomException {
    if (new HashSet<>(membreIds).size() != membreIds.size()) {
      throw new BadRequestException("Un même membre ne peut pas être désigné deux fois");
    }
    List<TontineMembre> choisis = new ArrayList<>();
    for (UUID membreId : membreIds) {
      choisis.add(membreDesignable(tontineId, membreId));
    }
    return choisis;
  }

  private TontineMembre membreDesignable(UUID tontineId, UUID membreId) throws CustomException {
    TontineMembre m =
        membreRepository
            .findByIdAndTontine_IdAndDeletedAtIsNull(membreId, tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.MEMBER_NOT_FOUND));
    if (m.getStatut() != MembreStatut.ACTIF) {
      throw new BadRequestException(
          m.getUser().getFirstName() + " n'est pas un membre actif de cette tontine");
    }
    if (m.isARecuJackpot()) {
      throw new BadRequestException(
          m.getUser().getFirstName() + " a déjà reçu un jackpot dans cette tontine");
    }
    return m;
  }

  /**
   * Gagnants désignés automatiquement à la clôture : priorités accordées d'abord, puis la règle
   * (ROTATION : ordre de passage ; ALEATOIRE : tirage). MANUEL / HYBRIDE : aucun, le gestionnaire
   * désigne ensuite.
   */
  private List<TontineMembre> resolveGagnants(Tontine tontine, UUID tontineId) {
    String ordre = tontine.getOrdreBeneficiaire();
    boolean automatique = "ROTATION".equals(ordre) || "ALEATOIRE".equals(ordre);
    if (!automatique) {
      return List.of();
    }
    List<TontineMembre> eligibles = eligibles(tontineId);
    if (eligibles.isEmpty()) {
      return List.of();
    }
    List<TontineMembre> selonRegle =
        "ROTATION".equals(ordre) ? parOrdreDePassage(eligibles) : melanger(eligibles);
    return prioritairesPuis(tontineId, selonRegle, tontine.getNombreGagnants());
  }

  private void activateNextCycle(UUID tontineId, int nextNum) {
    cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontineId).stream()
        .filter(c -> c.getNumeroCycle() == nextNum && c.getStatut() == CycleStatut.EN_ATTENTE)
        .findFirst()
        .ifPresent(
            next -> {
              next.setStatut(CycleStatut.EN_COURS);
              cycleRepository.save(next);
              log.info("Cycle suivant activé – cycleId={} num={}", next.getId(), nextNum);
            });
  }

  private User findUserByKeycloakId(String keycloakId) throws CustomException {
    return userRepository
        .findByKeycloakId(keycloakId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.AUTH_USER_NOT_FOUND));
  }

  private Tontine findTontineById(UUID id) throws CustomException {
    return tontineRepository
        .findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
  }

  private CycleTontine findCycleById(UUID tontineId, UUID cycleId) throws CustomException {
    return cycleRepository
        .findByIdAndTontine_IdAndDeletedAtIsNull(cycleId, tontineId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.CYCLE_NOT_FOUND));
  }

  private void assertIsCreatorOrSuperAdmin(User caller, Tontine tontine) throws CustomException {
    if (isSuperAdmin(caller)) return;
    if (!tontine.getCreePar().getId().equals(caller.getId())) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }
  }

  private void assertCanAccess(User caller, Tontine tontine) throws CustomException {
    if (isSuperAdmin(caller)) return;
    if (tontine.getCreePar().getId().equals(caller.getId())) return;
    if (membreRepository.existsByTontine_IdAndUser_IdAndDeletedAtIsNull(
        tontine.getId(), caller.getId())) return;
    throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
  }

  private boolean isSuperAdmin(User user) {
    return roleAssignmentRepository.existsByUserIdAndRole(user.getId(), UserRole.SUPER_ADMIN);
  }
}
