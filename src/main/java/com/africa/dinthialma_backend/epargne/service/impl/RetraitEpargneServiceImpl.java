package com.africa.dinthialma_backend.epargne.service.impl;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.common.util.CsvExport;
import com.africa.dinthialma_backend.common.util.CsvWriter;
import com.africa.dinthialma_backend.common.util.FichierExport;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.epargne.calcul.SoldeEpargne;
import com.africa.dinthialma_backend.epargne.codeList.NatureRetrait;
import com.africa.dinthialma_backend.epargne.codeList.OrigineRetrait;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.dto.RetraitRequests;
import com.africa.dinthialma_backend.epargne.dto.SoldeEpargneResponse;
import com.africa.dinthialma_backend.epargne.entity.RetraitEpargne;
import com.africa.dinthialma_backend.epargne.mapper.RetraitEpargneMapper;
import com.africa.dinthialma_backend.epargne.repository.RetraitEpargneRepository;
import com.africa.dinthialma_backend.epargne.repository.RetraitEpargneSpecifications;
import com.africa.dinthialma_backend.epargne.service.interfaces.RetraitEpargneService;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.dto.NotificationLiens;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.codeList.RegleRetrait;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RetraitEpargneServiceImpl implements RetraitEpargneService {

  private static final String TABLE = "retraits_epargne";

  /** Au-delà, un retrait saisi est un report du cahier : pas de notification rétroactive. */
  private static final int HEURES_NOTIFICATION = 24;

  private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

  /** Cotisations encore validables qui bloquent un retrait total (B42). */
  private static final List<CotisationStatut> A_TRAITER_AVANT_RETRAIT_TOTAL =
      List.of(CotisationStatut.EN_ATTENTE, CotisationStatut.EN_RETARD);

  private final RetraitEpargneRepository retraitRepository;
  private final RetraitEpargneMapper retraitMapper;
  private final TontineRepository tontineRepository;
  private final TontineMembreRepository membreRepository;
  private final CotisationRepository cotisationRepository;
  private final DistributionEvenementielleRepository distributionRepository;
  private final AccessControl accessControl;
  private final AuditService auditService;
  private final NotificationService notificationService;
  private final WhatsappService whatsappService;
  private final Clock horloge;

  // ─── Saisie du gestionnaire ──────────────────────────────────────────────

  @Override
  public RetraitEpargneResponse enregistrer(
      String keycloakId, UUID tontineId, RetraitRequests.Saisir request) throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    assertGestionnaire(caller, tontine);
    verifierRetraitsOuverts(tontine);

    TontineMembre membre = membre(tontineId, request.getMembreId());
    if (membre.getSoldeLe() != null) {
      throw new BadRequestException(
          "Ce membre a déjà retiré toute son épargne : annulez son retrait total pour revenir en"
              + " arrière",
          ErrorCode.MEMBRE_SOLDE);
    }
    if (request.getNature() == NatureRetrait.TOTAL) {
      verifierRienEnAttente(membre);
    }
    LocalDateTime maintenant = LocalDateTime.now(horloge);
    LocalDateTime dateRemise = dateRemise(tontine, request.getDateRemise(), maintenant);
    BigDecimal solde = soldeDe(tontineId, membre.getId());
    BigDecimal montant = montant(request, solde);

    RetraitEpargne retrait =
        retraitRepository.save(
            RetraitEpargne.builder()
                .tontine(tontine)
                .membre(membre)
                .nature(request.getNature())
                .montant(montant)
                .frais(BigDecimal.ZERO)
                .statut(StatutRetrait.REMIS)
                .origine(OrigineRetrait.GESTIONNAIRE)
                .methodePaiement(request.getMethodePaiement().trim())
                .referenceTransaction(texte(request.getReferenceTransaction()))
                .motif(texte(request.getMotif()))
                .dateRemise(dateRemise)
                .traitePar(caller)
                .build());
    auditService.logCreate(caller, TABLE, retrait.getId());
    if (request.getNature() == NatureRetrait.TOTAL) {
      membre.setSoldeLe(dateRemise);
      membreRepository.save(membre);
      auditService.log(
          caller, "tontine_membres", membre.getId(), "solde_le", null, dateRemise.toString());
    }

    BigDecimal soldeRestant = solde.subtract(montant);
    if (!dateRemise.isBefore(maintenant.minusHours(HEURES_NOTIFICATION))) {
      notifierRemise(tontine, membre, montant, soldeRestant);
    }
    log.info(
        "Retrait remis – tontineId={} membreId={} nature={} montant={} date={}",
        tontineId,
        membre.getId(),
        request.getNature(),
        montant,
        dateRemise);
    return retraitMapper.toResponse(retrait).toBuilder().soldeRestant(soldeRestant).build();
  }

  /** Montant retenu : TOTAL = tout le solde ; PARTIEL = montant fourni, en francs, ≤ solde. */
  private static BigDecimal montant(RetraitRequests.Saisir request, BigDecimal solde)
      throws CustomException {
    BigDecimal demande = request.getMontant();
    if (demande != null && demande.stripTrailingZeros().scale() > 0) {
      throw new BadRequestException(
          "Le montant s'exprime en francs entiers", ErrorCode.INVALID_PARAMETER);
    }
    if (request.getNature() == NatureRetrait.TOTAL) {
      if (solde.signum() <= 0) {
        throw soldeInsuffisant(solde);
      }
      if (demande != null && demande.compareTo(solde) != 0) {
        throw new BadRequestException(
            "Un retrait total porte sur tout le solde (" + solde.toPlainString() + " FCFA)",
            ErrorCode.INVALID_PARAMETER);
      }
      return solde;
    }
    if (demande == null) {
      throw new BadRequestException(
          "Le montant est obligatoire pour un retrait partiel", ErrorCode.INVALID_PARAMETER);
    }
    if (demande.compareTo(solde) > 0) {
      throw soldeInsuffisant(solde);
    }
    return demande;
  }

  /**
   * Retrait total : aucune cotisation EN_ATTENTE ou EN_RETARD (encore validable, B33) ne doit
   * rester, sinon sa validation redonnerait un solde positif à un membre soldé.
   */
  private void verifierRienEnAttente(TontineMembre membre) throws CustomException {
    long enAttente =
        cotisationRepository.countByMembre_IdAndStatutInAndDeletedAtIsNull(
            membre.getId(), A_TRAITER_AVANT_RETRAIT_TOTAL);
    if (enAttente > 0) {
      throw new BadRequestException(
          "Ce membre a "
              + enAttente
              + " cotisation(s) en attente de validation : validez-les ou refusez-les avant son"
              + " retrait total",
          ErrorCode.RETRAIT_COTISATIONS_EN_ATTENTE,
          Map.of("cotisationsEnAttente", enAttente));
    }
  }

  private static CustomException soldeInsuffisant(BigDecimal solde) {
    return new BadRequestException(
        "Solde insuffisant : ce membre dispose de " + solde.toPlainString() + " FCFA",
        ErrorCode.RETRAIT_SOLDE_INSUFFISANT,
        Map.of("solde", solde));
  }

  /**
   * Date réelle de la remise : défaut maintenant, jamais future, pas avant le début de la tontine.
   */
  private static LocalDateTime dateRemise(
      Tontine tontine, LocalDateTime demandee, LocalDateTime maintenant) throws CustomException {
    if (demandee == null) {
      return maintenant;
    }
    if (demandee.isAfter(maintenant)) {
      throw new BadRequestException(
          "La date de remise ne peut pas être dans le futur", ErrorCode.INVALID_PARAMETER);
    }
    if (tontine.getDateDebut() != null
        && demandee.isBefore(tontine.getDateDebut().atStartOfDay())) {
      throw new BadRequestException(
          "La date de remise ne peut pas précéder le début de la tontine ("
              + JOUR.format(tontine.getDateDebut())
              + ")",
          ErrorCode.INVALID_PARAMETER);
    }
    return demandee;
  }

  private void notifierRemise(
      Tontine tontine, TontineMembre membre, BigDecimal montant, BigDecimal soldeRestant) {
    User user = membre.getUser();
    notificationService.notify(
        user.getId(),
        NotificationType.RETRAIT_REMIS,
        "Retrait remis",
        "Retrait de "
            + montant.toPlainString()
            + " FCFA remis sur votre épargne "
            + tontine.getNom()
            + ". Solde restant : "
            + soldeRestant.toPlainString()
            + " FCFA.",
        tontine.getId(),
        NotificationLiens.membre(membre.getId()));
    if (user.getPhone() != null) {
      try {
        whatsappService.sendRetraitRemis(
            user.getPhone(),
            tontine.getNom(),
            montant.toPlainString(),
            soldeRestant.toPlainString());
      } catch (RuntimeException e) {
        log.warn("Notif WA retrait non envoyée pour {} : {}", user.getPhone(), e.getMessage());
      }
    }
  }

  // ─── Annulation ──────────────────────────────────────────────────────────

  @Override
  public RetraitEpargneResponse annuler(
      String keycloakId, UUID tontineId, UUID retraitId, RetraitRequests.Annuler request)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    assertGestionnaire(caller, tontine);
    RetraitEpargne retrait =
        retraitRepository
            .findByIdAndTontine_IdAndDeletedAtIsNull(retraitId, tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.RETRAIT_NOT_FOUND));
    verifierAnnulable(tontine, retrait);

    StatutRetrait ancien = retrait.getStatut();
    retrait.setStatut(StatutRetrait.ANNULE);
    retrait.setMotifAnnulation(request.getMotif().trim());
    retrait.setAnnulePar(caller);
    retrait.setDateAnnulation(LocalDateTime.now(horloge));
    retraitRepository.save(retrait);
    auditService.log(
        caller, TABLE, retrait.getId(), "statut", ancien.name(), StatutRetrait.ANNULE.name());

    TontineMembre membre = retrait.getMembre();
    if (retrait.getNature() == NatureRetrait.TOTAL && membre.getSoldeLe() != null) {
      auditService.log(
          caller,
          "tontine_membres",
          membre.getId(),
          "solde_le",
          membre.getSoldeLe().toString(),
          null);
      membre.setSoldeLe(null);
      membreRepository.save(membre);
    }
    log.info("Retrait annulé – tontineId={} retraitId={}", tontineId, retraitId);
    return retraitMapper.toResponse(retrait).toBuilder()
        .soldeRestant(soldeDe(tontineId, membre.getId()))
        .build();
  }

  /**
   * Annulable : retrait encore actif, distribution finale pas faite ; un partiel ne s'annule pas
   * tant que le membre est soldé (annuler d'abord le retrait total, sinon le solde redeviendrait
   * positif pour un membre qui ne cotise plus).
   */
  private void verifierAnnulable(Tontine tontine, RetraitEpargne retrait) throws CustomException {
    if (retrait.getStatut() == StatutRetrait.ANNULE
        || retrait.getStatut() == StatutRetrait.REFUSE) {
      throw new BadRequestException(
          "Ce retrait est déjà annulé ou refusé", ErrorCode.RETRAIT_NON_ANNULABLE);
    }
    if (distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId())) {
      throw new BadRequestException(
          "La distribution finale est faite : ce retrait ne peut plus être annulé",
          ErrorCode.RETRAIT_NON_ANNULABLE);
    }
    if (retrait.getNature() == NatureRetrait.PARTIEL && retrait.getMembre().getSoldeLe() != null) {
      throw new BadRequestException(
          "Ce membre est soldé : annulez d'abord son retrait total",
          ErrorCode.RETRAIT_NON_ANNULABLE);
    }
  }

  // ─── Lecture ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public Page<RetraitEpargneResponse> lister(
      String keycloakId,
      UUID tontineId,
      UUID membreId,
      List<StatutRetrait> statuts,
      Pageable pageable)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    // Un membre ne voit que ses propres retraits, quel que soit le filtre demandé
    UUID filtreMembre =
        estGestionnaire(caller, tontine) ? membreId : monMembership(caller, tontineId).getId();
    Specification<RetraitEpargne> spec =
        Specification.allOf(
            RetraitEpargneSpecifications.deTontine(tontineId),
            RetraitEpargneSpecifications.deMembre(filtreMembre),
            RetraitEpargneSpecifications.statutParmi(statuts));
    return retraitRepository.findAll(spec, pageable).map(retraitMapper::toResponse);
  }

  @Override
  @Transactional(readOnly = true)
  public SoldeEpargneResponse solde(String keycloakId, UUID tontineId, UUID membreId)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    TontineMembre membre = membre(tontineId, membreId);
    if (!estGestionnaire(caller, tontine) && !membre.getUser().getId().equals(caller.getId())) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }
    BigDecimal cotise =
        zero(cotisationRepository.sumMontantValideByTontineAndMembre(tontineId, membreId));
    BigDecimal retire = zero(retraitRepository.sumRemisByMembre(membreId));
    return SoldeEpargneResponse.builder()
        .membreId(membreId)
        .totalCotiseValide(cotise)
        .totalRetire(retire)
        .solde(SoldeEpargne.solde(cotise, retire))
        .soldeLe(membre.getSoldeLe())
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public FichierExport exporter(String keycloakId, UUID tontineId, String format)
      throws CustomException {
    CsvExport.verifierFormat(format);
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine = tontine(tontineId);
    assertGestionnaire(caller, tontine);

    CsvWriter csv =
        new CsvWriter()
            .ligne(
                "Date de remise",
                "Membre",
                "Téléphone",
                "Nature",
                "Montant (FCFA)",
                "Frais (FCFA)",
                "Statut",
                "Méthode",
                "Référence",
                "Motif",
                "Annulé le",
                "Motif d'annulation");
    for (RetraitEpargne r : retraitRepository.findPourExport(tontineId)) {
      User u = r.getMembre().getUser();
      csv.ligne(
          r.getDateRemise(),
          (u.getFirstName() + " " + u.getLastName()).trim(),
          u.getPhone(),
          r.getNature(),
          r.getMontant().toPlainString(),
          r.getFrais().toPlainString(),
          r.getStatut(),
          r.getMethodePaiement(),
          r.getReferenceTransaction(),
          r.getMotif(),
          r.getDateAnnulation(),
          r.getMotifAnnulation());
    }
    return FichierExport.csv("retraits-" + tontineId + ".csv", csv.toBytes());
  }

  // ─── Règles et accès ─────────────────────────────────────────────────────

  /**
   * Retraits possibles : événementielle ACTIVE ou SUSPENDUE, distribution finale pas faite, règle
   * LIBRE.
   */
  private void verifierRetraitsOuverts(Tontine tontine) throws CustomException {
    boolean ouverte =
        tontine.getStatut() == TontineStatut.ACTIVE
            || tontine.getStatut() == TontineStatut.SUSPENDUE;
    if (tontine.getTontineType() != TontineType.EVENEMENTIELLE
        || !ouverte
        || distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId())) {
      throw new BadRequestException(
          "Les retraits concernent une épargne événementielle en cours, avant sa distribution"
              + " finale",
          ErrorCode.RETRAIT_NON_DISPONIBLE);
    }
    if (tontine.getRegleRetraitEffectif() == RegleRetrait.INTERDIT) {
      throw new BadRequestException(
          "Les retraits anticipés ne sont pas autorisés sur cette tontine : modifiez la règle de"
              + " retrait dans les réglages",
          ErrorCode.RETRAIT_INTERDIT);
    }
  }

  private BigDecimal soldeDe(UUID tontineId, UUID membreId) {
    return SoldeEpargne.solde(
        cotisationRepository.sumMontantValideByTontineAndMembre(tontineId, membreId),
        retraitRepository.sumRemisByMembre(membreId));
  }

  private Tontine tontine(UUID tontineId) throws CustomException {
    return tontineRepository
        .findByIdAndDeletedAtIsNull(tontineId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
  }

  private TontineMembre membre(UUID tontineId, UUID membreId) throws CustomException {
    return membreRepository
        .findByIdAndTontine_IdAndDeletedAtIsNull(membreId, tontineId)
        .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.MEMBER_NOT_FOUND));
  }

  private TontineMembre monMembership(User caller, UUID tontineId) throws CustomException {
    return membreRepository
        .findByTontine_IdAndUser_IdAndDeletedAtIsNull(tontineId, caller.getId())
        .orElseThrow(() -> new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED));
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

  private static BigDecimal zero(BigDecimal valeur) {
    return valeur != null ? valeur : BigDecimal.ZERO;
  }
}
