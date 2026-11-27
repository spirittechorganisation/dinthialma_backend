package com.africa.dinthialma_backend.contribution.service.impl;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.codeList.OrigineCotisation;
import com.africa.dinthialma_backend.contribution.codeList.StatutReprise;
import com.africa.dinthialma_backend.contribution.dto.RepriseRequest;
import com.africa.dinthialma_backend.contribution.dto.RepriseResponse;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.mapper.CotisationMapper;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.interfaces.RepriseService;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.dto.NotificationLiens;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
public class RepriseServiceImpl implements RepriseService {

  /** Numéro du tour de reprise : avant le premier tour généré. */
  static final int TOUR_DE_REPRISE = 0;

  private static final String TABLE = "cotisations";
  private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  private final TontineRepository tontineRepository;
  private final TontineMembreRepository membreRepository;
  private final CycleTontineRepository cycleRepository;
  private final CotisationRepository cotisationRepository;
  private final DistributionEvenementielleRepository distributionRepository;
  private final CotisationMapper cotisationMapper;
  private final AccessControl accessControl;
  private final AuditService auditService;
  private final NotificationService notificationService;
  private final WhatsappService whatsappService;
  private final Clock horloge;

  // ─── Lecture ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public RepriseResponse lire(String keycloakId, UUID tontineId) throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    if (estGestionnaire(caller, tontine)) {
      return reponse(tontine, null);
    }
    TontineMembre moi =
        membreRepository
            .findByTontine_IdAndUser_IdAndDeletedAtIsNull(tontineId, caller.getId())
            .orElseThrow(
                () -> new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED));
    return reponse(tontine, moi.getId());
  }

  // ─── Saisie ──────────────────────────────────────────────────────────────

  @Override
  public RepriseResponse enregistrer(String keycloakId, UUID tontineId, RepriseRequest request)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    assertGestionnaire(caller, tontine);
    verifierModifiable(tontine);
    LocalDate dateDebutReelle = request.getDateDebutReelle();
    if (!dateDebutReelle.isBefore(tontine.getDateDebut())) {
      throw new BadRequestException(
          "La date de début du cahier doit précéder le début de la tontine dans l'application ("
              + JOUR.format(tontine.getDateDebut())
              + ")",
          ErrorCode.REPRISE_DATE_INVALIDE);
    }
    Map<UUID, TontineMembre> membres = membresDesSoldes(tontineId, request.getSoldes());

    CycleTontine tour = tourDeReprise(tontine, dateDebutReelle);
    Map<UUID, Cotisation> existants = new LinkedHashMap<>();
    soldesActifs(tontineId).forEach(c -> existants.put(c.getMembre().getId(), c));
    LocalDateTime maintenant = LocalDateTime.now(horloge);

    BigDecimal total = BigDecimal.ZERO;
    for (RepriseRequest.Solde solde : request.getSoldes()) {
      Cotisation existant = existants.remove(solde.getMembreId());
      String note = texte(solde.getNote());
      if (existant == null) {
        creer(caller, tontine, tour, membres.get(solde.getMembreId()), solde, note, maintenant);
      } else {
        modifier(caller, existant, solde.getMontant(), note);
      }
      total = total.add(solde.getMontant());
    }
    // Membres absents de la liste : leur solde de départ est retiré
    for (Cotisation retire : existants.values()) {
      retire.setDeletedAt(maintenant);
      cotisationRepository.save(retire);
      auditService.logDelete(caller, TABLE, retire.getId());
    }

    tour.setMontantJackpot(total);
    tour.setMontantCommission(BigDecimal.ZERO);
    tour.setMontantNet(total);
    cycleRepository.save(tour);
    if (!dateDebutReelle.equals(tontine.getDateDebutReelle())) {
      auditService.log(
          caller,
          "tontines",
          tontineId,
          "date_debut_reelle",
          tontine.getDateDebutReelle() != null ? tontine.getDateDebutReelle().toString() : null,
          dateDebutReelle.toString());
      tontine.setDateDebutReelle(dateDebutReelle);
      tontineRepository.save(tontine);
    }
    log.info(
        "Reprise enregistrée – tontineId={} membres={} total={}",
        tontineId,
        request.getSoldes().size(),
        total);
    return reponse(tontine, null);
  }

  /**
   * Reprise modifiable : événementielle non terminée, pas encore figée ni distribuée (la
   * distribution fige la reprise).
   */
  private void verifierModifiable(Tontine tontine) throws CustomException {
    if (tontine.getTontineType() != TontineType.EVENEMENTIELLE
        || tontine.getStatut() == TontineStatut.TERMINEE) {
      throw new BadRequestException(
          "La reprise d'un cahier concerne une épargne ou tontine événementielle non terminée",
          ErrorCode.REPRISE_NON_AUTORISEE);
    }
    if (tontine.getRepriseFigeeLe() != null
        || distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId())) {
      throw new BadRequestException(
          "La reprise est figée : les soldes de départ ne sont plus modifiables",
          ErrorCode.REPRISE_FIGEE);
    }
  }

  /** Membres des soldes : chacun une seule fois, membre de cette tontine, montant en francs. */
  private Map<UUID, TontineMembre> membresDesSoldes(
      UUID tontineId, List<RepriseRequest.Solde> soldes) throws CustomException {
    Map<UUID, TontineMembre> membres = new LinkedHashMap<>();
    Set<UUID> vus = new HashSet<>();
    for (RepriseRequest.Solde solde : soldes) {
      if (!vus.add(solde.getMembreId())) {
        throw new BadRequestException(
            "Un membre ne peut avoir qu'un seul solde de départ", ErrorCode.INVALID_PARAMETER);
      }
      if (solde.getMontant().stripTrailingZeros().scale() > 0) {
        throw new BadRequestException(
            "Le montant s'exprime en francs entiers", ErrorCode.INVALID_PARAMETER);
      }
      membres.put(
          solde.getMembreId(),
          membreRepository
              .findByIdAndTontine_IdAndDeletedAtIsNull(solde.getMembreId(), tontineId)
              .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.MEMBER_NOT_FOUND)));
    }
    return membres;
  }

  /**
   * Tour de reprise (n° 0, TERMINE, sans échéance) : du début du cahier à la veille du début de la
   * tontine dans l'application ; créé à la première saisie.
   */
  private CycleTontine tourDeReprise(Tontine tontine, LocalDate dateDebutReelle) {
    CycleTontine tour =
        cycleRepository
            .findByTontine_IdAndNumeroCycleAndDeletedAtIsNull(tontine.getId(), TOUR_DE_REPRISE)
            .orElseGet(
                () ->
                    CycleTontine.builder()
                        .tontine(tontine)
                        .numeroCycle(TOUR_DE_REPRISE)
                        .statut(CycleStatut.TERMINE)
                        .build());
    tour.setDateDebut(dateDebutReelle);
    tour.setDateFin(tontine.getDateDebut().minusDays(1));
    return cycleRepository.save(tour);
  }

  private void creer(
      User caller,
      Tontine tontine,
      CycleTontine tour,
      TontineMembre membre,
      RepriseRequest.Solde solde,
      String note,
      LocalDateTime maintenant) {
    Cotisation cotisation =
        cotisationRepository.save(
            Cotisation.builder()
                .tontine(tontine)
                .membre(membre)
                .cycle(tour)
                .montant(solde.getMontant())
                .note(note)
                .origine(OrigineCotisation.REPRISE)
                .statut(CotisationStatut.VALIDE)
                .enregistrePar(caller)
                .validePar(caller)
                .dateValidation(maintenant)
                .build());
    auditService.logCreate(caller, TABLE, cotisation.getId());
  }

  private void modifier(User caller, Cotisation existant, BigDecimal montant, String note) {
    boolean change = false;
    if (existant.getMontant().compareTo(montant) != 0) {
      auditService.log(
          caller,
          TABLE,
          existant.getId(),
          "montant",
          existant.getMontant().toPlainString(),
          montant.toPlainString());
      existant.setMontant(montant);
      change = true;
    }
    if (!Objects.equals(existant.getNote(), note)) {
      auditService.log(caller, TABLE, existant.getId(), "note", existant.getNote(), note);
      existant.setNote(note);
      change = true;
    }
    if (change) {
      cotisationRepository.save(existant);
    }
  }

  // ─── Gel ─────────────────────────────────────────────────────────────────

  @Override
  public RepriseResponse figer(String keycloakId, UUID tontineId) throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    assertGestionnaire(caller, tontine);
    if (tontine.getDateDebutReelle() == null) {
      throw new BadRequestException(
          "Aucune reprise à figer : enregistrez d'abord les soldes de départ",
          ErrorCode.REPRISE_NON_AUTORISEE);
    }
    figerSiOuverte(caller, tontine);
    return reponse(tontine, null);
  }

  @Override
  public void figerSiOuverte(User caller, Tontine tontine) {
    if (tontine.getDateDebutReelle() == null || tontine.getRepriseFigeeLe() != null) {
      return;
    }
    LocalDateTime maintenant = LocalDateTime.now(horloge);
    tontine.setRepriseFigeeLe(maintenant);
    tontineRepository.save(tontine);
    auditService.log(
        caller, "tontines", tontine.getId(), "reprise_figee_le", null, maintenant.toString());
    List<Cotisation> soldes = soldesActifs(tontine.getId());
    soldes.forEach(solde -> prevenir(tontine, solde));
    log.info("Reprise figée – tontineId={} soldes={}", tontine.getId(), soldes.size());
  }

  /** Solde de départ annoncé au membre, pour qu'il puisse le contester (in-app + WhatsApp). */
  private void prevenir(Tontine tontine, Cotisation solde) {
    User user = solde.getMembre().getUser();
    String montant = solde.getMontant().toPlainString();
    String depuis = JOUR.format(tontine.getDateDebutReelle());
    notificationService.notify(
        user.getId(),
        NotificationType.SOLDE_DEPART,
        "Votre solde de départ",
        "Solde repris du cahier pour "
            + tontine.getNom()
            + " (depuis le "
            + depuis
            + ") : "
            + montant
            + " FCFA. Une erreur ? Signalez-la à votre gestionnaire.",
        tontine.getId(),
        NotificationLiens.membre(solde.getMembre().getId()));
    if (user.getPhone() == null) {
      return;
    }
    try {
      whatsappService.sendSoldeDepart(user.getPhone(), tontine.getNom(), montant, depuis);
    } catch (RuntimeException e) {
      log.warn(
          "Notif WA solde de départ non envoyée pour {} : {}", user.getPhone(), e.getMessage());
    }
  }

  // ─── Réponse et accès ────────────────────────────────────────────────────

  /** Reprise de la tontine ; {@code seulMembre} non null : seule la ligne de ce membre. */
  private RepriseResponse reponse(Tontine tontine, UUID seulMembre) {
    List<Cotisation> soldes = soldesActifs(tontine.getId());
    BigDecimal total =
        soldes.stream().map(Cotisation::getMontant).reduce(BigDecimal.ZERO, BigDecimal::add);
    StatutReprise statut;
    if (tontine.getRepriseFigeeLe() != null) {
      statut = StatutReprise.FIGEE;
    } else {
      statut = tontine.getDateDebutReelle() != null ? StatutReprise.OUVERTE : StatutReprise.AUCUNE;
    }
    return RepriseResponse.builder()
        .statut(statut)
        .dateDebutReelle(tontine.getDateDebutReelle())
        .figeeLe(tontine.getRepriseFigeeLe())
        .cycleId(
            cycleRepository
                .findByTontine_IdAndNumeroCycleAndDeletedAtIsNull(tontine.getId(), TOUR_DE_REPRISE)
                .map(CycleTontine::getId)
                .orElse(null))
        .total(total)
        .nombreMembres(soldes.size())
        .soldes(
            soldes.stream()
                .filter(c -> seulMembre == null || c.getMembre().getId().equals(seulMembre))
                .map(cotisationMapper::toSoldeDepart)
                .toList())
        .build();
  }

  private List<Cotisation> soldesActifs(UUID tontineId) {
    return cotisationRepository.findByTontine_IdAndOrigineAndDeletedAtIsNull(
        tontineId, OrigineCotisation.REPRISE);
  }

  private Tontine tontine(UUID tontineId) throws CustomException {
    return tontineRepository
        .findByIdAndDeletedAtIsNull(tontineId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
  }

  private boolean estGestionnaire(User caller, Tontine tontine) {
    return accessControl.estSuperAdmin(caller)
        || tontine.getCreePar().getId().equals(caller.getId());
  }

  private void assertGestionnaire(User caller, Tontine tontine) throws CustomException {
    if (!estGestionnaire(caller, tontine)) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }
  }

  private static String texte(String valeur) {
    return valeur == null || valeur.isBlank() ? null : valeur.trim();
  }
}
