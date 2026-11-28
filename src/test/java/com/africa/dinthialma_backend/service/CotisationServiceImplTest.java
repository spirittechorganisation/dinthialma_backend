package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.codeList.AccountStatus;
import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.util.FichierExport;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.codeList.OrigineCotisation;
import com.africa.dinthialma_backend.contribution.codeList.Ponctualite;
import com.africa.dinthialma_backend.contribution.dto.AdminRecordCotisationRequest;
import com.africa.dinthialma_backend.contribution.dto.CotisationResponse;
import com.africa.dinthialma_backend.contribution.dto.CycleRecapCotisationResponse;
import com.africa.dinthialma_backend.contribution.dto.MembreTotalCotisationResponse;
import com.africa.dinthialma_backend.contribution.dto.RecordCotisationRequest;
import com.africa.dinthialma_backend.contribution.dto.RefuseCotisationRequest;
import com.africa.dinthialma_backend.contribution.dto.UpdateCotisationRequest;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.mapper.CotisationMapper;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.impl.CotisationServiceImpl;
import com.africa.dinthialma_backend.epargne.repository.RetraitEpargneRepository;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.EcheanceCycleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import com.africa.dinthialma_backend.tontine.service.interfaces.RemiseService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@DisplayName("CotisationServiceImpl")
class CotisationServiceImplTest {

  @Mock private CotisationRepository cotisationRepository;
  @Spy private CotisationMapper cotisationMapper = Mappers.getMapper(CotisationMapper.class);
  @Mock private TontineRepository tontineRepository;
  @Mock private CycleTontineRepository cycleRepository;
  @Mock private EcheanceCycleRepository echeanceRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserRoleAssignmentRepository roleRepository;
  @Mock private AuditService auditService;
  @Mock private WhatsappService whatsappService;
  @Mock private NotificationService notificationService;
  @Mock private CycleService cycleService;
  @Mock private RemiseService remiseService;
  @Mock private DistributionEvenementielleRepository distributionRepository;
  @Mock private RetraitEpargneRepository retraitRepository;
  @InjectMocks private CotisationServiceImpl service;

  private User gestionnaire;
  private User awa;
  private User etranger;
  private Tontine tontine;
  private TontineMembre membre;
  private CycleTontine cycle;

  @BeforeEach
  void setUp() {
    gestionnaire = Fixtures.user("Gestionnaire");
    awa = Fixtures.user("Awa");
    etranger = Fixtures.user("Étranger");
    tontine = Fixtures.tontine(gestionnaire);
    membre = Fixtures.membre(tontine, awa, 1);
    cycle = Fixtures.cycle(tontine, 1, CycleStatut.EN_COURS);
    for (User u : List.of(gestionnaire, awa, etranger)) {
      lenient().when(userRepository.findByKeycloakId(u.getKeycloakId())).thenReturn(Optional.of(u));
    }
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    lenient()
        .when(
            cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(cycle.getId(), tontine.getId()))
        .thenReturn(Optional.of(cycle));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                tontine.getId(), awa.getId()))
        .thenReturn(Optional.of(membre));
    lenient()
        .when(
            membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
                membre.getId(), tontine.getId()))
        .thenReturn(Optional.of(membre));
    lenient()
        .when(roleRepository.existsByUserIdAndRole(any(), eq(UserRole.SUPER_ADMIN)))
        .thenReturn(false);
    lenient()
        .when(cotisationRepository.save(any()))
        .thenAnswer(
            i -> {
              Cotisation c = i.getArgument(0);
              if (c.getId() == null) c.setId(UUID.randomUUID());
              return c;
            });
  }

  private static void code(Throwable e, ErrorCode attendu) {
    assertThat(((CustomException) e).getCode()).isEqualTo(attendu);
  }

  private RecordCotisationRequest declaration(String montant) {
    RecordCotisationRequest r = new RecordCotisationRequest();
    r.setCycleId(cycle.getId());
    r.setMontant(new BigDecimal(montant));
    r.setMethodePaiement("WAVE");
    r.setReferenceTransaction("REF-1");
    return r;
  }

  private AdminRecordCotisationRequest saisie(String montant, String ref) {
    AdminRecordCotisationRequest r = new AdminRecordCotisationRequest();
    r.setMembreId(membre.getId());
    r.setCycleId(cycle.getId());
    r.setMontant(new BigDecimal(montant));
    r.setMethodePaiement("CASH");
    r.setReferenceTransaction(ref);
    return r;
  }

  private Cotisation existante(CotisationStatut statut) {
    Cotisation c = Fixtures.cotisation(membre, cycle, statut);
    lenient()
        .when(cotisationRepository.findByIdAndDeletedAtIsNull(c.getId()))
        .thenReturn(Optional.of(c));
    return c;
  }

  @Nested
  @DisplayName("déclaration par le membre")
  class Declaration {

    @Test
    @DisplayName(
        "EN_ATTENTE, saisie par le membre, membre + gestionnaire notifiés, WhatsApp non bloquant")
    void succes() throws Exception {
      doThrow(new RuntimeException("WhatsApp KO"))
          .when(whatsappService)
          .sendCotisation(anyString(), anyString());

      CotisationResponse r =
          service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1000"));

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.EN_ATTENTE);
      assertThat(r.getMembre().getMembreId()).isEqualTo(membre.getId());
      assertThat(r.getEnregistrePar().getId()).isEqualTo(awa.getId());
      assertThat(r.getNumeroCycle()).isEqualTo(1);
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.COTISATION_SOUMISE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.PAIEMENT_RECU),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(whatsappService, times(2)).sendCotisation(anyString(), anyString());
      verify(auditService).logCreate(eq(awa), eq("cotisations"), any());
    }

    @Test
    @DisplayName(
        "refus : non membre (403), tour pas encore ouvert, déjà déclarée (409), cycle inconnu (404)")
    void refus() {
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      etranger.getKeycloakId(), tontine.getId(), declaration("1000")))
          .isInstanceOf(ForbiddenException.class);

      when(cotisationRepository.existsActiveByCycleAndMembre(cycle.getId(), membre.getId()))
          .thenReturn(true);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.COTISATION_ALREADY_EXISTS));

      cycle.setStatut(CycleStatut.EN_ATTENTE);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.NO_CYCLE_IN_PROGRESS));

      RecordCotisationRequest r = declaration("1000");
      r.setCycleId(UUID.randomUUID());
      when(cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(r.getCycleId(), tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.recordCotisation(awa.getKeycloakId(), tontine.getId(), r))
          .isInstanceOf(NotFoundException.class);
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "ROTATIVE : montant non contrôlé ; EVENEMENTIELLE fixe : montant exact (AMOUNT_MISMATCH)")
    void montantFixe() throws Exception {
      assertThat(
              service
                  .recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1234"))
                  .getMontant())
          .isEqualByComparingTo("1234");

      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("999")))
          .satisfies(e -> code(e, ErrorCode.AMOUNT_MISMATCH));
      assertThat(
              service.recordCotisation(
                  awa.getKeycloakId(), tontine.getId(), declaration("1000.00")))
          .isNotNull();
    }

    @Test
    @DisplayName(
        "EVENEMENTIELLE libre : plancher montantMinimum (AMOUNT_BELOW_MINIMUM), sinon libre")
    void montantLibre() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      tontine.setMontantLibre(true);
      tontine.setMontantMinimum(new BigDecimal("500"));
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("499")))
          .satisfies(e -> code(e, ErrorCode.AMOUNT_BELOW_MINIMUM));
      assertThat(
              service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("7500")))
          .isNotNull();
      tontine.setMontantMinimum(null);
      assertThat(service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1")))
          .isNotNull();
    }
  }

  @Nested
  @DisplayName("saisie par le gestionnaire")
  class Saisie {

    @Test
    @DisplayName(
        "créée VALIDE, validée par le gestionnaire, membre notifié + WhatsApp avec référence")
    void succes() throws Exception {
      CotisationResponse r =
          service.adminRecordCotisation(
              gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", "OM-42"));

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.VALIDE);
      assertThat(r.getValidePar().getId()).isEqualTo(gestionnaire.getId());
      assertThat(r.getDateValidation()).isNotNull();
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.COTISATION_VALIDEE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(whatsappService).sendCotisation(eq(awa.getPhone()), contains("Référence : OM-42"));
      verify(whatsappService).sendCotisation(eq(gestionnaire.getPhone()), anyString());
      // B32 : la saisie directement VALIDE peut compléter le tour (TOUS_PAYES)
      verify(cycleService)
          .remettreSiTousPayes(eq(gestionnaire.getKeycloakId()), eq(tontine.getId()), any());
    }

    @Test
    @DisplayName(
        "membre PRE_ENROLLED : pas de notification in-app, WhatsApp d'invitation à s'inscrire")
    void preInscrit() throws Exception {
      awa.setAccountStatus(AccountStatus.PRE_ENROLLED);
      doThrow(new RuntimeException("KO"))
          .when(whatsappService)
          .sendCotisation(eq(gestionnaire.getPhone()), anyString());

      service.adminRecordCotisation(
          gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null));

      verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
      verify(whatsappService).sendCotisation(eq(awa.getPhone()), contains("Inscrivez-vous"));
    }

    @Test
    @DisplayName(
        "paiement remis en main propre : datePaiement enregistrée et prise pour la ponctualité ;"
            + " avant le début du tour -> 400")
    void datePaiement() throws Exception {
      AdminRecordCotisationRequest hier = saisie("1000", null);
      hier.setDatePaiement(cycle.getDateFin().atTime(23, 0));
      CotisationResponse r =
          service.adminRecordCotisation(gestionnaire.getKeycloakId(), tontine.getId(), hier);
      assertThat(r.getDatePaiement()).isEqualTo(cycle.getDateFin().atTime(23, 0));
      assertThat(r.getPonctualite()).isEqualTo(Ponctualite.A_TEMPS);

      AdminRecordCotisationRequest tropTot = saisie("1000", null);
      tropTot.setDatePaiement(cycle.getDateDebut().minusDays(1).atStartOfDay());
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), tropTot))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> code(e, ErrorCode.INVALID_PARAMETER));
    }

    @Test
    @DisplayName("sans référence : message sans ligne Référence")
    void sansReference() throws Exception {
      service.adminRecordCotisation(
          gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", " "));
      ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
      verify(whatsappService).sendCotisation(eq(awa.getPhone()), msg.capture());
      assertThat(msg.getValue()).doesNotContain("Référence");
    }

    @Test
    @DisplayName(
        "refus : tiers (403), membre inconnu (404), cycle clôturé, doublon (409), montant événementiel")
    void refus() {
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      awa.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .isInstanceOf(ForbiddenException.class);

      AdminRecordCotisationRequest inconnu = saisie("1000", null);
      inconnu.setMembreId(UUID.randomUUID());
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
              inconnu.getMembreId(), tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), inconnu))
          .isInstanceOf(NotFoundException.class);

      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("10", null)))
          .satisfies(e -> code(e, ErrorCode.AMOUNT_MISMATCH));

      when(cotisationRepository.existsActiveByCycleAndMembre(cycle.getId(), membre.getId()))
          .thenReturn(true);
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .satisfies(e -> code(e, ErrorCode.COTISATION_ALREADY_EXISTS));

      cycle.setStatut(CycleStatut.EN_ATTENTE);
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .satisfies(e -> code(e, ErrorCode.NO_CYCLE_IN_PROGRESS));
      verify(cotisationRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("validation et refus")
  class ValidationEtRefus {

    @Test
    @DisplayName("validation : VALIDE, validateur et date posés, audit, notification et WhatsApp")
    void validation() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      c.setReferenceTransaction("REF-9");
      doThrow(new RuntimeException("KO"))
          .when(whatsappService)
          .sendCotisation(eq(gestionnaire.getPhone()), anyString());

      CotisationResponse r =
          service.validateCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId());

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.VALIDE);
      assertThat(c.getValidePar()).isSameAs(gestionnaire);
      assertThat(c.getDateValidation()).isNotNull();
      verify(auditService)
          .log(gestionnaire, "cotisations", c.getId(), "statut", "EN_ATTENTE", "VALIDE");
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.COTISATION_VALIDEE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(whatsappService).sendCotisation(eq(awa.getPhone()), contains("Référence : REF-9"));
      // B32 : chaque validation vérifie si le jackpot peut être remis (TOUS_PAYES)
      verify(cycleService)
          .remettreSiTousPayes(gestionnaire.getKeycloakId(), tontine.getId(), c.getCycle().getId());
    }

    @Test
    @DisplayName("validation sans référence : message simple")
    void validationSansReference() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      service.validateCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId());
      ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
      verify(whatsappService).sendCotisation(eq(awa.getPhone()), msg.capture());
      assertThat(msg.getValue()).doesNotContain("Référence");
    }

    @Test
    @DisplayName("refus : REFUSE, motif rogné, refuseur, membre notifié avec motif")
    void refus() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      RefuseCotisationRequest req = new RefuseCotisationRequest();
      req.setMotif("  Référence introuvable ");
      doThrow(new RuntimeException("KO"))
          .when(whatsappService)
          .sendCotisation(anyString(), anyString());

      CotisationResponse r =
          service.refuseCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId(), req);

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.REFUSE);
      assertThat(r.getMotifRefus()).isEqualTo("Référence introuvable");
      assertThat(r.getRefusePar().getId()).isEqualTo(gestionnaire.getId());
      assertThat(c.getDateRefus()).isNotNull();
      ArgumentCaptor<String> corps = ArgumentCaptor.forClass(String.class);
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.COTISATION_REFUSEE),
              anyString(),
              corps.capture(),
              eq(tontine.getId()),
              any());
      assertThat(corps.getValue()).contains("Référence introuvable");
    }

    @Test
    @DisplayName("B33 : une cotisation EN_RETARD (non validée à la clôture) se valide ou se refuse")
    void enRetardTraitable() throws Exception {
      cycle.setStatut(CycleStatut.TERMINE);
      Cotisation c = existante(CotisationStatut.EN_RETARD);

      CotisationResponse r =
          service.validateCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId());

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.VALIDE);
      verify(auditService)
          .log(gestionnaire, "cotisations", c.getId(), "statut", "EN_RETARD", "VALIDE");
      // B33 lot 2 : le tour remis est recalculé (complément ou remboursement d'avance)
      verify(remiseService).regulariser(cycle);

      Cotisation autre = existante(CotisationStatut.EN_RETARD);
      RefuseCotisationRequest motif = new RefuseCotisationRequest();
      motif.setMotif("Paiement jamais reçu");
      assertThat(
              service
                  .refuseCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), autre.getId(), motif)
                  .getStatut())
          .isEqualTo(CotisationStatut.REFUSE);
      verify(auditService)
          .log(gestionnaire, "cotisations", autre.getId(), "statut", "EN_RETARD", "REFUSE");
    }

    @Test
    @DisplayName(
        "déjà VALIDE (COTISATION_NOT_PENDING), autre tontine ou inconnue (404), tiers (403)")
    void transitionsInterdites() {
      Cotisation valide = existante(CotisationStatut.VALIDE);
      RefuseCotisationRequest req = new RefuseCotisationRequest();
      req.setMotif("x");
      assertThatThrownBy(
              () ->
                  service.validateCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), valide.getId()))
          .satisfies(e -> code(e, ErrorCode.COTISATION_NOT_PENDING));
      assertThatThrownBy(
              () ->
                  service.refuseCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), valide.getId(), req))
          .satisfies(e -> code(e, ErrorCode.COTISATION_NOT_PENDING));

      Cotisation ailleurs = existante(CotisationStatut.EN_ATTENTE);
      ailleurs.setTontine(Fixtures.tontine(gestionnaire));
      assertThatThrownBy(
              () ->
                  service.validateCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), ailleurs.getId()))
          .isInstanceOf(NotFoundException.class);
      assertThatThrownBy(
              () ->
                  service.refuseCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), ailleurs.getId(), req))
          .isInstanceOf(NotFoundException.class);

      UUID inconnue = UUID.randomUUID();
      when(cotisationRepository.findByIdAndDeletedAtIsNull(inconnue)).thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.validateCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), inconnue))
          .isInstanceOf(NotFoundException.class);
      assertThatThrownBy(
              () ->
                  service.refuseCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), inconnue, req))
          .isInstanceOf(NotFoundException.class);
      assertThatThrownBy(
              () ->
                  service.validateCotisation(awa.getKeycloakId(), tontine.getId(), valide.getId()))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  @DisplayName("consultation")
  class Consultation {

    private final Pageable page = PageRequest.of(0, 20);

    @Test
    @DisplayName("liste : gestionnaire et membre ; tiers -> 403")
    @SuppressWarnings("unchecked")
    void liste() throws Exception {
      Cotisation c = existante(CotisationStatut.VALIDE);
      when(cotisationRepository.findAll(any(Specification.class), eq(page)))
          .thenReturn(new PageImpl<>(List.of(c)));

      assertThat(
              service.listCotisations(
                  gestionnaire.getKeycloakId(),
                  tontine.getId(),
                  cycle.getId(),
                  membre.getId(),
                  List.of(CotisationStatut.VALIDE),
                  "awa",
                  page))
          .hasSize(1);
      assertThat(
              service.listCotisations(
                  awa.getKeycloakId(), tontine.getId(), null, UUID.randomUUID(), null, null, page))
          .hasSize(1);
      assertThatThrownBy(
              () ->
                  service.listCotisations(
                      etranger.getKeycloakId(), tontine.getId(), null, null, null, null, page))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName(
        "détail : propriétaire et gestionnaire oui ; autre membre ou tiers non ; autre tontine -> 404")
    void detail() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      assertThat(service.getCotisation(awa.getKeycloakId(), tontine.getId(), c.getId()).getId())
          .isEqualTo(c.getId());
      assertThat(service.getCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId()))
          .isNotNull();

      User fatou = Fixtures.user("Fatou");
      when(userRepository.findByKeycloakId(fatou.getKeycloakId())).thenReturn(Optional.of(fatou));
      when(membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
              tontine.getId(), fatou.getId()))
          .thenReturn(Optional.of(Fixtures.membre(tontine, fatou, 2)));
      assertThatThrownBy(
              () -> service.getCotisation(fatou.getKeycloakId(), tontine.getId(), c.getId()))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(
              () -> service.getCotisation(etranger.getKeycloakId(), tontine.getId(), c.getId()))
          .isInstanceOf(ForbiddenException.class);

      c.setTontine(Fixtures.tontine(gestionnaire));
      assertThatThrownBy(
              () -> service.getCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId()))
          .isInstanceOf(NotFoundException.class);
      UUID inconnue = UUID.randomUUID();
      when(cotisationRepository.findByIdAndDeletedAtIsNull(inconnue)).thenReturn(Optional.empty());
      assertThatThrownBy(
              () -> service.getCotisation(gestionnaire.getKeycloakId(), tontine.getId(), inconnue))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName(
        "export CSV : en-tête, une ligne par cotisation, nom de fichier ; format autre que csv -> 400")
    @SuppressWarnings("unchecked")
    void export() throws Exception {
      Cotisation c = existante(CotisationStatut.REFUSE);
      c.setMotifRefus("doublon");
      c.setOrigine(OrigineCotisation.SAISIE);
      c.getCycle().setEcheanceAt(LocalDate.of(2026, 11, 22).atTime(20, 0));
      // B41 : origine lisible en fin de ligne (absente pour une ligne sans origine connue)
      Cotisation declaree = existante(CotisationStatut.VALIDE);
      declaree.setOrigine(OrigineCotisation.DECLARATION);
      Cotisation reprise = existante(CotisationStatut.VALIDE);
      reprise.setOrigine(OrigineCotisation.REPRISE);
      Cotisation sansOrigine = existante(CotisationStatut.VALIDE);
      when(cotisationRepository.findAll(any(Specification.class), any(Pageable.class)))
          .thenReturn(new PageImpl<>(List.of(c, declaree, reprise, sansOrigine)));

      FichierExport f =
          service.exportCotisations(
              gestionnaire.getKeycloakId(), tontine.getId(), "CSV", null, null);

      String contenu = new String(f.contenu(), StandardCharsets.UTF_8);
      assertThat(f.nomFichier()).isEqualTo("cotisations-" + tontine.getId() + ".csv");
      assertThat(contenu)
          .contains(
              "Tour;Membre;Téléphone;",
              ";Note;Échéance du tour;Ponctualité;Payée le;Rythme;Origine")
          .contains("Awa Test")
          .contains("doublon")
          // B26 : échéance du tour, ponctualité (refusée = non déclarée), rythme lisible
          .contains(
              ";2026-11-22T20:00;NON_DECLAREE;;chaque mois, avant 23h59;Saisie par le gestionnaire")
          .contains(";Déclarée par le membre", ";Reprise du cahier");
      assertThat(contenu.lines().filter(l -> !l.isBlank())).hasSize(5);
      assertThat(contenu.lines().filter(l -> l.endsWith("avant 23h59;"))).hasSize(1);

      assertThatThrownBy(
              () ->
                  service.exportCotisations(
                      gestionnaire.getKeycloakId(), tontine.getId(), "xlsx", null, null))
          .satisfies(e -> code(e, ErrorCode.INVALID_PARAMETER));
      assertThatThrownBy(
              () ->
                  service.exportCotisations(
                      awa.getKeycloakId(), tontine.getId(), "csv", null, null))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  @DisplayName("correction par le gestionnaire")
  class Correction {

    private UpdateCotisationRequest req(String montant, String methode, String ref, String note) {
      UpdateCotisationRequest r = new UpdateCotisationRequest();
      r.setMontant(montant != null ? new BigDecimal(montant) : null);
      r.setMethodePaiement(methode);
      r.setReferenceTransaction(ref);
      r.setNote(note);
      return r;
    }

    @Test
    @DisplayName("EN_ATTENTE : champs fournis modifiés et audités ; montant identique non audité")
    void enAttente() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);

      CotisationResponse r =
          service.updateCotisation(
              gestionnaire.getKeycloakId(),
              tontine.getId(),
              c.getId(),
              req("1500", "OM", "R2", "corrigé"));

      assertThat(r.getMontant()).isEqualByComparingTo("1500");
      assertThat(r.getMethodePaiement()).isEqualTo("OM");
      assertThat(r.getReferenceTransaction()).isEqualTo("R2");
      assertThat(r.getNote()).isEqualTo("corrigé");
      verify(auditService).log(gestionnaire, "cotisations", c.getId(), "montant", "1000", "1500");
      verify(auditService, times(3))
          .log(eq(gestionnaire), eq("cotisations"), eq(c.getId()), anyString(), any(), any());

      service.updateCotisation(
          gestionnaire.getKeycloakId(),
          tontine.getId(),
          c.getId(),
          req("1500.00", null, null, null));
      verify(auditService, times(3))
          .log(eq(gestionnaire), eq("cotisations"), eq(c.getId()), anyString(), any(), any());
    }

    @Test
    @DisplayName(
        "VALIDE sur cycle EN_COURS autorisée ; VALIDE sur cycle TERMINE, EN_RETARD, REFUSE -> COTISATION_NOT_EDITABLE")
    void editabilite() throws Exception {
      Cotisation valide = existante(CotisationStatut.VALIDE);
      assertThat(
              service.updateCotisation(
                  gestionnaire.getKeycloakId(),
                  tontine.getId(),
                  valide.getId(),
                  req(null, null, null, "ok")))
          .isNotNull();

      cycle.setStatut(CycleStatut.TERMINE);
      assertThatThrownBy(
              () ->
                  service.updateCotisation(
                      gestionnaire.getKeycloakId(),
                      tontine.getId(),
                      valide.getId(),
                      req(null, null, null, "x")))
          .satisfies(e -> code(e, ErrorCode.COTISATION_NOT_EDITABLE));
      Cotisation refusee = existante(CotisationStatut.REFUSE);
      assertThatThrownBy(
              () ->
                  service.updateCotisation(
                      gestionnaire.getKeycloakId(),
                      tontine.getId(),
                      refusee.getId(),
                      req(null, null, null, "x")))
          .satisfies(e -> code(e, ErrorCode.COTISATION_NOT_EDITABLE));
      // B33 : une cotisation EN_RETARD reste à valider, donc corrigeable même tour clôturé
      Cotisation enRetard = existante(CotisationStatut.EN_RETARD);
      assertThat(
              service.updateCotisation(
                  gestionnaire.getKeycloakId(),
                  tontine.getId(),
                  enRetard.getId(),
                  req(null, null, null, "ok")))
          .isNotNull();
    }

    @Test
    @DisplayName("montant hors règle événementielle refusé ; autre tontine -> 404 ; tiers -> 403")
    void refus() {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(
              () ->
                  service.updateCotisation(
                      gestionnaire.getKeycloakId(),
                      tontine.getId(),
                      c.getId(),
                      req("1200", null, null, null)))
          .satisfies(e -> code(e, ErrorCode.AMOUNT_MISMATCH));
      assertThatThrownBy(
              () ->
                  service.updateCotisation(
                      awa.getKeycloakId(), tontine.getId(), c.getId(), req(null, null, null, null)))
          .isInstanceOf(ForbiddenException.class);
      c.setTontine(Fixtures.tontine(gestionnaire));
      assertThatThrownBy(
              () ->
                  service.updateCotisation(
                      gestionnaire.getKeycloakId(),
                      tontine.getId(),
                      c.getId(),
                      req(null, null, null, null)))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("récapitulatifs")
  class Recaps {

    private final Pageable page = PageRequest.of(0, 20);

    @Test
    @DisplayName("récap d'un tour : statut par membre, la cotisation active prime sur une refusée")
    void recapTour() throws Exception {
      TontineMembre fatou = Fixtures.membre(tontine, Fixtures.user("Fatou"), 2);
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre, fatou)));
      Cotisation refusee = Fixtures.cotisation(membre, cycle, CotisationStatut.REFUSE);
      refusee.setCreatedAt(LocalDateTime.now());
      Cotisation active = Fixtures.cotisation(membre, cycle, CotisationStatut.EN_ATTENTE);
      active.setCreatedAt(LocalDateTime.now().minusHours(1));
      when(cotisationRepository.findByCycle_IdAndMembre_IdInAndDeletedAtIsNull(
              eq(cycle.getId()), anyList()))
          .thenReturn(List.of(refusee, active));

      List<CycleRecapCotisationResponse> r =
          service
              .getCycleRecap(
                  gestionnaire.getKeycloakId(), tontine.getId(), cycle.getId(), null, page)
              .getContent();

      assertThat(r.get(0).getCotisationId()).isEqualTo(active.getId());
      assertThat(r.get(0).getStatutCotisation()).isEqualTo(CotisationStatut.EN_ATTENTE);
      assertThat(r.get(1).getCotisationId()).isNull();
      assertThat(r.get(1).getFirstName()).isEqualTo("Fatou");
    }

    @Test
    @DisplayName("récap d'un tour : page vide sans requête de cotisations ; cycle inconnu -> 404")
    void recapVide() throws Exception {
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(Page.empty(page));
      assertThat(
              service.getCycleRecap(
                  gestionnaire.getKeycloakId(), tontine.getId(), cycle.getId(), null, page))
          .isEmpty();
      verify(cotisationRepository, never())
          .findByCycle_IdAndMembre_IdInAndDeletedAtIsNull(any(), any());

      UUID inconnu = UUID.randomUUID();
      when(cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(inconnu, tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.getCycleRecap(
                      gestionnaire.getKeycloakId(), tontine.getId(), inconnu, null, page))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName(
        "récap total : somme et nombre de VALIDE par membre, 0 sans cotisation ; tiers -> 403")
    void recapTotal() throws Exception {
      TontineMembre fatou = Fixtures.membre(tontine, Fixtures.user("Fatou"), 2);
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre, fatou)));
      List<Object[]> lignes =
          List.<Object[]>of(new Object[] {membre.getId(), new BigDecimal("3000"), 3L});
      when(cotisationRepository.sumAndCountValideGroupByMembreIn(eq(tontine.getId()), anyList()))
          .thenReturn(lignes);

      List<MembreTotalCotisationResponse> r =
          service
              .getTontineRecapTotal(gestionnaire.getKeycloakId(), tontine.getId(), page)
              .getContent();

      assertThat(r.get(0).getTotalCotise()).isEqualByComparingTo("3000");
      assertThat(r.get(0).getNombreCotisationsValidees()).isEqualTo(3);
      assertThat(r.get(1).getTotalCotise()).isEqualByComparingTo("0");
      assertThat(r.get(1).getNombreCotisationsValidees()).isZero();

      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(Page.empty(page));
      assertThat(service.getTontineRecapTotal(gestionnaire.getKeycloakId(), tontine.getId(), page))
          .isEmpty();
      assertThatThrownBy(
              () -> service.getTontineRecapTotal(awa.getKeycloakId(), tontine.getId(), page))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  @DisplayName("B48 : récapitulatifs avec les membres SORTI qui ont cotisé (inclureSortis)")
  class RecapsAvecSortis {

    private final Pageable page = PageRequest.of(0, 20);

    @Test
    @DisplayName(
        "récap total inclureSortis=true : requête dédiée, le SORTI apparaît avec son total")
    @SuppressWarnings("unchecked")
    void recapTotalAvecSortis() throws Exception {
      TontineMembre sorti = Fixtures.membre(tontine, Fixtures.user("Sorti"), 2);
      sorti.setStatut(MembreStatut.SORTI);
      when(membreRepository.findAll(any(Specification.class), eq(page)))
          .thenReturn(new PageImpl<>(List.of(membre, sorti)));
      List<Object[]> lignes =
          List.<Object[]>of(new Object[] {sorti.getId(), new BigDecimal("4000"), 2L});
      when(cotisationRepository.sumAndCountValideGroupByMembreIn(eq(tontine.getId()), anyList()))
          .thenReturn(lignes);

      List<MembreTotalCotisationResponse> r =
          service
              .getTontineRecapTotal(gestionnaire.getKeycloakId(), tontine.getId(), true, page)
              .getContent();

      assertThat(r).hasSize(2);
      assertThat(r.get(1).getStatutMembre()).isEqualTo(MembreStatut.SORTI);
      assertThat(r.get(1).getTotalCotise()).isEqualByComparingTo("4000");
      verify(membreRepository, never())
          .findByTontine_IdAndStatutNotAndDeletedAtIsNull(any(), any(), any());
    }

    @Test
    @DisplayName("récap total inclureSortis=false : comportement historique (SORTI exclus)")
    void recapTotalSansSortis() throws Exception {
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre)));

      assertThat(
              service.getTontineRecapTotal(
                  gestionnaire.getKeycloakId(), tontine.getId(), false, page))
          .hasSize(1);
      verify(membreRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("récap d'un tour inclureSortis=true : le SORTI qui a cotisé sur le tour apparaît")
    @SuppressWarnings("unchecked")
    void recapTourAvecSortis() throws Exception {
      TontineMembre sorti = Fixtures.membre(tontine, Fixtures.user("Sorti"), 2);
      sorti.setStatut(MembreStatut.SORTI);
      when(membreRepository.findAll(any(Specification.class), eq(page)))
          .thenReturn(new PageImpl<>(List.of(sorti)));
      Cotisation c = Fixtures.cotisation(sorti, cycle, CotisationStatut.VALIDE);
      when(cotisationRepository.findByCycle_IdAndMembre_IdInAndDeletedAtIsNull(
              eq(cycle.getId()), anyList()))
          .thenReturn(List.of(c));

      List<CycleRecapCotisationResponse> r =
          service
              .getCycleRecap(
                  gestionnaire.getKeycloakId(), tontine.getId(), cycle.getId(), null, true, page)
              .getContent();

      assertThat(r).hasSize(1);
      assertThat(r.get(0).getStatutMembre()).isEqualTo(MembreStatut.SORTI);
      assertThat(r.get(0).getCotisationId()).isEqualTo(c.getId());
    }
  }

  @Nested
  @DisplayName("cotisations par échéance (B32)")
  class ParEcheance {

    private final LocalDate aujourdhui = LocalDate.now();
    private EcheanceCycle passee;
    private EcheanceCycle enCours;
    private EcheanceCycle aVenir;

    private EcheanceCycle echeance(int numero, LocalDate debut, LocalDate fin) {
      EcheanceCycle e =
          EcheanceCycle.builder()
              .id(UUID.randomUUID())
              .cycle(cycle)
              .numero(numero)
              .dateDebut(debut)
              .echeanceAt(fin.atTime(23, 59))
              .build();
      cycle.getEcheances().add(e);
      lenient()
          .when(echeanceRepository.findByIdAndCycle_IdAndDeletedAtIsNull(e.getId(), cycle.getId()))
          .thenReturn(Optional.of(e));
      return e;
    }

    @BeforeEach
    void troisEcheances() {
      cycle.setDateDebut(aujourdhui.minusDays(14));
      passee = echeance(1, aujourdhui.minusDays(14), aujourdhui.minusDays(8));
      enCours = echeance(2, aujourdhui.minusDays(7), aujourdhui.plusDays(2));
      aVenir = echeance(3, aujourdhui.plusDays(3), aujourdhui.plusDays(9));
    }

    @Test
    @DisplayName(
        "B33, tour clôturé sans echeanceId : première échéance ouverte impayée ; toutes payées"
            + " -> 409")
    void regularisationTourCloture() throws Exception {
      cycle.setStatut(CycleStatut.TERMINE);
      when(cotisationRepository.existsActiveByEcheanceAndMembre(passee.getId(), membre.getId()))
          .thenReturn(true);

      CotisationResponse r =
          service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1000"));

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.EN_ATTENTE);
      assertThat(r.getEcheanceId()).isEqualTo(enCours.getId());

      when(cotisationRepository.existsActiveByEcheanceAndMembre(enCours.getId(), membre.getId()))
          .thenReturn(true);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.COTISATION_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("B33, encaissement d'une échéance passée sur un tour clôturé : créée VALIDE")
    void encaissementTourCloture() throws Exception {
      cycle.setStatut(CycleStatut.TERMINE);
      AdminRecordCotisationRequest saisie = saisie("1000", null);
      saisie.setEcheanceId(passee.getId());

      CotisationResponse r =
          service.adminRecordCotisation(gestionnaire.getKeycloakId(), tontine.getId(), saisie);

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.VALIDE);
      assertThat(r.getEcheanceId()).isEqualTo(passee.getId());
      assertThat(r.getPonctualite()).isEqualTo(Ponctualite.EN_RETARD);
    }

    @Test
    @DisplayName(
        "B33, événementielle : sous-cycle clôturé payable, sauf distribution finale faite"
            + " (REGULARISATION_IMPOSSIBLE)")
    void evenementielleApresDistribution() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      cycle.setStatut(CycleStatut.TERMINE);
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(false);
      assertThat(
              service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .isNotNull();

      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(true);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.REGULARISATION_IMPOSSIBLE));
    }

    @Test
    @DisplayName("sans echeanceId : échéance en cours, unicité par échéance, libellé 2/3")
    void echeanceParDefaut() throws Exception {
      CotisationResponse r =
          service.recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1000"));

      assertThat(r.getEcheanceId()).isEqualTo(enCours.getId());
      assertThat(r.getNumeroEcheance()).isEqualTo(2);
      assertThat(r.getEcheanceAt()).isNotNull();
      verify(cotisationRepository).existsActiveByEcheanceAndMembre(enCours.getId(), membre.getId());
      verify(cotisationRepository, never()).existsActiveByCycleAndMembre(any(), any());
      verify(whatsappService, times(2))
          .sendCotisation(anyString(), contains("tour 1, échéance 2/3"));
    }

    @Test
    @DisplayName(
        "déjà payée sur l'échéance -> 409 ; future -> ECHEANCE_NON_OUVERTE ; inconnue -> 404")
    void refus() {
      when(cotisationRepository.existsActiveByEcheanceAndMembre(enCours.getId(), membre.getId()))
          .thenReturn(true);
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.COTISATION_ALREADY_EXISTS));

      RecordCotisationRequest future = declaration("1000");
      future.setEcheanceId(aVenir.getId());
      assertThatThrownBy(
              () -> service.recordCotisation(awa.getKeycloakId(), tontine.getId(), future))
          .satisfies(e -> code(e, ErrorCode.ECHEANCE_NON_OUVERTE));

      RecordCotisationRequest inconnue = declaration("1000");
      inconnue.setEcheanceId(UUID.randomUUID());
      assertThatThrownBy(
              () -> service.recordCotisation(awa.getKeycloakId(), tontine.getId(), inconnue))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> code(e, ErrorCode.ECHEANCE_INTROUVABLE));
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("encaissement : régularisation d'une échéance passée, ponctualité EN_RETARD")
    void regularisation() throws Exception {
      AdminRecordCotisationRequest r = saisie("1000", null);
      r.setEcheanceId(passee.getId());
      r.setDatePaiement(LocalDateTime.now().minusMinutes(5));

      CotisationResponse c =
          service.adminRecordCotisation(gestionnaire.getKeycloakId(), tontine.getId(), r);

      assertThat(c.getEcheanceId()).isEqualTo(passee.getId());
      assertThat(c.getNumeroEcheance()).isEqualTo(1);
      assertThat(c.getPonctualite()).isEqualTo(Ponctualite.EN_RETARD);
      assertThat(c.getStatut()).isEqualTo(CotisationStatut.VALIDE);
    }

    @Test
    @DisplayName("récap : échéance en cours par défaut, dues / payées, détail ; filtre echeanceId")
    void recap() throws Exception {
      Pageable page = PageRequest.of(0, 20);
      TontineMembre fatou = Fixtures.membre(tontine, Fixtures.user("Fatou"), 2);
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre, fatou)));
      Cotisation payee = Fixtures.cotisation(membre, cycle, CotisationStatut.VALIDE);
      payee.setEcheance(passee);
      Cotisation declaree = Fixtures.cotisation(membre, cycle, CotisationStatut.EN_ATTENTE);
      declaree.setEcheance(enCours);
      when(cotisationRepository.findByCycle_IdAndMembre_IdInAndDeletedAtIsNull(
              eq(cycle.getId()), anyList()))
          .thenReturn(List.of(payee, declaree));

      List<CycleRecapCotisationResponse> r =
          service
              .getCycleRecap(
                  gestionnaire.getKeycloakId(), tontine.getId(), cycle.getId(), null, page)
              .getContent();
      CycleRecapCotisationResponse awaLigne = r.get(0);
      assertThat(awaLigne.getEcheanceId()).isEqualTo(enCours.getId());
      assertThat(awaLigne.getNumeroEcheance()).isEqualTo(2);
      assertThat(awaLigne.getStatutCotisation()).isEqualTo(CotisationStatut.EN_ATTENTE);
      assertThat(awaLigne.getNombreEcheances()).isEqualTo(3);
      assertThat(awaLigne.getEcheancesDues()).isEqualTo(2);
      assertThat(awaLigne.getEcheancesPayees()).isEqualTo(1);
      assertThat(awaLigne.getEcheances())
          .extracting(CycleRecapCotisationResponse.EcheanceRecap::isOuverte)
          .containsExactly(true, true, false);
      assertThat(r.get(1).getStatutCotisation()).isNull();
      assertThat(r.get(1).getEcheancesPayees()).isZero();

      CycleRecapCotisationResponse filtree =
          service
              .getCycleRecap(
                  gestionnaire.getKeycloakId(),
                  tontine.getId(),
                  cycle.getId(),
                  passee.getId(),
                  page)
              .getContent()
              .get(0);
      assertThat(filtree.getStatutCotisation()).isEqualTo(CotisationStatut.VALIDE);
      assertThat(filtree.getNumeroEcheance()).isEqualTo(1);

      UUID inconnue = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  service.getCycleRecap(
                      gestionnaire.getKeycloakId(), tontine.getId(), cycle.getId(), inconnue, page))
          .satisfies(e -> code(e, ErrorCode.ECHEANCE_INTROUVABLE));
    }
  }

  @Nested
  @DisplayName("B47 : pas de paiement sur un sous-cycle événementiel pas encore commencé")
  class SousCycleNonCommence {

    @Test
    @DisplayName(
        "événementielle, sous-cycle de demain : déclaration et saisie → ECHEANCE_NON_OUVERTE")
    void evenementielleRefusee() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      cycle.setDateDebut(LocalDate.now().plusDays(1));
      cycle.setDateFin(LocalDate.now().plusDays(1));

      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> code(e, ErrorCode.ECHEANCE_NON_OUVERTE));
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> code(e, ErrorCode.ECHEANCE_NON_OUVERTE));
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("B41 : tour de reprise n° 0 -> déclaration et saisie refusées (TOUR_DE_REPRISE)")
    void tourDeReprise() {
      cycle.setNumeroCycle(0);
      cycle.setStatut(CycleStatut.TERMINE);

      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .satisfies(e -> code(e, ErrorCode.TOUR_DE_REPRISE));
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .satisfies(e -> code(e, ErrorCode.TOUR_DE_REPRISE));
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("événementielle, sous-cycle du jour : saisie acceptée")
    void evenementielleDuJour() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      cycle.setDateDebut(LocalDate.now());
      cycle.setDateFin(LocalDate.now());

      assertThat(
              service
                  .adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null))
                  .getStatut())
          .isEqualTo(CotisationStatut.VALIDE);
    }

    @Test
    @DisplayName(
        "rotative, tour pas encore commencé : paiement d'avance accepté (remise TOUS_PAYES)")
    void rotativeAcceptee() throws Exception {
      cycle.setDateDebut(LocalDate.now().plusDays(10));

      assertThat(
              service
                  .recordCotisation(awa.getKeycloakId(), tontine.getId(), declaration("1000"))
                  .getStatut())
          .isEqualTo(CotisationStatut.EN_ATTENTE);
    }
  }

  @Nested
  @DisplayName("B44 : membre non actif (SORTI, SUSPENDU)")
  class MembreInactif {

    @Test
    @DisplayName("saisie du gestionnaire pour un membre SUSPENDU ou SORTI → MEMBRE_INACTIF")
    void saisieRefusee() {
      for (MembreStatut statut : List.of(MembreStatut.SUSPENDU, MembreStatut.SORTI)) {
        membre.setStatut(statut);
        assertThatThrownBy(
                () ->
                    service.adminRecordCotisation(
                        gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
            .isInstanceOf(BadRequestException.class)
            .satisfies(e -> code(e, ErrorCode.MEMBRE_INACTIF));
      }
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("déclaration par un membre SUSPENDU ou SORTI → MEMBRE_INACTIF")
    void declarationRefusee() {
      for (MembreStatut statut : List.of(MembreStatut.SUSPENDU, MembreStatut.SORTI)) {
        membre.setStatut(statut);
        assertThatThrownBy(
                () ->
                    service.recordCotisation(
                        awa.getKeycloakId(), tontine.getId(), declaration("1000")))
            .isInstanceOf(BadRequestException.class)
            .satisfies(e -> code(e, ErrorCode.MEMBRE_INACTIF));
      }
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("régularisation sur un tour TERMINE pour un membre SORTI → MEMBRE_INACTIF")
    void regularisationRefusee() {
      cycle.setStatut(CycleStatut.TERMINE);
      membre.setStatut(MembreStatut.SORTI);
      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .satisfies(e -> code(e, ErrorCode.MEMBRE_INACTIF));
    }

    @Test
    @DisplayName(
        "validation d'une cotisation EN_ATTENTE d'un membre suspendu entre-temps : permise")
    void validationPermise() throws Exception {
      Cotisation c = existante(CotisationStatut.EN_ATTENTE);
      membre.setStatut(MembreStatut.SUSPENDU);

      CotisationResponse r =
          service.validateCotisation(gestionnaire.getKeycloakId(), tontine.getId(), c.getId());

      assertThat(r.getStatut()).isEqualTo(CotisationStatut.VALIDE);
    }
  }

  @Nested
  @DisplayName("B42 : membre soldé et récap total avec retraits")
  class MembreSolde {

    private final Pageable page = PageRequest.of(0, 20);

    @Test
    @DisplayName("membre soldé : déclaration et saisie refusées → MEMBRE_SOLDE")
    void cotisationRefusee() {
      membre.setSoldeLe(LocalDateTime.now().minusDays(1));

      assertThatThrownBy(
              () ->
                  service.adminRecordCotisation(
                      gestionnaire.getKeycloakId(), tontine.getId(), saisie("1000", null)))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> code(e, ErrorCode.MEMBRE_SOLDE));
      assertThatThrownBy(
              () ->
                  service.recordCotisation(
                      awa.getKeycloakId(), tontine.getId(), declaration("1000")))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> code(e, ErrorCode.MEMBRE_SOLDE));
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("récap total d'une événementielle : totalRetire, solde et soldeLe par membre")
    void recapTotalEvenementielle() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      membre.setSoldeLe(LocalDateTime.of(2026, 11, 28, 18, 0));
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre)));
      when(cotisationRepository.sumAndCountValideGroupByMembreIn(eq(tontine.getId()), anyList()))
          .thenReturn(
              List.<Object[]>of(new Object[] {membre.getId(), new BigDecimal("46000"), 3L}));
      when(retraitRepository.sumRemisGroupByMembreIn(anyList()))
          .thenReturn(List.<Object[]>of(new Object[] {membre.getId(), new BigDecimal("46000")}));

      MembreTotalCotisationResponse r =
          service
              .getTontineRecapTotal(gestionnaire.getKeycloakId(), tontine.getId(), page)
              .getContent()
              .get(0);

      assertThat(r.getTotalCotise()).isEqualByComparingTo("46000");
      assertThat(r.getTotalRetire()).isEqualByComparingTo("46000");
      assertThat(r.getSolde()).isEqualByComparingTo("0");
      assertThat(r.getSoldeLe()).isEqualTo(LocalDateTime.of(2026, 11, 28, 18, 0));
    }

    @Test
    @DisplayName("récap total d'une rotative : pas de solde (null), aucune requête de retrait")
    void recapTotalRotative() throws Exception {
      when(membreRepository.findByTontine_IdAndStatutNotAndDeletedAtIsNull(
              tontine.getId(), MembreStatut.SORTI, page))
          .thenReturn(new PageImpl<>(List.of(membre)));

      MembreTotalCotisationResponse r =
          service
              .getTontineRecapTotal(gestionnaire.getKeycloakId(), tontine.getId(), page)
              .getContent()
              .get(0);

      assertThat(r.getTotalRetire()).isNull();
      assertThat(r.getSolde()).isNull();
      verify(retraitRepository, never()).sumRemisGroupByMembreIn(any());
    }
  }
}
