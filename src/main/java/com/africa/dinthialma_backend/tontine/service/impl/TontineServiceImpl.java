package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.entity.UserRoleAssignment;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.KeycloakAuthService;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ConflictException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.calendrier.CalendrierTours;
import com.africa.dinthialma_backend.tontine.calendrier.Rythme;
import com.africa.dinthialma_backend.tontine.calendrier.RythmeJackpot;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.DeclenchementJackpot;
import com.africa.dinthialma_backend.tontine.codeList.ModeCycle;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.OrdreBeneficiaire;
import com.africa.dinthialma_backend.tontine.codeList.OuvertureTours;
import com.africa.dinthialma_backend.tontine.codeList.RegleRetrait;
import com.africa.dinthialma_backend.tontine.codeList.RoleFiltreTontine;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.ChangerOrdreBeneficiaireRequest;
import com.africa.dinthialma_backend.tontine.dto.CreateTontineRequest;
import com.africa.dinthialma_backend.tontine.dto.ModifierReglagesRequest;
import com.africa.dinthialma_backend.tontine.dto.RappelsEcheance;
import com.africa.dinthialma_backend.tontine.dto.TontineResponse;
import com.africa.dinthialma_backend.tontine.dto.UpdateTontineRequest;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.mapper.TontineMapper;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineSpecifications;
import com.africa.dinthialma_backend.tontine.service.interfaces.CommissionService;
import com.africa.dinthialma_backend.tontine.service.interfaces.TontineService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
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
public class TontineServiceImpl implements TontineService {

  private final TontineRepository tontineRepository;
  private final TontineMapper tontineMapper;
  private final CycleTontineRepository cycleRepository;
  private final TontineMembreRepository membreRepository;
  private final UserRepository userRepository;
  private final UserRoleAssignmentRepository roleAssignmentRepository;
  private final KeycloakAuthService keycloakAuthService;
  private final AuditService auditService;
  private final NotificationService notificationService;
  private final CommissionService commissionService;
  private final Clock horloge;

  /** Rappel par défaut N heures avant l'échéance (B26). */
  private static final int RAPPEL_HEURES_AVANT_PAR_DEFAUT = 3;

  // ─── Création ────────────────────────────────────────────────────────────

  @Override
  public TontineResponse createTontine(String keycloakId, CreateTontineRequest request)
      throws CustomException {

    User caller = findUserByKeycloakId(keycloakId);
    TontineType type =
        request.getTontineType() != null ? request.getTontineType() : TontineType.ROTATIVE;

    verifierDateDebut(request.getDateDebut());
    validateCreateRequest(request, type);
    Rythme rythme =
        Rythme.resoudre(
            request.getRythmeIntervalle(),
            request.getRythmeUnite(),
            request.getJourEcheance(),
            request.getHeureEcheance(),
            request.getFrequence());
    commissionService.verifierCommissionsInitiales(request.getCommissions());

    BigDecimal montantEffectif = resolveMontant(request, type);
    RappelsEcheance rappels = request.getRappels();
    int cotisationsParTour =
        RythmeJackpot.cotisationsParTour(
            type,
            request.getCotisationsParTour(),
            request.getJackpotVise(),
            montantEffectif,
            request.getNombreMembres() != null ? request.getNombreMembres() : 0,
            request.getNombreGagnants());

    Tontine tontine =
        Tontine.builder()
            .tontineType(type)
            .nom(request.getNom().trim())
            .description(request.getDescription())
            .montant(montantEffectif)
            .frequence(rythme.frequence())
            .rythmeIntervalle(rythme.intervalle())
            .rythmeUnite(rythme.unite())
            .jourEcheance(rythme.jourEcheance())
            .heureEcheance(rythme.heureEcheance())
            .rappelVeille(rappels == null || rappels.getVeille() == null || rappels.getVeille())
            .rappelHeuresAvant(
                rappels == null || rappels.getHeuresAvant() == null
                    ? RAPPEL_HEURES_AVANT_PAR_DEFAUT
                    : rappels.getHeuresAvant())
            .cotisationsParTour(cotisationsParTour)
            .declenchementJackpot(declenchement(type, request.getDeclenchementJackpot()))
            .modeRemise(modeRemise(request.getModeRemise()))
            // B33 lot 3 : une nouvelle tontine ouvre ses tours à leur date ; remise par le
            // gestionnaire, sauf choix contraire
            .ouvertureTours(
                request.getOuvertureTours() != null
                    ? request.getOuvertureTours()
                    : OuvertureTours.A_LA_DATE)
            .remiseAutomatique(
                type == TontineType.ROTATIVE && Boolean.TRUE.equals(request.getRemiseAutomatique()))
            // B42 : retraits anticipés permis par défaut sur une nouvelle épargne événementielle
            .regleRetrait(
                type == TontineType.EVENEMENTIELLE ? RegleRetrait.LIBRE : RegleRetrait.INTERDIT)
            .ordreBeneficiaire(
                type == TontineType.EVENEMENTIELLE ? null : request.getOrdreBeneficiaire())
            .modeCycle(
                type == TontineType.EVENEMENTIELLE ? ModeCycle.AUTOMATIQUE : request.getModeCycle())
            .dateDebut(request.getDateDebut())
            .nombreMembres(request.getNombreMembres() != null ? request.getNombreMembres() : 0)
            .nombreGagnants(request.getNombreGagnants())
            .statut(TontineStatut.BROUILLON)
            .creePar(caller)
            .dateEcheance(type == TontineType.EVENEMENTIELLE ? request.getDateEcheance() : null)
            .nomEvenement(type == TontineType.EVENEMENTIELLE ? request.getNomEvenement() : null)
            .montantLibre(type == TontineType.EVENEMENTIELLE && request.isMontantLibre())
            .montantMinimum(type == TontineType.EVENEMENTIELLE ? request.getMontantMinimum() : null)
            .build();

    Tontine saved = tontineRepository.save(tontine);
    commissionService.enregistrerCommissionsInitiales(caller, saved, request.getCommissions());
    assignRoleIfAbsent(caller, UserRole.ADMIN);
    auditService.logCreate(caller, "tontines", saved.getId());
    log.info("Tontine créée – id={} type={} par userId={}", saved.getId(), type, caller.getId());
    return tontineMapper.toResponse(saved, 0, caller.getId(), null);
  }

  // ─── Lecture ─────────────────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public TontineResponse getTontine(String keycloakId, UUID id) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);

    assertCanAccess(caller, tontine);

    return toResponse(tontine, caller);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<TontineResponse> listTontines(
      String keycloakId, RoleFiltreTontine role, TontineStatut statut, String q, Pageable pageable)
      throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    UUID uid = caller.getId();

    Specification<Tontine> perimetre;
    if (role == RoleFiltreTontine.GERE) {
      perimetre = TontineSpecifications.creeePar(uid);
    } else if (role == RoleFiltreTontine.COTISE) {
      perimetre = TontineSpecifications.cotiseePar(uid);
    } else if (isSuperAdmin(caller)) {
      perimetre = null;
    } else {
      perimetre = TontineSpecifications.visiblePar(uid);
    }

    Page<Tontine> tontines =
        tontineRepository.findAll(
            Specification.allOf(
                TontineSpecifications.nonSupprimee(),
                perimetre,
                TontineSpecifications.statut(statut),
                TontineSpecifications.recherche(q)),
            pageable);

    // Memberships de l'appelant sur la page courante : une seule requête
    List<UUID> tontineIds = tontines.getContent().stream().map(Tontine::getId).toList();
    Map<UUID, TontineMembre> mesMemberships =
        tontineIds.isEmpty()
            ? Map.of()
            : membreRepository
                .findByUser_IdAndTontine_IdInAndDeletedAtIsNull(uid, tontineIds)
                .stream()
                .collect(Collectors.toMap(m -> m.getTontine().getId(), m -> m, (a, b) -> a));

    return tontines.map(
        t -> {
          int count = (int) membreRepository.countByTontine_IdAndDeletedAtIsNull(t.getId());
          return tontineMapper.toResponse(t, count, uid, mesMemberships.get(t.getId()));
        });
  }

  // ─── Mise à jour ─────────────────────────────────────────────────────────

  @Override
  public TontineResponse updateTontine(String keycloakId, UUID id, UpdateTontineRequest request)
      throws CustomException {

    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    if (tontine.getStatut() != TontineStatut.BROUILLON) {
      if (request.getRythmeIntervalle() != null
          || request.getRythmeUnite() != null
          || request.getFrequence() != null) {
        throw new BadRequestException(
            "Le rythme ne peut être modifié qu'en statut BROUILLON",
            ErrorCode.RYTHME_NON_MODIFIABLE);
      }
      if (request.getCotisationsParTour() != null
          || request.getJackpotVise() != null
          || request.getDeclenchementJackpot() != null) {
        throw new BadRequestException(
            "Le rythme du jackpot ne peut être modifié qu'en statut BROUILLON",
            ErrorCode.COTISATIONS_PAR_TOUR_NON_MODIFIABLE);
      }
      throw new BadRequestException("La tontine ne peut être modifiée qu'en statut BROUILLON");
    }

    appliquerRythme(caller, tontine, request);
    appliquerChampsCommuns(caller, tontine, request);
    appliquerRythmeJackpot(caller, tontine, request);
    if (tontine.getTontineType() == TontineType.EVENEMENTIELLE) {
      appliquerChampsEvenementielle(caller, tontine, request);
    }

    Tontine saved = tontineRepository.save(tontine);
    return toResponse(saved, caller);
  }

  /** Rythme, jour et heure d'échéance, rappels (B26) : chaque changement est audité. */
  /**
   * Cotisations par tour et déclenchement (B32), après les champs communs : le jackpot visé se
   * calcule avec le montant et le nombre de membres éventuellement modifiés dans la même requête.
   */
  private void appliquerRythmeJackpot(User caller, Tontine tontine, UpdateTontineRequest request)
      throws CustomException {
    if (request.getCotisationsParTour() != null || request.getJackpotVise() != null) {
      int n =
          RythmeJackpot.cotisationsParTour(
              tontine.getTontineType(),
              request.getCotisationsParTour(),
              request.getJackpotVise(),
              tontine.getMontant(),
              tontine.getNombreMembres(),
              tontine.getNombreGagnants());
      modifier(
          caller,
          tontine,
          "cotisationsParTour",
          tontine.getCotisationsParTour(),
          n,
          String::valueOf,
          tontine::setCotisationsParTour);
    }
    if (request.getDeclenchementJackpot() != null) {
      modifier(
          caller,
          tontine,
          "declenchementJackpot",
          tontine.getDeclenchementJackpot(),
          declenchement(tontine.getTontineType(), request.getDeclenchementJackpot()),
          DeclenchementJackpot::name,
          tontine::setDeclenchementJackpot);
    }
    if (request.getModeRemise() != null) {
      modifier(
          caller,
          tontine,
          "modeRemise",
          tontine.getModeRemiseEffectif(),
          modeRemise(request.getModeRemise()),
          ModeRemise::name,
          tontine::setModeRemise);
    }
  }

  private void appliquerRythme(User caller, Tontine tontine, UpdateTontineRequest request)
      throws CustomException {
    Rythme actuel = tontine.getRythme();
    Rythme nouveau =
        actuel.modifier(
            request.getRythmeIntervalle(),
            request.getRythmeUnite(),
            request.getJourEcheance(),
            request.getHeureEcheance(),
            request.getFrequence());
    if (!nouveau.equals(actuel)) {
      auditService.log(
          caller, "tontines", tontine.getId(), "rythme", actuel.libelle(), nouveau.libelle());
      tontine.appliquerRythme(nouveau);
    }
    RappelsEcheance rappels = request.getRappels();
    if (rappels != null) {
      modifier(
          caller,
          tontine,
          "rappelVeille",
          tontine.isRappelVeille(),
          rappels.getVeille(),
          String::valueOf,
          tontine::setRappelVeille);
      modifier(
          caller,
          tontine,
          "rappelHeuresAvant",
          tontine.getRappelHeuresAvant(),
          rappels.getHeuresAvant(),
          String::valueOf,
          tontine::setRappelHeuresAvant);
    }
  }

  /**
   * Reprise d'un cahier (B41) : la date du cahier doit rester antérieure au nouveau début, et le
   * tour de reprise s'arrête la veille.
   */
  private void suivreTourDeReprise(Tontine tontine, LocalDate dateDebut) throws CustomException {
    if (tontine.getDateDebutReelle() == null) {
      return;
    }
    if (!tontine.getDateDebutReelle().isBefore(dateDebut)) {
      throw new BadRequestException(
          "La tontine reprend un cahier commencé le "
              + tontine.getDateDebutReelle()
              + " : la date de début doit être postérieure",
          ErrorCode.REPRISE_DATE_INVALIDE);
    }
    cycleRepository
        .findByTontine_IdAndNumeroCycleAndDeletedAtIsNull(tontine.getId(), 0)
        .ifPresent(
            tour -> {
              tour.setDateFin(dateDebut.minusDays(1));
              cycleRepository.save(tour);
            });
  }

  private void appliquerChampsCommuns(User caller, Tontine tontine, UpdateTontineRequest request)
      throws CustomException {
    if (request.getDateDebut() != null) {
      verifierDateDebut(request.getDateDebut());
      suivreTourDeReprise(tontine, request.getDateDebut());
    }
    String nom = request.getNom() != null ? request.getNom().trim() : null;
    modifier(caller, tontine, "nom", tontine.getNom(), nom, String::valueOf, tontine::setNom);
    modifier(
        caller,
        tontine,
        "description",
        tontine.getDescription(),
        request.getDescription(),
        String::valueOf,
        tontine::setDescription);
    modifier(
        caller,
        tontine,
        "montant",
        tontine.getMontant(),
        request.getMontant(),
        BigDecimal::toPlainString,
        tontine::setMontant);
    if (request.getOrdreBeneficiaire() != null) {
      verifierOrdreBeneficiaire(request.getOrdreBeneficiaire());
    }
    modifier(
        caller,
        tontine,
        "ordreBeneficiaire",
        tontine.getOrdreBeneficiaire(),
        request.getOrdreBeneficiaire(),
        String::valueOf,
        tontine::setOrdreBeneficiaire);
    modifier(
        caller,
        tontine,
        "dateDebut",
        tontine.getDateDebut(),
        request.getDateDebut(),
        LocalDate::toString,
        tontine::setDateDebut);
    modifier(
        caller,
        tontine,
        "nombreMembres",
        tontine.getNombreMembres(),
        request.getNombreMembres(),
        String::valueOf,
        tontine::setNombreMembres);
    modifier(
        caller,
        tontine,
        "nombreGagnants",
        tontine.getNombreGagnants(),
        request.getNombreGagnants(),
        String::valueOf,
        tontine::setNombreGagnants);
  }

  private void appliquerChampsEvenementielle(
      User caller, Tontine tontine, UpdateTontineRequest request) throws CustomException {
    LocalDate echeance = request.getDateEcheance();
    if (echeance != null && !echeance.isAfter(tontine.getDateDebut())) {
      throw new BadRequestException("La date d'échéance doit être postérieure à la date de début");
    }
    modifier(
        caller,
        tontine,
        "dateEcheance",
        tontine.getDateEcheance(),
        echeance,
        LocalDate::toString,
        tontine::setDateEcheance);
    if (request.getNomEvenement() != null) {
      tontine.setNomEvenement(request.getNomEvenement());
    }
    if (request.getMontantMinimum() != null) {
      tontine.setMontantMinimum(request.getMontantMinimum());
    }
    if (request.getMontantLibre() != null) {
      tontine.setMontantLibre(request.getMontantLibre());
    }
  }

  /** Champ non null dans la requête : trace l'ancienne et la nouvelle valeur, puis l'affecte. */
  private <T> void modifier(
      User caller,
      Tontine tontine,
      String champ,
      T ancienne,
      T nouvelle,
      Function<T, String> texte,
      Consumer<T> affecter) {
    if (nouvelle == null) {
      return;
    }
    String ancienTexte = ancienne != null ? texte.apply(ancienne) : null;
    auditService.log(
        caller, "tontines", tontine.getId(), champ, ancienTexte, texte.apply(nouvelle));
    affecter.accept(nouvelle);
  }

  // ─── Ordre des bénéficiaires en cours de tontine ─────────────────────────

  @Override
  public TontineResponse changerOrdreBeneficiaire(
      String keycloakId, UUID id, ChangerOrdreBeneficiaireRequest request) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);
    String nouvel = request.getOrdreBeneficiaire().trim();
    verifierOrdreBeneficiaire(nouvel);
    verifierOrdreModifiable(tontine, nouvel);

    String ancien = tontine.getOrdreBeneficiaire();
    if (nouvel.equals(ancien)) {
      return toResponse(tontine, caller);
    }
    tontine.setOrdreBeneficiaire(nouvel);
    Tontine saved = tontineRepository.save(tontine);
    auditService.log(caller, "tontines", id, "ordreBeneficiaire", ancien, nouvel);
    notifierChangementOrdre(saved);
    log.info("Ordre des bénéficiaires modifié – tontineId={} {} → {}", id, ancien, nouvel);
    return toResponse(saved, caller);
  }

  // ─── Réglages modifiables à tout moment (B33) ────────────────────────────

  @Override
  public TontineResponse modifierReglages(
      String keycloakId, UUID id, ModifierReglagesRequest request) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);
    boolean rotative = tontine.getTontineType() == TontineType.ROTATIVE;
    if (tontine.getStatut() == TontineStatut.TERMINEE
        || (!rotative && request.getModeRemise() != null)) {
      throw new BadRequestException(
          "Les réglages de remise concernent une tontine rotative non terminée");
    }
    if (request.getRegleRetrait() != null && rotative) {
      throw new BadRequestException(
          "Les retraits anticipés concernent les tontines événementielles",
          ErrorCode.RETRAIT_NON_DISPONIBLE);
    }
    if (request.getRemiseAutomatique() != null && !rotative) {
      throw new BadRequestException(
          "La remise automatique à l'échéance concerne les tontines rotatives : en événementielle,"
              + " la distribution finale est toujours faite par le gestionnaire",
          ErrorCode.REMISE_AUTOMATIQUE_NON_DISPONIBLE);
    }
    modifier(
        caller,
        tontine,
        "ouvertureTours",
        tontine.getOuvertureToursEffectif(),
        request.getOuvertureTours(),
        OuvertureTours::name,
        tontine::setOuvertureTours);
    modifier(
        caller,
        tontine,
        "remiseAutomatique",
        tontine.isRemiseAutomatique(),
        request.getRemiseAutomatique(),
        String::valueOf,
        tontine::setRemiseAutomatique);
    modifier(
        caller,
        tontine,
        "regleRetrait",
        tontine.getRegleRetraitEffectif(),
        request.getRegleRetrait(),
        RegleRetrait::name,
        tontine::setRegleRetrait);
    if (request.getModeRemise() != null) {
      modifier(
          caller,
          tontine,
          "modeRemise",
          tontine.getModeRemiseEffectif(),
          modeRemise(request.getModeRemise()),
          ModeRemise::name,
          tontine::setModeRemise);
    }
    return toResponse(tontineRepository.save(tontine), caller);
  }

  /** Option de remise retenue : DISPONIBLE par défaut ; CAISSE pas encore disponible. */
  private static ModeRemise modeRemise(ModeRemise demande) throws CustomException {
    if (demande == ModeRemise.CAISSE) {
      throw new BadRequestException(
          "La remise sur la caisse commune n'est pas encore disponible",
          ErrorCode.MODE_REMISE_INDISPONIBLE);
    }
    return demande != null ? demande : ModeRemise.DISPONIBLE;
  }

  private void verifierOrdreModifiable(Tontine tontine, String nouvel) throws CustomException {
    boolean modifiable =
        tontine.getStatut() == TontineStatut.BROUILLON
            || tontine.getStatut() == TontineStatut.ACTIVE
            || tontine.getStatut() == TontineStatut.SUSPENDUE;
    if (tontine.getTontineType() != TontineType.ROTATIVE || !modifiable) {
      throw new BadRequestException(
          "L'ordre des bénéficiaires ne se modifie que sur une tontine rotative non terminée");
    }
    if (!OrdreBeneficiaire.designationApresCloture(nouvel)
        && cycleRepository.existsTermineSansGagnant(tontine.getId())) {
      throw new ConflictException(
          "Un tour clôturé attend encore ses gagnants : désignez-les avant de passer à un ordre"
              + " automatique",
          ErrorCode.DESIGNATION_EN_ATTENTE);
    }
  }

  private void notifierChangementOrdre(Tontine tontine) {
    String libelle = libelleOrdre(tontine.getOrdreBeneficiaire());
    membreRepository
        .findByTontine_IdAndStatutAndDeletedAtIsNull(tontine.getId(), MembreStatut.ACTIF)
        .forEach(
            m ->
                notificationService.notify(
                    m.getUser().getId(),
                    NotificationType.ORDRE_BENEFICIAIRE_MODIFIE,
                    "Règle du jackpot modifiée",
                    "Les gagnants de "
                        + tontine.getNom()
                        + " seront désormais désignés "
                        + libelle
                        + ", à partir de la prochaine clôture de tour.",
                    tontine.getId()));
  }

  private static String libelleOrdre(String ordre) {
    return switch (ordre) {
      case "ROTATION" -> "selon l'ordre de passage";
      case "ALEATOIRE" -> "par tirage au sort automatique";
      case "HYBRIDE" -> "par le gestionnaire à chaque tour (tirage ou choix)";
      default -> "par le choix du gestionnaire";
    };
  }

  // ─── Suppression ─────────────────────────────────────────────────────────

  @Override
  public void deleteTontine(String keycloakId, UUID id) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    if (tontine.getStatut() != TontineStatut.BROUILLON) {
      throw new BadRequestException("Seule une tontine en BROUILLON peut être supprimée");
    }

    tontine.setDeletedAt(LocalDateTime.now());
    tontineRepository.save(tontine);
    auditService.logDelete(caller, "tontines", id);
    log.info("Tontine supprimée (soft delete) – id={}", id);
  }

  // ─── Activation ──────────────────────────────────────────────────────────

  @Override
  public TontineResponse activateTontine(String keycloakId, UUID id) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    if (tontine.getStatut() != TontineStatut.BROUILLON
        && tontine.getStatut() != TontineStatut.SUSPENDUE) {
      throw new BadRequestException(
          "La tontine doit être en BROUILLON ou SUSPENDUE pour être activée");
    }

    String oldStatut = tontine.getStatut().name();
    boolean genererTours =
        (tontine.getTontineType() == TontineType.EVENEMENTIELLE
                || tontine.getModeCycle() == ModeCycle.AUTOMATIQUE)
            // le tour de reprise n° 0 (B41) n'empêche pas la génération des tours
            && !cycleRepository.existsByTontine_IdAndNumeroCycleGreaterThanAndDeletedAtIsNull(
                id, 0);
    if (genererTours) {
      // B46 : les tours partent de dateDebut ; devenue passée depuis la création, elle ferait
      // démarrer le premier tour dans le passé
      verifierDateDebut(tontine.getDateDebut());
    }
    tontine.setStatut(TontineStatut.ACTIVE);

    if (genererTours && tontine.getTontineType() == TontineType.EVENEMENTIELLE) {
      generateEvenementielleSubCycles(tontine);
    } else if (genererTours) {
      generateCycles(tontine);
    }

    Tontine saved = tontineRepository.save(tontine);
    auditService.log(caller, "tontines", id, "statut", oldStatut, TontineStatut.ACTIVE.name());
    log.info("Tontine activée – id={} type={}", id, tontine.getTontineType());
    return toResponse(saved, caller);
  }

  // ─── Suspension ──────────────────────────────────────────────────────────

  @Override
  public TontineResponse suspendTontine(String keycloakId, UUID id) throws CustomException {
    User caller = findUserByKeycloakId(keycloakId);
    Tontine tontine = findTontineById(id);
    assertIsCreatorOrSuperAdmin(caller, tontine);

    if (tontine.getStatut() != TontineStatut.ACTIVE) {
      throw new BadRequestException("Seule une tontine ACTIVE peut être suspendue");
    }

    tontine.setStatut(TontineStatut.SUSPENDUE);
    Tontine saved = tontineRepository.save(tontine);
    auditService.log(
        caller,
        "tontines",
        id,
        "statut",
        TontineStatut.ACTIVE.name(),
        TontineStatut.SUSPENDUE.name());
    log.info("Tontine suspendue – id={}", id);
    return toResponse(saved, caller);
  }

  // ─── Génération automatique des cycles (ROTATIVE) ────────────────────────

  private void generateCycles(Tontine tontine) {
    List<TontineMembre> membres =
        new ArrayList<>(
            membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
                tontine.getId(), MembreStatut.ACTIF));

    if ("ALEATOIRE".equals(tontine.getOrdreBeneficiaire())) {
      Collections.shuffle(membres);
      for (int i = 0; i < membres.size(); i++) {
        membres.get(i).setOrdreJackpot(i + 1);
      }
      membreRepository.saveAll(membres);
    } else {
      membres.sort(
          Comparator.comparingInt(
              m -> (m.getOrdreJackpot() != null ? m.getOrdreJackpot() : Integer.MAX_VALUE)));
    }

    int n = tontine.getNombreGagnants();
    int nombreCycles = (int) Math.ceil((double) membres.size() / n);
    // B32 : chaque tour regroupe N échéances consécutives du rythme
    int parTour = tontine.getCotisationsParTourEffectif();
    enregistrerTours(
        tontine,
        CalendrierTours.grouper(
            CalendrierTours.generer(
                tontine.getRythme(), tontine.getDateDebut(), nombreCycles * parTour, null),
            parTour));

    log.info(
        "Cycles ROTATIVE générés – tontineId={} membres={} gagnants/cycle={} cycles={}",
        tontine.getId(),
        membres.size(),
        n,
        nombreCycles);
  }

  // ─── Génération des sous-cycles (EVENEMENTIELLE) ─────────────────────────

  /**
   * Génère les sous-cycles informatifs d'une tontine événementielle.
   *
   * <p>Chaque sous-cycle couvre une période selon la fréquence. Le dernier se termine exactement
   * sur {@code dateEcheance}. La fréquence est utilisée pour planifier les rappels WhatsApp.
   */
  private void generateEvenementielleSubCycles(Tontine tontine) {
    LocalDate echeance = tontine.getDateEcheance();
    List<CalendrierTours.PeriodeTour> periodes =
        CalendrierTours.generer(
            tontine.getRythme(), tontine.getDateDebut(), Integer.MAX_VALUE, echeance);
    enregistrerTours(tontine, CalendrierTours.grouper(periodes, 1));

    log.info(
        "Sous-cycles EVENEMENTIELLE générés – tontineId={} nbCycles={} echéance={}",
        tontine.getId(),
        periodes.size(),
        echeance);
  }

  /** Enregistre les tours (avec leurs échéances) : le premier EN_COURS, les suivants EN_ATTENTE. */
  private void enregistrerTours(
      Tontine tontine, List<List<CalendrierTours.PeriodeTour>> echeancesParTour) {
    for (int i = 0; i < echeancesParTour.size(); i++) {
      cycleRepository.save(
          CalendrierTours.tour(
              tontine,
              i + 1,
              echeancesParTour.get(i),
              i == 0 ? CycleStatut.EN_COURS : CycleStatut.EN_ATTENTE));
    }
  }

  /** Déclenchement de la remise : réglage demandé (ROTATIVE), sinon FIN_DU_TOUR. */
  private static DeclenchementJackpot declenchement(
      TontineType type, DeclenchementJackpot demande) {
    return type == TontineType.ROTATIVE && demande != null
        ? demande
        : DeclenchementJackpot.FIN_DU_TOUR;
  }

  // ─── Validation création ─────────────────────────────────────────────────

  private void validateCreateRequest(CreateTontineRequest request, TontineType type)
      throws CustomException {
    if (type == TontineType.EVENEMENTIELLE) {
      validerCreationEvenementielle(request);
    } else {
      validerCreationRotative(request);
    }
  }

  private static void validerCreationEvenementielle(CreateTontineRequest request)
      throws CustomException {
    if (request.getDateEcheance() == null) {
      throw new BadRequestException(
          "La date d'échéance est obligatoire pour une tontine événementielle");
    }
    if (!request.getDateEcheance().isAfter(request.getDateDebut())) {
      throw new BadRequestException("La date d'échéance doit être postérieure à la date de début");
    }
    if (!request.isMontantLibre() && !estStrictementPositif(request.getMontant())) {
      throw new BadRequestException(
          "Le montant fixe de cotisation est obligatoire quand montantLibre=false");
    }
  }

  private static void validerCreationRotative(CreateTontineRequest request) throws CustomException {
    if (request.getOrdreBeneficiaire() == null || request.getOrdreBeneficiaire().isBlank()) {
      throw new BadRequestException(
          "L'ordre des bénéficiaires est obligatoire pour une tontine rotative");
    }
    verifierOrdreBeneficiaire(request.getOrdreBeneficiaire());
    if (!estStrictementPositif(request.getMontant())) {
      throw new BadRequestException(
          "Le montant de cotisation est obligatoire pour une tontine rotative");
    }
    if (request.getNombreMembres() == null || request.getNombreMembres() < 2) {
      throw new BadRequestException(
          "Le nombre de membres doit être au minimum 2 pour une tontine rotative");
    }
    if (request.getModeCycle() == null) {
      throw new BadRequestException("Le mode de cycle est obligatoire pour une tontine rotative");
    }
  }

  /**
   * Date de début aujourd'hui ou future, heure de Dakar (B46) : création, modification et
   * activation qui génère les tours. Une reprise de tontine déjà commencée relèvera de B41.
   */
  private void verifierDateDebut(LocalDate dateDebut) throws CustomException {
    if (dateDebut != null && dateDebut.isBefore(LocalDate.now(horloge))) {
      throw new BadRequestException(
          "La date de début ("
              + DateTimeFormatter.ofPattern("dd/MM/yyyy").format(dateDebut)
              + ") est passée : choisissez aujourd'hui ou une date future, puis réessayez",
          ErrorCode.DATE_DEBUT_PASSEE);
    }
  }

  private static boolean estStrictementPositif(BigDecimal montant) {
    return montant != null && montant.compareTo(BigDecimal.ZERO) > 0;
  }

  private static void verifierOrdreBeneficiaire(String ordre) throws CustomException {
    if (!OrdreBeneficiaire.estValide(ordre)) {
      throw new BadRequestException(
          "Ordre des bénéficiaires inconnu : « "
              + ordre
              + " ». Valeurs acceptées : "
              + OrdreBeneficiaire.VALEURS,
          ErrorCode.INVALID_PARAMETER);
    }
  }

  private BigDecimal resolveMontant(CreateTontineRequest request, TontineType type) {
    if (type == TontineType.EVENEMENTIELLE && request.isMontantLibre()) {
      return request.getMontantMinimum() != null ? request.getMontantMinimum() : BigDecimal.ZERO;
    }
    return request.getMontant();
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

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

  /** Réponse enrichie du rôle de l'appelant (détail et mutations unitaires). */
  private TontineResponse toResponse(Tontine tontine, User caller) {
    int nombreMembres = (int) membreRepository.countByTontine_IdAndDeletedAtIsNull(tontine.getId());
    TontineMembre monMembership =
        membreRepository
            .findByTontine_IdAndUser_IdAndDeletedAtIsNull(tontine.getId(), caller.getId())
            .orElse(null);
    return tontineMapper.toResponse(tontine, nombreMembres, caller.getId(), monMembership);
  }

  private boolean isSuperAdmin(User user) {
    return roleAssignmentRepository.existsByUserIdAndRole(user.getId(), UserRole.SUPER_ADMIN);
  }

  private void assignRoleIfAbsent(User user, UserRole role) {
    if (roleAssignmentRepository.existsByUserIdAndRole(user.getId(), role)) return;

    boolean synced = true;
    try {
      keycloakAuthService.assignRole(user.getKeycloakId(), role.getKeycloakRole());
    } catch (Exception e) {
      log.warn(
          "Impossible d'assigner le rôle {} à user {} dans Keycloak : {}",
          role,
          user.getId(),
          e.getMessage());
      synced = false;
    }

    UserRoleAssignment assignment =
        UserRoleAssignment.builder().user(user).role(role).syncedToKeycloak(synced).build();
    roleAssignmentRepository.save(assignment);
  }
}
