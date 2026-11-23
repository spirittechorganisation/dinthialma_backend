package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.codeList.UserRole;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.auth.repository.UserRepository;
import com.africa.dinthialma_backend.auth.repository.UserRoleAssignmentRepository;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ConflictException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.dto.EtatTour;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.interfaces.SuiviTourService;
import com.africa.dinthialma_backend.jackpot.service.interfaces.PrioriteJackpotService;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.SchedulerService;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.calendrier.Rythme;
import com.africa.dinthialma_backend.tontine.codeList.CommissionType;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.DeclenchementJackpot;
import com.africa.dinthialma_backend.tontine.codeList.ModeCycle;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.StatutEcheance;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.codeList.UniteRythme;
import com.africa.dinthialma_backend.tontine.dto.CloturerCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse;
import com.africa.dinthialma_backend.tontine.dto.OpenCycleRequest;
import com.africa.dinthialma_backend.tontine.dto.PropositionTourResponse;
import com.africa.dinthialma_backend.tontine.dto.SelectionnerBeneficiaireRequest;
import com.africa.dinthialma_backend.tontine.entity.CycleGagnant;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import com.africa.dinthialma_backend.tontine.mapper.CycleMapper;
import com.africa.dinthialma_backend.tontine.repository.CycleGagnantRepository;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineCommissionRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.impl.CycleServiceImpl;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("CycleServiceImpl")
class CycleServiceImplTest {

  @Mock private CycleTontineRepository cycleRepository;
  @Spy private CycleMapper cycleMapper = Mappers.getMapper(CycleMapper.class);
  @Mock private CycleGagnantRepository gagnantRepository;
  @Mock private TontineRepository tontineRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private CotisationRepository cotisationRepository;
  @Mock private TontineCommissionRepository commissionRepository;
  @Mock private UserRepository userRepository;
  @Mock private UserRoleAssignmentRepository roleRepository;
  @Mock private AuditService auditService;
  @Mock private SchedulerService schedulerService;
  @Mock private WhatsappService whatsappService;
  @Mock private NotificationService notificationService;
  @Mock private DistributionEvenementielleRepository distributionRepository;
  @Mock private PrioriteJackpotService prioriteService;
  @Mock private SuiviTourService suiviTour;
  @InjectMocks private CycleServiceImpl service;

  private User gestionnaire;
  private User etranger;
  private Tontine tontine;
  private String kc;

  @BeforeEach
  void setUp() {
    gestionnaire = Fixtures.user("Gestionnaire");
    etranger = Fixtures.user("Étranger");
    tontine = Fixtures.tontine(gestionnaire);
    kc = gestionnaire.getKeycloakId();
    lenient()
        .when(suiviTour.etat(any()))
        .thenReturn(new EtatTour(0, 1, null, false, null, null, 0, 0));
    lenient().when(userRepository.findByKeycloakId(kc)).thenReturn(Optional.of(gestionnaire));
    lenient()
        .when(userRepository.findByKeycloakId(etranger.getKeycloakId()))
        .thenReturn(Optional.of(etranger));
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    lenient()
        .when(roleRepository.existsByUserIdAndRole(any(), eq(UserRole.SUPER_ADMIN)))
        .thenReturn(false);
    lenient()
        .when(cycleRepository.save(any()))
        .thenAnswer(
            i -> {
              CycleTontine c = i.getArgument(0);
              if (c.getId() == null) c.setId(UUID.randomUUID());
              return c;
            });
    lenient().when(gagnantRepository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  private CycleTontine cycleConnu(int numero, CycleStatut statut) {
    CycleTontine c = Fixtures.cycle(tontine, numero, statut);
    lenient()
        .when(cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(c.getId(), tontine.getId()))
        .thenReturn(Optional.of(c));
    return c;
  }

  private Cotisation cotisation(
      TontineMembre m, CycleTontine c, CotisationStatut statut, String montant) {
    Cotisation co = Fixtures.cotisation(m, c, statut);
    co.setMontant(new BigDecimal(montant));
    return co;
  }

  private TontineCommission commission(CommissionType type, String valeur) {
    return TontineCommission.builder()
        .id(UUID.randomUUID())
        .tontine(tontine)
        .type(type)
        .valeur(new BigDecimal(valeur))
        .build();
  }

  private List<TontineMembre> membresActifs(int n) {
    List<TontineMembre> membres = new ArrayList<>();
    for (int i = 1; i <= n; i++) membres.add(Fixtures.membre(tontine, Fixtures.user("M" + i), i));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
                tontine.getId(), MembreStatut.ACTIF))
        .thenReturn(membres);
    return membres;
  }

  @Nested
  @DisplayName("lecture")
  class Lecture {

    @Test
    @DisplayName("liste, détail et historique des bénéficiaires pour un membre ; tiers -> 403")
    void lecture() throws Exception {
      User awa = Fixtures.user("Awa");
      when(userRepository.findByKeycloakId(awa.getKeycloakId())).thenReturn(Optional.of(awa));
      when(membreRepository.existsByTontine_IdAndUser_IdAndDeletedAtIsNull(
              tontine.getId(), awa.getId()))
          .thenReturn(true);
      CycleTontine c = cycleConnu(1, CycleStatut.TERMINE);
      PageRequest page = PageRequest.of(0, 20);
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId(), page))
          .thenReturn(new PageImpl<>(List.of(c)));
      when(cycleRepository.findTerminesAvecGagnants(tontine.getId(), CycleStatut.TERMINE, page))
          .thenReturn(new PageImpl<>(List.of(c)));

      assertThat(service.listCycles(awa.getKeycloakId(), tontine.getId(), page).getContent())
          .hasSize(1);
      assertThat(service.getCycle(awa.getKeycloakId(), tontine.getId(), c.getId()).getNumeroCycle())
          .isEqualTo(1);
      assertThat(
              service
                  .listBeneficiaires(awa.getKeycloakId(), tontine.getId(), page)
                  .getContent()
                  .get(0)
                  .getCycleId())
          .isEqualTo(c.getId());

      assertThatThrownBy(() -> service.listCycles(etranger.getKeycloakId(), tontine.getId(), page))
          .isInstanceOf(ForbiddenException.class);
      UUID inconnu = UUID.randomUUID();
      when(cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(inconnu, tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.getCycle(kc, tontine.getId(), inconnu))
          .isInstanceOf(NotFoundException.class);
    }
  }

  @Nested
  @DisplayName("ouverture manuelle")
  class Ouverture {

    private OpenCycleRequest req(LocalDate debut, LocalDate fin) {
      OpenCycleRequest r = new OpenCycleRequest();
      r.setDateDebut(debut);
      r.setDateFin(fin);
      return r;
    }

    @Test
    @DisplayName("numéro suivant, EN_COURS, audité, chaque membre actif notifié (CYCLE_OUVERT)")
    void succes() throws Exception {
      membresActifs(3);
      when(cycleRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CycleStatut.EN_COURS))
          .thenReturn(Optional.empty());
      when(cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
              tontine.getId()))
          .thenReturn(Optional.of(Fixtures.cycle(tontine, 4, CycleStatut.TERMINE)));

      CycleResponse r =
          service.openCycle(
              kc, tontine.getId(), req(LocalDate.now(), LocalDate.now().plusDays(30)));

      assertThat(r.getNumeroCycle()).isEqualTo(5);
      assertThat(r.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      verify(auditService).logCreate(eq(gestionnaire), eq("cycles_tontine"), any());
      verify(notificationService, times(3))
          .notify(
              any(),
              eq(NotificationType.CYCLE_OUVERT),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
    }

    @Test
    @DisplayName("premier cycle numéroté 1")
    void premier() throws Exception {
      membresActifs(0);
      when(cycleRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CycleStatut.EN_COURS))
          .thenReturn(Optional.empty());
      when(cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
              tontine.getId()))
          .thenReturn(Optional.empty());
      assertThat(
              service
                  .openCycle(kc, tontine.getId(), req(LocalDate.now(), LocalDate.now()))
                  .getNumeroCycle())
          .isEqualTo(1);
    }

    @Test
    @DisplayName("sans dateFin (B26) : calculée selon le rythme, échéance créée avec le tour")
    void dateFinCalculee() throws Exception {
      membresActifs(0);
      tontine.appliquerRythme(new Rythme(1, UniteRythme.SEMAINE, "DIMANCHE", LocalTime.of(20, 0)));
      when(cycleRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CycleStatut.EN_COURS))
          .thenReturn(Optional.empty());
      CycleTontine precedent = Fixtures.cycle(tontine, 1, CycleStatut.TERMINE);
      precedent.setDateFin(LocalDate.of(2026, 11, 15));
      when(cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
              tontine.getId()))
          .thenReturn(Optional.of(precedent));
      ArgumentCaptor<CycleTontine> enregistre = ArgumentCaptor.forClass(CycleTontine.class);

      CycleResponse r =
          service.openCycle(kc, tontine.getId(), req(LocalDate.of(2026, 11, 16), null));

      assertThat(r.getDateFin()).isEqualTo(LocalDate.of(2026, 11, 22));
      assertThat(r.getEcheanceAt())
          .isEqualTo(OffsetDateTime.of(2026, 11, 22, 20, 0, 0, 0, ZoneOffset.UTC));
      verify(cycleRepository).save(enregistre.capture());
      assertThat(enregistre.getValue().getEcheances()).hasSize(1);
    }

    @Test
    @DisplayName("B32 : 4 par tour → 4 échéances ; date de fin plus courte → moins d'échéances")
    void quatreEcheances() throws Exception {
      membresActifs(0);
      tontine.appliquerRythme(new Rythme(1, UniteRythme.SEMAINE, "DIMANCHE", LocalTime.of(20, 0)));
      tontine.setCotisationsParTour(4);
      when(cycleRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CycleStatut.EN_COURS))
          .thenReturn(Optional.empty());
      when(cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
              tontine.getId()))
          .thenReturn(Optional.empty());
      LocalDate mercredi = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY));

      CycleResponse complet = service.openCycle(kc, tontine.getId(), req(mercredi, null));
      assertThat(complet.getNombreEcheances()).isEqualTo(4);
      assertThat(complet.getEcheances()).hasSize(4);
      assertThat(complet.getEcheances().getFirst().getStatut()).isEqualTo(StatutEcheance.A_VENIR);
      assertThat(complet.getEcheanceEnCours()).isNull();
      assertThat(complet.getDateFin()).isEqualTo(mercredi.plusDays(4).plusWeeks(3));

      CycleResponse court =
          service.openCycle(kc, tontine.getId(), req(mercredi, mercredi.plusDays(9)));
      assertThat(court.getNombreEcheances()).isEqualTo(2);
      assertThat(court.getDateFin()).isEqualTo(mercredi.plusDays(9));
    }

    @Test
    @DisplayName(
        "proposition de tour (MANUEL) : N échéances sans effet en base ; refus hors MANUEL")
    void proposition() throws Exception {
      tontine.appliquerRythme(new Rythme(1, UniteRythme.SEMAINE, "DIMANCHE", LocalTime.of(20, 0)));
      tontine.setCotisationsParTour(4);
      when(cycleRepository.findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
              tontine.getId()))
          .thenReturn(Optional.empty());

      PropositionTourResponse p =
          service.proposerTour(kc, tontine.getId(), LocalDate.of(2026, 11, 11));
      assertThat(p.getNombreEcheances()).isEqualTo(4);
      assertThat(p.getDateFin()).isEqualTo(LocalDate.of(2026, 12, 6));
      assertThat(p.getEcheances().get(0))
          .isEqualTo(OffsetDateTime.of(2026, 11, 15, 20, 0, 0, 0, ZoneOffset.UTC));
      assertThat(service.proposerTour(kc, tontine.getId(), null).getDateDebut())
          .isEqualTo(LocalDate.now());
      verify(cycleRepository, never()).save(any());

      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      assertThatThrownBy(() -> service.proposerTour(kc, tontine.getId(), null))
          .isInstanceOf(BadRequestException.class);
      tontine.setModeCycle(ModeCycle.MANUEL);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(() -> service.proposerTour(kc, tontine.getId(), null))
          .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName(
        "refus : cycle déjà en cours (CYCLE_ALREADY_IN_PROGRESS), dates inversées, statut, mode, type, tiers")
    void refus() {
      OpenCycleRequest ok = req(LocalDate.now(), LocalDate.now().plusDays(1));
      when(cycleRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CycleStatut.EN_COURS))
          .thenReturn(Optional.of(Fixtures.cycle(tontine, 1, CycleStatut.EN_COURS)));
      assertThatThrownBy(() -> service.openCycle(kc, tontine.getId(), ok))
          .isInstanceOfSatisfying(
              BadRequestException.class,
              e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CYCLE_ALREADY_IN_PROGRESS));

      assertThatThrownBy(
              () ->
                  service.openCycle(
                      kc, tontine.getId(), req(LocalDate.now(), LocalDate.now().minusDays(1))))
          .isInstanceOf(BadRequestException.class);
      tontine.setStatut(TontineStatut.SUSPENDUE);
      assertThatThrownBy(() -> service.openCycle(kc, tontine.getId(), ok))
          .isInstanceOf(BadRequestException.class);
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      assertThatThrownBy(() -> service.openCycle(kc, tontine.getId(), ok))
          .isInstanceOf(BadRequestException.class);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(() -> service.openCycle(kc, tontine.getId(), ok))
          .isInstanceOf(BadRequestException.class);
      assertThatThrownBy(() -> service.openCycle(etranger.getKeycloakId(), tontine.getId(), ok))
          .isInstanceOf(ForbiddenException.class);
      verify(cycleRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("clôture ROTATIVE")
  class ClotureRotative {

    @Test
    @DisplayName(
        "jackpot = VALIDE, commissions (10 % + 200 fixes, adhésion ignorée), gagnant ROTATION payé, retards signalés")
    void calculs() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      membres.get(0).setARecuJackpot(true);
      CycleTontine cycle = cycleConnu(2, CycleStatut.EN_COURS);
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              cycle.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(membres.get(0), cycle, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(1), cycle, CotisationStatut.VALIDE, "1000")));
      Cotisation enAttente = cotisation(membres.get(2), cycle, CotisationStatut.EN_ATTENTE, "1000");
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              cycle.getId(), CotisationStatut.EN_ATTENTE))
          .thenReturn(List.of(enAttente));
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(
              List.of(
                  commission(CommissionType.POURCENTAGE_JACKPOT, "10"),
                  commission(CommissionType.FRAIS_FIXES_PAR_CYCLE, "200"),
                  commission(CommissionType.FRAIS_ADHESION, "5000")));

      CycleResponse r = service.closeCycle(kc, tontine.getId(), cycle.getId());

      assertThat(r.getStatut()).isEqualTo(CycleStatut.TERMINE);
      assertThat(r.getMontantJackpot()).isEqualByComparingTo("2000");
      assertThat(r.getMontantCommission()).isEqualByComparingTo("400");
      assertThat(r.getMontantNet()).isEqualByComparingTo("1600");
      assertThat(r.getDateRemise()).isEqualTo(LocalDate.now());
      assertThat(r.getGagnants())
          .singleElement()
          .satisfies(
              g -> {
                assertThat(g.getMembreId()).isEqualTo(membres.get(1).getId());
                assertThat(g.getMontantRecu()).isEqualByComparingTo("1600");
              });
      assertThat(membres.get(1).isARecuJackpot()).isTrue();
      assertThat(membres.get(1).getDateJackpot()).isNotNull();
      assertThat(enAttente.getStatut()).isEqualTo(CotisationStatut.EN_RETARD);
      verify(schedulerService).annoncerGagnants(eq(cycle), any());
      verify(notificationService, times(2))
          .notify(
              any(),
              eq(NotificationType.JACKPOT_DISTRIBUE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.PAIEMENT_EN_RETARD),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(auditService)
          .log(gestionnaire, "cycles_tontine", cycle.getId(), "statut", "EN_COURS", "TERMINE");
    }

    @Test
    @DisplayName(
        "2 gagnants : net partagé en francs entiers, somme exacte (3 001 -> 1 501 + 1 500) ;"
            + " mode AUTOMATIQUE : cycle suivant activé")
    void plusieursGagnantsEtSuivant() throws Exception {
      tontine.setNombreGagnants(2);
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      membresActifs(3);
      CycleTontine cycle = cycleConnu(1, CycleStatut.EN_COURS);
      CycleTontine suivant = Fixtures.cycle(tontine, 2, CycleStatut.EN_ATTENTE);
      CycleTontine apres = Fixtures.cycle(tontine, 3, CycleStatut.EN_ATTENTE);
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              cycle.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(
                      Fixtures.membre(tontine, gestionnaire, 9),
                      cycle,
                      CotisationStatut.VALIDE,
                      "3001")));
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(cycle, suivant, apres));

      CycleResponse r = service.closeCycle(kc, tontine.getId(), cycle.getId());

      assertThat(r.getGagnants())
          .extracting(CycleResponse.GagnantInfo::getMontantRecu)
          .usingElementComparator(BigDecimal::compareTo)
          .containsExactly(new BigDecimal("1501"), new BigDecimal("1500"));
      assertThat(suivant.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      assertThat(apres.getStatut()).isEqualTo(CycleStatut.EN_ATTENTE);
    }

    @Test
    @DisplayName("ordre ALEATOIRE : un gagnant éligible tiré ; MANUEL : aucun gagnant à la clôture")
    void aleatoireEtManuel() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      tontine.setOrdreBeneficiaire("ALEATOIRE");
      CycleTontine c1 = cycleConnu(1, CycleStatut.EN_COURS);
      CycleResponse r = service.closeCycle(kc, tontine.getId(), c1.getId());
      assertThat(r.getGagnants()).hasSize(1);
      assertThat(membres.stream().filter(TontineMembre::isARecuJackpot)).hasSize(1);

      tontine.setOrdreBeneficiaire("MANUEL");
      CycleTontine c2 = cycleConnu(2, CycleStatut.EN_COURS);
      assertThat(service.closeCycle(kc, tontine.getId(), c2.getId()).getGagnants()).isEmpty();
      verify(schedulerService, times(1)).annoncerGagnants(any(), any());
    }

    @Test
    @DisplayName(
        "PAIEMENT_EN_RETARD : « déclaré à temps, non validé » si déclarée avant l'échéance,"
            + " « n'a pas cotisé » sinon")
    void texteRetardSelonPonctualite() throws Exception {
      List<TontineMembre> membres = membresActifs(2);
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      Cotisation aTemps = cotisation(membres.get(0), c, CotisationStatut.EN_ATTENTE, "1000");
      Cotisation tardive = cotisation(membres.get(1), c, CotisationStatut.EN_ATTENTE, "1000");
      tardive.setCreatedAt(c.getDateFin().plusDays(1).atTime(8, 0));
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              c.getId(), CotisationStatut.VALIDE))
          .thenReturn(List.of());
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              c.getId(), CotisationStatut.EN_ATTENTE))
          .thenReturn(List.of(aTemps, tardive));

      service.closeCycle(kc, tontine.getId(), c.getId());

      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.PAIEMENT_EN_RETARD),
              anyString(),
              org.mockito.ArgumentMatchers.contains("déclaré son paiement à temps"),
              eq(tontine.getId()),
              any());
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.PAIEMENT_EN_RETARD),
              anyString(),
              org.mockito.ArgumentMatchers.contains("n'a pas cotisé"),
              eq(tontine.getId()),
              any());
      assertThat(aTemps.getStatut()).isEqualTo(CotisationStatut.EN_RETARD);
    }

    @Test
    @DisplayName("tous les membres déjà servis : aucun gagnant ; cycle non EN_COURS -> 400")
    void casLimites() throws Exception {
      membresActifs(2).forEach(m -> m.setARecuJackpot(true));
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      assertThat(service.closeCycle(kc, tontine.getId(), c.getId()).getGagnants()).isEmpty();
      verify(gagnantRepository, never()).save(any());

      assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), c.getId()))
          .isInstanceOf(BadRequestException.class);
    }
  }

  @Nested
  @DisplayName("clôture EVENEMENTIELLE")
  class ClotureEvenementielle {

    @BeforeEach
    void evenementielle() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
    }

    @Test
    @DisplayName(
        "sous-cycle intermédiaire : sans commission ni distribution, sous-cycle suivant activé")
    void intermediaire() throws Exception {
      List<TontineMembre> membres = membresActifs(1);
      CycleTontine c1 = cycleConnu(1, CycleStatut.EN_COURS);
      CycleTontine c2 = Fixtures.cycle(tontine, 2, CycleStatut.EN_ATTENTE);
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              c1.getId(), CotisationStatut.VALIDE))
          .thenReturn(List.of(cotisation(membres.getFirst(), c1, CotisationStatut.VALIDE, "700")));
      Cotisation enAttente = cotisation(membres.getFirst(), c1, CotisationStatut.EN_ATTENTE, "700");
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              c1.getId(), CotisationStatut.EN_ATTENTE))
          .thenReturn(List.of(enAttente));
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c1, c2));

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c1.getId());

      assertThat(r.getMontantJackpot()).isEqualByComparingTo("700");
      assertThat(r.getMontantCommission()).isEqualByComparingTo("0");
      assertThat(r.getDistributionParMembre()).isNull();
      assertThat(c2.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      assertThat(enAttente.getStatut()).isEqualTo(CotisationStatut.EN_RETARD);
      verify(whatsappService, never()).sendDistributionFinale(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "dernier sous-cycle : distribution proportionnelle (10 % + 300 fixes), WhatsApp et notifications")
    void finale() throws Exception {
      List<TontineMembre> membres = membresActifs(2);
      CycleTontine c1 = Fixtures.cycle(tontine, 1, CycleStatut.TERMINE);
      CycleTontine c2 = cycleConnu(2, CycleStatut.EN_COURS);
      c2.setDateFin(LocalDate.now()); // B45 : dernier sous-cycle clôturé le jour de sa fin
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c1, c2));
      TontineMembre sorti = Fixtures.membre(tontine, Fixtures.user("Sorti"), 9);
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(membres.get(0), c1, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(0), c2, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(1), c1, CotisationStatut.VALIDE, "1000"),
                  cotisation(sorti, c1, CotisationStatut.VALIDE, "5000")));
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(
              List.of(
                  commission(CommissionType.POURCENTAGE_JACKPOT, "10"),
                  commission(CommissionType.FRAIS_FIXES_PAR_CYCLE, "300"),
                  commission(CommissionType.FRAIS_ADHESION, "1000")));
      doThrow(new RuntimeException("WhatsApp KO"))
          .when(whatsappService)
          .sendDistributionFinaleAdmin(any(), any(), any(), anyInt());

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c2.getId());

      // B43 : le membre absent des membres actifs reçoit aussi sa part
      assertThat(r.getDistributionParMembre()).hasSize(3);
      CycleResponse.MembreDistributionInfo awa =
          r.getDistributionParMembre().stream()
              .filter(d -> d.getMembreId().equals(membres.getFirst().getId()))
              .findFirst()
              .orElseThrow();
      assertThat(awa.getMontantCotise()).isEqualByComparingTo("2000");
      // 10 % de 2000 = 200 ; frais fixes : 300 × 2000 / 8000 (cagnotte, sortant compris) = 75
      assertThat(awa.getMontantCommission()).isEqualByComparingTo("275");
      assertThat(awa.getMontantNet()).isEqualByComparingTo("1725");
      assertThat(r.getMontantJackpot()).isEqualByComparingTo("8000");

      // V034 : une ligne par membre distribué, montants identiques à la réponse
      ArgumentCaptor<List<DistributionEvenementielle>> lignes = ArgumentCaptor.forClass(List.class);
      verify(distributionRepository).saveAll(lignes.capture());
      assertThat(lignes.getValue()).hasSize(3);
      DistributionEvenementielle ligneAwa =
          lignes.getValue().stream()
              .filter(l -> l.getMontantCotise().compareTo(new BigDecimal("2000")) == 0)
              .findFirst()
              .orElseThrow();
      assertThat(ligneAwa.getMontantCommission()).isEqualByComparingTo("275");
      assertThat(ligneAwa.getMontantNet()).isEqualByComparingTo("1725");
      assertThat(ligneAwa.getTontine()).isSameAs(tontine);
      assertThat(ligneAwa.getCycle()).isSameAs(c2);
      verify(whatsappService, times(3))
          .sendDistributionFinale(any(), eq(tontine.getNom()), any(), any());
      verify(notificationService, times(4))
          .notify(
              any(),
              eq(NotificationType.DISTRIBUTION_FINALE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
    }

    @Test
    @DisplayName(
        "distribution : commissions en francs entiers, somme exacte (100 F / 3 -> 34+33+33)")
    void distributionSommeExacte() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      CycleTontine c1 = cycleConnu(1, CycleStatut.EN_COURS);
      c1.setDateFin(LocalDate.now()); // B45 : dernier sous-cycle clôturé le jour de sa fin
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c1));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              membres.stream()
                  .map(m -> cotisation(m, c1, CotisationStatut.VALIDE, "1000"))
                  .toList());
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of(commission(CommissionType.FRAIS_FIXES_PAR_CYCLE, "100")));

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c1.getId());

      assertThat(r.getDistributionParMembre())
          .extracting(CycleResponse.MembreDistributionInfo::getMontantCommission)
          .usingElementComparator(BigDecimal::compareTo)
          .containsExactly(new BigDecimal("34"), new BigDecimal("33"), new BigDecimal("33"));
      assertThat(r.getMontantCommission()).isEqualByComparingTo("100");
      assertThat(r.getMontantNet()).isEqualByComparingTo("2900");
    }

    @Test
    @DisplayName("distribution déjà enregistrée pour la tontine : aucune seconde écriture")
    void distributionDejaEnregistree() throws Exception {
      List<TontineMembre> membres = membresActifs(1);
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      c.setDateFin(LocalDate.now()); // B45 : dernier sous-cycle clôturé le jour de sa fin
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(List.of(cotisation(membres.get(0), c, CotisationStatut.VALIDE, "500")));
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(true);

      assertThat(service.closeCycle(kc, tontine.getId(), c.getId()).getDistributionParMembre())
          .hasSize(1);
      verify(distributionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName(
        "dernier sous-cycle sans aucune cotisation : distribution vide, gestionnaire notifié")
    void finaleVide() throws Exception {
      membresActifs(1);
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      c.setDateFin(LocalDate.now()); // B45 : dernier sous-cycle clôturé le jour de sa fin
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c));
      doThrow(new RuntimeException("KO"))
          .when(whatsappService)
          .sendDistributionFinaleAdmin(any(), any(), any(), anyInt());

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c.getId());

      assertThat(r.getDistributionParMembre()).isEmpty();
      verify(distributionRepository, never()).saveAll(any());
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.DISTRIBUTION_FINALE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
    }
  }

  @Nested
  @DisplayName("ordre HYBRIDE : tirage au sort ou choix, tour par tour")
  class Hybride {

    @BeforeEach
    void hybride() {
      tontine.setOrdreBeneficiaire("HYBRIDE");
    }

    @Test
    @DisplayName("clôture : aucun gagnant désigné automatiquement")
    void cloture() throws Exception {
      membresActifs(3);
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      assertThat(service.closeCycle(kc, tontine.getId(), c.getId()).getGagnants()).isEmpty();
      verify(gagnantRepository, never()).save(any());
      verify(schedulerService, never()).annoncerGagnants(any(), any());
    }

    @Test
    @DisplayName("après clôture : tirage au sort (liste vide) puis choix explicite au tour suivant")
    void tirageEtChoix() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      CycleTontine tour1 = cycleConnu(1, CycleStatut.TERMINE);
      tour1.setMontantNet(new BigDecimal("3000"));
      SelectionnerBeneficiaireRequest tirage = new SelectionnerBeneficiaireRequest();

      CycleResponse r1 =
          service.selectionnerBeneficiaire(kc, tontine.getId(), tour1.getId(), tirage);
      assertThat(r1.getGagnants()).hasSize(1);
      assertThat(r1.getGagnants().get(0).getMontantRecu()).isEqualByComparingTo("3000");

      TontineMembre restant =
          membres.stream().filter(m -> !m.isARecuJackpot()).findFirst().orElseThrow();
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
              restant.getId(), tontine.getId()))
          .thenReturn(Optional.of(restant));
      CycleTontine tour2 = cycleConnu(2, CycleStatut.TERMINE);
      tour2.setMontantNet(new BigDecimal("3000"));
      SelectionnerBeneficiaireRequest choix = new SelectionnerBeneficiaireRequest();
      choix.setMembreIds(List.of(restant.getId()));

      CycleResponse r2 =
          service.selectionnerBeneficiaire(kc, tontine.getId(), tour2.getId(), choix);
      assertThat(r2.getGagnants())
          .extracting(CycleResponse.GagnantInfo::getMembreId)
          .containsExactly(restant.getId());
    }

    @Test
    @DisplayName("ROTATION et ALEATOIRE : désignation après clôture refusée")
    void autresOrdresRefuses() {
      CycleTontine c = cycleConnu(1, CycleStatut.TERMINE);
      for (String ordre : new String[] {"ROTATION", "ALEATOIRE"}) {
        tontine.setOrdreBeneficiaire(ordre);
        assertThatThrownBy(
                () ->
                    service.selectionnerBeneficiaire(
                        kc, tontine.getId(), c.getId(), new SelectionnerBeneficiaireRequest()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("HYBRIDE");
      }
    }
  }

  @Nested
  @DisplayName("désignation des gagnants (MANUEL)")
  class Designation {

    private CycleTontine cycle;

    @BeforeEach
    void manuel() {
      tontine.setOrdreBeneficiaire("MANUEL");
      tontine.setNombreGagnants(2);
      cycle = cycleConnu(1, CycleStatut.TERMINE);
      cycle.setMontantNet(new BigDecimal("3000"));
    }

    private SelectionnerBeneficiaireRequest choix(UUID... ids) {
      SelectionnerBeneficiaireRequest r = new SelectionnerBeneficiaireRequest();
      r.setMembreIds(ids.length == 0 ? null : List.of(ids));
      return r;
    }

    private TontineMembre connu(TontineMembre m) {
      lenient()
          .when(
              membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(m.getId(), tontine.getId()))
          .thenReturn(Optional.of(m));
      return m;
    }

    @Test
    @DisplayName(
        "choix explicite : net partagé, membres marqués, audit, annonce, WhatsApp admin non bloquant")
    void explicite() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      connu(membres.get(0));
      connu(membres.get(2));
      doThrow(new RuntimeException("KO"))
          .when(whatsappService)
          .sendJackpotManuelAdmin(any(), any(), anyInt(), any());

      CycleResponse r =
          service.selectionnerBeneficiaire(
              kc,
              tontine.getId(),
              cycle.getId(),
              choix(membres.get(0).getId(), membres.get(2).getId()));

      assertThat(r.getGagnants())
          .extracting(CycleResponse.GagnantInfo::getMembreId)
          .containsExactly(membres.get(0).getId(), membres.get(2).getId());
      assertThat(r.getGagnants())
          .allSatisfy(g -> assertThat(g.getMontantRecu()).isEqualByComparingTo("1500"));
      assertThat(membres.get(0).isARecuJackpot()).isTrue();
      verify(auditService, times(2))
          .log(
              eq(gestionnaire),
              eq("cycles_tontine"),
              eq(cycle.getId()),
              eq("gagnant_ajoute"),
              any(),
              any());
      verify(schedulerService).annoncerGagnants(eq(cycle), any());
    }

    @Test
    @DisplayName("liste vide : tirage aléatoire de nombreGagnants parmi les éligibles")
    void tirage() throws Exception {
      List<TontineMembre> membres = membresActifs(4);
      membres.get(0).setARecuJackpot(true);

      CycleResponse r =
          service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix());

      assertThat(r.getGagnants())
          .hasSize(2)
          .noneMatch(g -> g.getMembreId().equals(membres.get(0).getId()));
    }

    @Test
    @DisplayName("refus : doublon, membre non actif, déjà servi, inconnu")
    void refusMembres() {
      List<TontineMembre> membres = membresActifs(3);
      UUID id0 = connu(membres.get(0)).getId();
      assertThatThrownBy(
              () ->
                  service.selectionnerBeneficiaire(
                      kc, tontine.getId(), cycle.getId(), choix(id0, id0)))
          .isInstanceOf(BadRequestException.class);

      TontineMembre suspendu = connu(Fixtures.membre(tontine, Fixtures.user("S"), 8));
      suspendu.setStatut(MembreStatut.SUSPENDU);
      assertThatThrownBy(
              () ->
                  service.selectionnerBeneficiaire(
                      kc, tontine.getId(), cycle.getId(), choix(suspendu.getId())))
          .isInstanceOf(BadRequestException.class);

      TontineMembre servi = connu(membres.get(1));
      servi.setARecuJackpot(true);
      assertThatThrownBy(
              () ->
                  service.selectionnerBeneficiaire(
                      kc, tontine.getId(), cycle.getId(), choix(servi.getId())))
          .isInstanceOf(BadRequestException.class);

      UUID inconnu = UUID.randomUUID();
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(inconnu, tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.selectionnerBeneficiaire(
                      kc, tontine.getId(), cycle.getId(), choix(inconnu)))
          .isInstanceOf(NotFoundException.class);
      verify(gagnantRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "refus : déjà désignés, aucun éligible, ordre non MANUEL, cycle non terminé, événementielle")
    void refusContexte() {
      when(gagnantRepository.existsByCycle_IdAndDeletedAtIsNull(cycle.getId())).thenReturn(true);
      assertThatThrownBy(
              () -> service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix()))
          .isInstanceOf(BadRequestException.class);

      when(gagnantRepository.existsByCycle_IdAndDeletedAtIsNull(cycle.getId())).thenReturn(false);
      membresActifs(1).get(0).setARecuJackpot(true);
      assertThatThrownBy(
              () -> service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix()))
          .isInstanceOf(BadRequestException.class);

      tontine.setOrdreBeneficiaire("ROTATION");
      assertThatThrownBy(
              () -> service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix()))
          .isInstanceOf(BadRequestException.class);
      cycle.setStatut(CycleStatut.EN_COURS);
      assertThatThrownBy(
              () -> service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix()))
          .isInstanceOf(BadRequestException.class);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(
              () -> service.selectionnerBeneficiaire(kc, tontine.getId(), cycle.getId(), choix()))
          .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("gagnants supprimés logiquement absents de la réponse du cycle")
    void gagnantsSupprimesFiltres() throws Exception {
      CycleGagnant actif =
          CycleGagnant.builder()
              .cycle(cycle)
              .membre(Fixtures.membre(tontine, Fixtures.user("A"), 1))
              .montantRecu(BigDecimal.TEN)
              .build();
      CycleGagnant supprime =
          CycleGagnant.builder()
              .cycle(cycle)
              .membre(Fixtures.membre(tontine, Fixtures.user("B"), 2))
              .montantRecu(BigDecimal.TEN)
              .build();
      supprime.setDeletedAt(LocalDateTime.now());
      cycle.setGagnants(new ArrayList<>(List.of(actif, supprime)));
      assertThat(service.getCycle(kc, tontine.getId(), cycle.getId()).getGagnants()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("priorités de jackpot : un membre dans le besoin passe en premier")
  class Priorites {

    @Test
    @DisplayName("ROTATION : le prioritaire gagne avant l'ordre de passage ; priorité servie")
    void rotation() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      when(prioriteService.prioritairesEligibles(tontine.getId()))
          .thenReturn(List.of(membres.get(2)));
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c.getId());

      assertThat(r.getGagnants())
          .extracting(CycleResponse.GagnantInfo::getMembreId)
          .containsExactly(membres.get(2).getId());
      verify(prioriteService).marquerServies(c, List.of(membres.get(2)));
    }

    @Test
    @DisplayName("ALEATOIRE, 2 gagnants : le prioritaire + un tiré au sort parmi les autres")
    void aleatoire() throws Exception {
      List<TontineMembre> membres = membresActifs(4);
      tontine.setOrdreBeneficiaire("ALEATOIRE");
      tontine.setNombreGagnants(2);
      when(prioriteService.prioritairesEligibles(tontine.getId()))
          .thenReturn(List.of(membres.get(3)));
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c.getId());

      assertThat(r.getGagnants()).hasSize(2);
      assertThat(r.getGagnants().get(0).getMembreId()).isEqualTo(membres.get(3).getId());
      assertThat(r.getGagnants().get(1).getMembreId()).isNotEqualTo(membres.get(3).getId());
    }

    @Test
    @DisplayName("HYBRIDE : clôture -> GAGNANTS_A_DESIGNER au gestionnaire ; tirage -> prioritaire")
    void hybride() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      tontine.setOrdreBeneficiaire("HYBRIDE");
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);

      assertThat(service.closeCycle(kc, tontine.getId(), c.getId()).getGagnants()).isEmpty();
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.GAGNANTS_A_DESIGNER),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(prioriteService, never()).prioritairesEligibles(any());

      c.setMontantNet(new BigDecimal("3000"));
      when(prioriteService.prioritairesEligibles(tontine.getId()))
          .thenReturn(List.of(membres.get(1)));
      CycleResponse r =
          service.selectionnerBeneficiaire(
              kc, tontine.getId(), c.getId(), new SelectionnerBeneficiaireRequest());
      assertThat(r.getGagnants())
          .extracting(CycleResponse.GagnantInfo::getMembreId)
          .containsExactly(membres.get(1).getId());
      verify(prioriteService).marquerServies(c, List.of(membres.get(1)));
    }

    @Test
    @DisplayName("HYBRIDE sans éligible : pas de notification GAGNANTS_A_DESIGNER")
    void hybrideSansEligible() throws Exception {
      membresActifs(2).forEach(m -> m.setARecuJackpot(true));
      tontine.setOrdreBeneficiaire("HYBRIDE");
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);

      service.closeCycle(kc, tontine.getId(), c.getId());

      verify(notificationService, never())
          .notify(
              any(),
              eq(NotificationType.GAGNANTS_A_DESIGNER),
              anyString(),
              anyString(),
              any(),
              any());
    }

    @Test
    @DisplayName("choix explicite du gestionnaire : priorités des membres choisis servies")
    void choixExplicite() throws Exception {
      List<TontineMembre> membres = membresActifs(2);
      tontine.setOrdreBeneficiaire("MANUEL");
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
              membres.get(0).getId(), tontine.getId()))
          .thenReturn(Optional.of(membres.get(0)));
      CycleTontine c = cycleConnu(1, CycleStatut.TERMINE);
      c.setMontantNet(new BigDecimal("2000"));
      SelectionnerBeneficiaireRequest choix = new SelectionnerBeneficiaireRequest();
      choix.setMembreIds(List.of(membres.get(0).getId()));

      service.selectionnerBeneficiaire(kc, tontine.getId(), c.getId(), choix);

      verify(prioriteService).marquerServies(c, List.of(membres.get(0)));
      verify(prioriteService, never()).prioritairesEligibles(any());
    }
  }

  @Nested
  @DisplayName("remise dès que tous ont payé (B32, TOUS_PAYES)")
  class RemiseTousPayes {

    private CycleTontine tour;

    @BeforeEach
    void tousPayes() {
      tontine.setDeclenchementJackpot(DeclenchementJackpot.TOUS_PAYES);
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      tour = cycleConnu(2, CycleStatut.EN_COURS);
      lenient()
          .when(suiviTour.etat(tour))
          .thenReturn(new EtatTour(3, 1, new BigDecimal("3000"), true, null, 1, 3, 0));
    }

    @Test
    @DisplayName("mode AUTOMATIQUE : le tour est clôturé, les gagnants désignés, le suivant activé")
    void automatique() throws Exception {
      List<TontineMembre> membres = membresActifs(3);
      when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
              tour.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(membres.get(0), tour, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(1), tour, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(2), tour, CotisationStatut.VALIDE, "1000")));

      Optional<CycleResponse> r = service.remettreSiTousPayes(kc, tontine.getId(), tour.getId());

      assertThat(r).isPresent();
      assertThat(r.get().getStatut()).isEqualTo(CycleStatut.TERMINE);
      assertThat(r.get().getMontantJackpot()).isEqualByComparingTo("3000");
      assertThat(r.get().getGagnants()).hasSize(1);
      assertThat(tour.getStatut()).isEqualTo(CycleStatut.TERMINE);
    }

    @Test
    @DisplayName("mode MANUEL : pas de clôture, le gestionnaire est notifié JACKPOT_PRET")
    void manuel() throws Exception {
      tontine.setModeCycle(ModeCycle.MANUEL);

      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      assertThat(tour.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.JACKPOT_PRET),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
    }

    @Test
    @DisplayName("il manque un paiement : rien ne se passe")
    void pasTousPayes() throws Exception {
      when(suiviTour.etat(tour))
          .thenReturn(new EtatTour(3, 1, new BigDecimal("3000"), false, null, 1, 2, 1));

      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      assertThat(tour.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "FIN_DU_TOUR, événementielle, tontine suspendue ou tour terminé : rien ne se passe")
    void horsPerimetre() throws Exception {
      tontine.setDeclenchementJackpot(DeclenchementJackpot.FIN_DU_TOUR);
      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      tontine.setDeclenchementJackpot(DeclenchementJackpot.TOUS_PAYES);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      tontine.setTontineType(TontineType.ROTATIVE);
      tontine.setStatut(TontineStatut.SUSPENDUE);
      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      tontine.setStatut(TontineStatut.ACTIVE);
      tour.setStatut(CycleStatut.TERMINE);
      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isEmpty();

      verify(cycleRepository, never()).save(any());
    }

    @Test
    @DisplayName("réponse d'un tour : jackpot prévu et « tous payés » repris du suivi")
    void reponseEnrichie() throws Exception {
      CycleResponse r = service.getCycle(kc, tontine.getId(), tour.getId());

      assertThat(r.getMontantJackpotPrevu()).isEqualByComparingTo("3000");
      assertThat(r.isTousPayes()).isTrue();
    }
  }

  @Nested
  @DisplayName("options de remise à la clôture (B33)")
  class OptionsDeRemise {

    private CycleTontine tour;
    private List<TontineMembre> membres;

    private CloturerCycleRequest avec(ModeRemise mode) {
      CloturerCycleRequest r = new CloturerCycleRequest();
      r.setModeRemise(mode);
      return r;
    }

    /** 4 membres à 1 000 F, 3 payés, commission 10 % : prévu 4 000 F, collecté 3 000 F. */
    @BeforeEach
    void troisPayesSurQuatre() {
      membres = membresActifs(4);
      tour = cycleConnu(1, CycleStatut.EN_COURS);
      lenient()
          .when(suiviTour.etat(tour))
          .thenReturn(new EtatTour(4, 1, new BigDecimal("4000"), false, null, 1, 3, 0));
      lenient()
          .when(
              cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
                  tour.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(membres.get(0), tour, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(1), tour, CotisationStatut.VALIDE, "1000"),
                  cotisation(membres.get(2), tour, CotisationStatut.VALIDE, "1000")));
      lenient()
          .when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of(commission(CommissionType.POURCENTAGE_JACKPOT, "10")));
    }

    @Test
    @DisplayName("sans option : réglage de la tontine (DISPONIBLE), reste à recevoir suivi")
    void parDefaut() throws Exception {
      CycleResponse r = service.closeCycle(kc, tontine.getId(), tour.getId());

      assertThat(r.getModeRemise()).isEqualTo(ModeRemise.DISPONIBLE);
      assertThat(r.getMontantJackpot()).isEqualByComparingTo("3000");
      assertThat(r.getMontantCommission()).isEqualByComparingTo("300");
      assertThat(r.getMontantNet()).isEqualByComparingTo("2700");
      assertThat(r.getMontantJackpotPrevu()).isEqualByComparingTo("4000");
      assertThat(r.getResteARecevoir()).isEqualByComparingTo("900");
      assertThat(r.getMontantAvance()).isEqualByComparingTo("0");
      assertThat(r.getGagnants().get(0).getMontantRecu()).isEqualByComparingTo("2700");
    }

    @Test
    @DisplayName("ATTENDRE avec un manque : 409 REMISE_JACKPOT_INCOMPLETE, tour inchangé")
    void attendre() {
      tontine.setModeRemise(ModeRemise.ATTENDRE);

      assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), tour.getId()))
          .isInstanceOf(ConflictException.class)
          .satisfies(
              e ->
                  assertThat(((CustomException) e).getCode())
                      .isEqualTo(ErrorCode.REMISE_JACKPOT_INCOMPLETE));
      assertThat(tour.getStatut()).isEqualTo(CycleStatut.EN_COURS);
    }

    @Test
    @DisplayName("option choisie à la remise : elle prime sur le réglage de la tontine")
    void optionChoisie() throws Exception {
      tontine.setModeRemise(ModeRemise.ATTENDRE);

      CycleResponse r =
          service.closeCycle(
              kc, tontine.getId(), tour.getId(), avec(ModeRemise.AVANCE_GESTIONNAIRE));

      assertThat(r.getModeRemise()).isEqualTo(ModeRemise.AVANCE_GESTIONNAIRE);
      // Le bénéficiaire reçoit le net prévu (3 600 F) : commission abandonnée + 600 F de poche
      assertThat(r.getMontantNet()).isEqualByComparingTo("3600");
      assertThat(r.getMontantCommission()).isEqualByComparingTo("0");
      assertThat(r.getMontantAvance()).isEqualByComparingTo("600");
      assertThat(r.getResteARecevoir()).isEqualByComparingTo("0");
      assertThat(r.getGagnants().get(0).getMontantRecu()).isEqualByComparingTo("3600");
    }

    @Test
    @DisplayName(
        "AVANCE_COMMISSION que la commission ne couvre pas, ou CAISSE : 400"
            + " MODE_REMISE_INDISPONIBLE")
    void optionIndisponible() {
      for (ModeRemise mode : List.of(ModeRemise.AVANCE_COMMISSION, ModeRemise.CAISSE)) {
        assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), tour.getId(), avec(mode)))
            .isInstanceOf(BadRequestException.class)
            .satisfies(
                e ->
                    assertThat(((CustomException) e).getCode())
                        .isEqualTo(ErrorCode.MODE_REMISE_INDISPONIBLE));
      }
      assertThat(tour.getStatut()).isEqualTo(CycleStatut.EN_COURS);
    }

    @Test
    @DisplayName("corps sans modeRemise : réglage de la tontine")
    void corpsVide() throws Exception {
      CycleResponse r =
          service.closeCycle(kc, tontine.getId(), tour.getId(), new CloturerCycleRequest());

      assertThat(r.getModeRemise()).isEqualTo(ModeRemise.DISPONIBLE);
    }
  }

  @Nested
  @DisplayName("clôture de la tontine (B33, lot 4) : suggestion et distribution immédiate")
  class ClotureTontine {

    private void toursDeLaTontine(CycleTontine... tours) {
      lenient()
          .when(
              cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(
                  tontine.getId()))
          .thenReturn(List.of(tours));
    }

    private void verifierSuggestion(int fois, String extrait) {
      verify(notificationService, times(fois))
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.TONTINE_PRETE_A_CLOTURER),
              anyString(),
              contains(extrait),
              eq(tontine.getId()));
    }

    @Test
    @DisplayName("mode MANUEL : le dernier membre reçoit son jackpot → clôture suggérée")
    void dernierTourRemis() throws Exception {
      List<TontineMembre> membres = membresActifs(2);
      membres.get(0).setARecuJackpot(true);
      CycleTontine tour = cycleConnu(2, CycleStatut.EN_COURS);
      toursDeLaTontine(tour);

      service.closeCycle(kc, tontine.getId(), tour.getId());

      assertThat(membres.get(1).isARecuJackpot()).isTrue();
      verifierSuggestion(1, "dernier tour");
    }

    @Test
    @DisplayName("mode MANUEL : des membres attendent encore leur jackpot → pas de suggestion")
    void tourAOuvrir() throws Exception {
      membresActifs(3);
      CycleTontine tour = cycleConnu(1, CycleStatut.EN_COURS);
      toursDeLaTontine(tour);

      service.closeCycle(kc, tontine.getId(), tour.getId());

      verifierSuggestion(0, "");
    }

    @Test
    @DisplayName("mode AUTOMATIQUE : un tour reste à venir → pas de suggestion")
    void tourRestant() throws Exception {
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      membresActifs(2);
      CycleTontine tour1 = cycleConnu(1, CycleStatut.EN_COURS);
      CycleTontine tour2 = Fixtures.cycle(tontine, 2, CycleStatut.EN_ATTENTE);
      toursDeLaTontine(tour1, tour2);

      service.closeCycle(kc, tontine.getId(), tour1.getId());

      assertThat(tour2.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      verifierSuggestion(0, "");
    }

    @Test
    @DisplayName(
        "mode AUTOMATIQUE, ordre HYBRIDE : suggestion seulement une fois les gagnants désignés")
    void apresDesignation() throws Exception {
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      tontine.setOrdreBeneficiaire("HYBRIDE");
      membresActifs(1);
      CycleTontine tour = cycleConnu(1, CycleStatut.EN_COURS);
      toursDeLaTontine(tour);
      when(cycleRepository.existsTermineSansGagnant(tontine.getId())).thenReturn(true);

      service.closeCycle(kc, tontine.getId(), tour.getId());
      verifierSuggestion(0, "");

      service.selectionnerBeneficiaire(
          kc, tontine.getId(), tour.getId(), new SelectionnerBeneficiaireRequest());
      verifierSuggestion(1, "dernier tour");
    }

    @Test
    @DisplayName(
        "mode AUTOMATIQUE : tous les tours remis et désignés, un membre arrivé après n'a rien"
            + " reçu → clôture suggérée quand même")
    void membreArriveApres() throws Exception {
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      List<TontineMembre> membres = membresActifs(3);
      membres.get(0).setARecuJackpot(true);
      CycleTontine tour = cycleConnu(2, CycleStatut.EN_COURS);
      toursDeLaTontine(tour);

      service.closeCycle(kc, tontine.getId(), tour.getId());

      verifierSuggestion(1, "dernier tour");
    }

    @Test
    @DisplayName(
        "tontine déjà clôturée : le tour se remet encore, le suivant ne s'ouvre pas, aucune"
            + " suggestion")
    void remiseApresCloture() throws Exception {
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      tontine.setStatut(TontineStatut.TERMINEE);
      membresActifs(2);
      CycleTontine tour1 = cycleConnu(1, CycleStatut.EN_COURS);
      CycleTontine tour2 = Fixtures.cycle(tontine, 2, CycleStatut.EN_ATTENTE);
      toursDeLaTontine(tour1, tour2);

      CycleResponse r = service.closeCycle(kc, tontine.getId(), tour1.getId());

      assertThat(r.getStatut()).isEqualTo(CycleStatut.TERMINE);
      assertThat(r.getGagnants()).hasSize(1);
      assertThat(tour2.getStatut()).isEqualTo(CycleStatut.EN_ATTENTE);
      verifierSuggestion(0, "");
    }

    @Test
    @DisplayName("événementielle : distribution finale faite → clôture suggérée")
    void evenementielleDistribuee() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      membresActifs(1);
      CycleTontine dernier = cycleConnu(1, CycleStatut.EN_COURS);
      dernier.setDateFin(LocalDate.now()); // B45 : dernier sous-cycle clôturé le jour de sa fin
      toursDeLaTontine(dernier);

      service.closeCycle(kc, tontine.getId(), dernier.getId());

      verifierSuggestion(1, "distribution finale");
    }

    @Test
    @DisplayName(
        "distribution immédiate : sous-cycles à venir retirés, sous-cycle en cours clôturé avec"
            + " la part de chaque membre, sans suggestion")
    void distributionImmediate() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      List<TontineMembre> membres = membresActifs(1);
      CycleTontine passe = Fixtures.cycle(tontine, 1, CycleStatut.TERMINE);
      CycleTontine enCours = Fixtures.cycle(tontine, 2, CycleStatut.EN_COURS);
      CycleTontine aVenir = Fixtures.cycle(tontine, 3, CycleStatut.EN_ATTENTE);
      EcheanceCycle echeance =
          EcheanceCycle.builder()
              .id(UUID.randomUUID())
              .cycle(aVenir)
              .numero(1)
              .dateDebut(LocalDate.now().plusDays(31))
              .echeanceAt(LocalDateTime.now().plusDays(60))
              .build();
      aVenir.getEcheances().add(echeance);
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(passe, enCours, aVenir), List.of(passe, enCours));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(cotisation(membres.getFirst(), passe, CotisationStatut.VALIDE, "1000")));

      service.distribuerMaintenant(gestionnaire, tontine);

      assertThat(aVenir.getDeletedAt()).isNotNull();
      assertThat(echeance.getDeletedAt()).isNotNull();
      assertThat(aVenir.getStatut()).isEqualTo(CycleStatut.EN_ATTENTE);
      assertThat(enCours.getStatut()).isEqualTo(CycleStatut.TERMINE);
      assertThat(enCours.getMontantNet()).isEqualByComparingTo("1000");
      verify(distributionRepository).saveAll(any());
      verify(notificationService)
          .notify(
              eq(membres.getFirst().getUser().getId()),
              eq(NotificationType.DISTRIBUTION_FINALE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verifierSuggestion(0, "");
    }

    @Test
    @DisplayName("distribution immédiate sans effet : rotative, ou événementielle déjà distribuée")
    void distributionImmediateSansEffet() {
      service.distribuerMaintenant(gestionnaire, tontine);
      verify(cycleRepository, never())
          .findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(any());

      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      CycleTontine termine = Fixtures.cycle(tontine, 1, CycleStatut.TERMINE);
      toursDeLaTontine(termine);

      service.distribuerMaintenant(gestionnaire, tontine);

      verify(cycleRepository, never()).save(any());
      verify(distributionRepository, never()).saveAll(any());
    }
  }

  @Nested
  @DisplayName(
      "B43 : distribution finale de toutes les cotisations validées, quel que soit le statut")
  class DistributionTousStatuts {

    @BeforeEach
    void evenementielle() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
    }

    @Test
    @DisplayName("SORTI, SUSPENDU et supprimé reçoivent leur part ; jackpot = total encaissé")
    void membresNonActifsDistribues() throws Exception {
      List<TontineMembre> actifs = membresActifs(1);
      TontineMembre sorti = Fixtures.membre(tontine, Fixtures.user("Sorti"), 2);
      sorti.setStatut(MembreStatut.SORTI);
      TontineMembre suspendu = Fixtures.membre(tontine, Fixtures.user("Suspendu"), 3);
      suspendu.setStatut(MembreStatut.SUSPENDU);
      TontineMembre supprime = Fixtures.membre(tontine, Fixtures.user("Supprime"), 4);
      supprime.setStatut(MembreStatut.SORTI);
      supprime.setDeletedAt(LocalDateTime.now());
      CycleTontine c = cycleConnu(1, CycleStatut.EN_COURS);
      c.setDateFin(LocalDate.now());
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(c));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(actifs.get(0), c, CotisationStatut.VALIDE, "10000"),
                  cotisation(sorti, c, CotisationStatut.VALIDE, "22000"),
                  cotisation(suspendu, c, CotisationStatut.VALIDE, "42000"),
                  cotisation(supprime, c, CotisationStatut.VALIDE, "30000")));
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of(commission(CommissionType.POURCENTAGE_JACKPOT, "10")));

      CycleResponse r = service.closeCycle(kc, tontine.getId(), c.getId());

      assertThat(r.getDistributionParMembre())
          .extracting(CycleResponse.MembreDistributionInfo::getMembreId)
          .containsExactlyInAnyOrder(
              actifs.get(0).getId(), sorti.getId(), suspendu.getId(), supprime.getId());
      assertThat(r.getMontantJackpot()).isEqualByComparingTo("104000");
      assertThat(r.getMontantCommission()).isEqualByComparingTo("10400");
      assertThat(r.getMontantNet()).isEqualByComparingTo("93600");
      CycleResponse.MembreDistributionInfo partSorti =
          r.getDistributionParMembre().stream()
              .filter(d -> d.getMembreId().equals(sorti.getId()))
              .findFirst()
              .orElseThrow();
      assertThat(partSorti.getMontantCommission()).isEqualByComparingTo("2200");
      assertThat(partSorti.getMontantNet()).isEqualByComparingTo("19800");
      ArgumentCaptor<List<DistributionEvenementielle>> lignes = ArgumentCaptor.forClass(List.class);
      verify(distributionRepository).saveAll(lignes.capture());
      assertThat(lignes.getValue()).hasSize(4);
      verify(whatsappService, times(4)).sendDistributionFinale(any(), any(), any(), any());
    }

    @Test
    @DisplayName("clôture anticipée (distribuerMaintenant) : même règle")
    void distribuerMaintenantTousStatuts() {
      List<TontineMembre> actifs = membresActifs(1);
      TontineMembre sorti = Fixtures.membre(tontine, Fixtures.user("Sorti"), 2);
      sorti.setStatut(MembreStatut.SORTI);
      CycleTontine enCours = Fixtures.cycle(tontine, 1, CycleStatut.EN_COURS);
      CycleTontine aVenir = Fixtures.cycle(tontine, 2, CycleStatut.EN_ATTENTE);
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(enCours, aVenir), List.of(enCours));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(
              List.of(
                  cotisation(actifs.get(0), enCours, CotisationStatut.VALIDE, "2000"),
                  cotisation(sorti, enCours, CotisationStatut.VALIDE, "4000")));

      service.distribuerMaintenant(gestionnaire, tontine);

      assertThat(enCours.getMontantJackpot()).isEqualByComparingTo("6000");
      ArgumentCaptor<List<DistributionEvenementielle>> lignes = ArgumentCaptor.forClass(List.class);
      verify(distributionRepository).saveAll(lignes.capture());
      assertThat(lignes.getValue()).hasSize(2);
    }
  }

  @Nested
  @DisplayName("B45 : garde-fou de date à la clôture d'un tour")
  class GardeFouDate {

    private CycleTontine tourDu(int numero, LocalDate debut, LocalDate fin) {
      CycleTontine c = cycleConnu(numero, CycleStatut.EN_COURS);
      c.setDateDebut(debut);
      c.setDateFin(fin);
      return c;
    }

    private void tousPayes(boolean valeur) {
      lenient()
          .when(suiviTour.etat(any()))
          .thenReturn(new EtatTour(1, 1, null, valeur, null, null, 0, 0));
    }

    @Test
    @DisplayName("rotative : tour pas encore commencé, paiements incomplets → TOUR_NON_COMMENCE")
    void rotativeNonCommencee() {
      membresActifs(2);
      CycleTontine tour = tourDu(2, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30));
      tousPayes(false);

      assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), tour.getId()))
          .isInstanceOf(BadRequestException.class)
          .satisfies(
              e ->
                  assertThat(((CustomException) e).getCode())
                      .isEqualTo(ErrorCode.TOUR_NON_COMMENCE));
      assertThat(tour.getStatut()).isEqualTo(CycleStatut.EN_COURS);
    }

    @Test
    @DisplayName("rotative : tour pas encore commencé mais tous ont payé → remise permise")
    void rotativeNonCommenceeTousPayes() throws Exception {
      membresActifs(2);
      CycleTontine tour = tourDu(2, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30));
      tousPayes(true);

      service.closeCycle(kc, tontine.getId(), tour.getId());

      assertThat(tour.getStatut()).isEqualTo(CycleStatut.TERMINE);
    }

    @Test
    @DisplayName("rotative TOUS_PAYES AUTOMATIQUE : remise automatique d'un tour pas commencé")
    void remiseAutomatiqueAvantLeDebut() throws Exception {
      tontine.setDeclenchementJackpot(DeclenchementJackpot.TOUS_PAYES);
      tontine.setModeCycle(ModeCycle.AUTOMATIQUE);
      membresActifs(2);
      CycleTontine tour = tourDu(2, LocalDate.now().plusDays(1), LocalDate.now().plusDays(30));
      tousPayes(true);

      assertThat(service.remettreSiTousPayes(kc, tontine.getId(), tour.getId())).isPresent();
      assertThat(tour.getStatut()).isEqualTo(CycleStatut.TERMINE);
    }

    @Test
    @DisplayName("rotative : tour commencé, avant son échéance, paiements incomplets → permise")
    void rotativeAvantEcheance() throws Exception {
      membresActifs(2);
      CycleTontine tour = tourDu(1, LocalDate.now(), LocalDate.now().plusDays(30));
      tousPayes(false);

      service.closeCycle(kc, tontine.getId(), tour.getId());

      assertThat(tour.getStatut()).isEqualTo(CycleStatut.TERMINE);
    }

    @Test
    @DisplayName("événementielle : sous-cycle intermédiaire pas commencé → TOUR_NON_COMMENCE")
    void evenementielleNonCommencee() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      CycleTontine demain = tourDu(2, LocalDate.now().plusDays(1), LocalDate.now().plusDays(1));
      CycleTontine apres = Fixtures.cycle(tontine, 3, CycleStatut.EN_ATTENTE);
      lenient()
          .when(
              cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(
                  tontine.getId()))
          .thenReturn(List.of(demain, apres));

      assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), demain.getId()))
          .isInstanceOf(BadRequestException.class)
          .satisfies(
              e ->
                  assertThat(((CustomException) e).getCode())
                      .isEqualTo(ErrorCode.TOUR_NON_COMMENCE));
    }

    @Test
    @DisplayName("événementielle : dernier sous-cycle avant son jour → CLOTURE_FINALE_PREMATUREE")
    void evenementielleFinalePrematuree() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      CycleTontine dernier = tourDu(4, LocalDate.now(), LocalDate.now().plusDays(6));
      lenient()
          .when(
              cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(
                  tontine.getId()))
          .thenReturn(List.of(dernier));

      assertThatThrownBy(() -> service.closeCycle(kc, tontine.getId(), dernier.getId()))
          .isInstanceOf(BadRequestException.class)
          .hasMessageContaining("Clôturer la tontine")
          .satisfies(
              e ->
                  assertThat(((CustomException) e).getCode())
                      .isEqualTo(ErrorCode.CLOTURE_FINALE_PREMATUREE));
      verify(distributionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("événementielle : dernier sous-cycle le jour de sa fin → distribution finale")
    void evenementielleFinaleLeJour() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      membresActifs(1);
      CycleTontine dernier = tourDu(4, LocalDate.now().minusDays(6), LocalDate.now());
      lenient()
          .when(
              cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(
                  tontine.getId()))
          .thenReturn(List.of(dernier));

      assertThat(
              service.closeCycle(kc, tontine.getId(), dernier.getId()).getDistributionParMembre())
          .isNotNull();
      assertThat(dernier.getStatut()).isEqualTo(CycleStatut.TERMINE);
    }
  }

  @Nested
  @DisplayName("B51 : répartition finale relisible")
  class RepartitionRelisible {

    private User awa;
    private TontineMembre membreAwa;
    private TontineMembre membreBinta;
    private CycleTontine dernier;

    @BeforeEach
    void distribuee() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      awa = Fixtures.user("Awa");
      membreAwa = Fixtures.membre(tontine, awa, 1);
      membreBinta = Fixtures.membre(tontine, Fixtures.user("Binta"), 2);
      dernier = cycleConnu(3, CycleStatut.TERMINE);
      lenient()
          .when(userRepository.findByKeycloakId(awa.getKeycloakId()))
          .thenReturn(Optional.of(awa));
      lenient()
          .when(
              membreRepository.existsByTontine_IdAndUser_IdAndDeletedAtIsNull(
                  tontine.getId(), awa.getId()))
          .thenReturn(true);
      lenient()
          .when(distributionRepository.findByCycleAvecMembres(dernier.getId()))
          .thenReturn(List.of(part(membreAwa, "46000"), part(membreBinta, "42000")));
    }

    private DistributionEvenementielle part(TontineMembre membre, String montant) {
      return DistributionEvenementielle.builder()
          .id(UUID.randomUUID())
          .tontine(tontine)
          .cycle(dernier)
          .membre(membre)
          .montantCotise(new BigDecimal(montant))
          .montantCommission(BigDecimal.ZERO)
          .montantNet(new BigDecimal(montant))
          .build();
    }

    @Test
    @DisplayName("GET du dernier sous-cycle : toutes les parts pour le gestionnaire")
    void gestionnaire() throws Exception {
      CycleResponse r = service.getCycle(kc, tontine.getId(), dernier.getId());

      assertThat(r.getDistributionParMembre())
          .extracting(CycleResponse.MembreDistributionInfo::getMembreId)
          .containsExactly(membreAwa.getId(), membreBinta.getId());
      assertThat(r.getDistributionParMembre().get(0).getFirstName()).isEqualTo("Awa");
      assertThat(r.getDistributionParMembre().get(0).getMontantNet()).isEqualByComparingTo("46000");
    }

    @Test
    @DisplayName("GET du dernier sous-cycle par un membre : sa part seulement")
    void membre() throws Exception {
      CycleResponse r = service.getCycle(awa.getKeycloakId(), tontine.getId(), dernier.getId());

      assertThat(r.getDistributionParMembre())
          .extracting(CycleResponse.MembreDistributionInfo::getMembreId)
          .containsExactly(membreAwa.getId());
    }

    @Test
    @DisplayName("sous-cycle intermédiaire ou tour rotatif : pas de distribution (null)")
    void sansDistribution() throws Exception {
      CycleTontine intermediaire = cycleConnu(1, CycleStatut.TERMINE);
      assertThat(
              service
                  .getCycle(kc, tontine.getId(), intermediaire.getId())
                  .getDistributionParMembre())
          .isNull();

      tontine.setTontineType(TontineType.ROTATIVE);
      assertThat(service.getCycle(kc, tontine.getId(), dernier.getId()).getDistributionParMembre())
          .isNull();
      verify(distributionRepository, never()).findByCycleAvecMembres(dernier.getId());
    }

    @Test
    @DisplayName("clôture anticipée : distribuerMaintenant renvoie la répartition calculée")
    void distribuerMaintenantRenvoieLaRepartition() {
      CycleTontine enCours = Fixtures.cycle(tontine, 1, CycleStatut.EN_COURS);
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of(enCours), List.of(enCours));
      when(cotisationRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.VALIDE))
          .thenReturn(List.of(cotisation(membreAwa, enCours, CotisationStatut.VALIDE, "2000")));

      assertThat(service.distribuerMaintenant(gestionnaire, tontine))
          .extracting(CycleResponse.MembreDistributionInfo::getMontantNet)
          .containsExactly(new BigDecimal("2000"));
    }

    @Test
    @DisplayName("distribuerMaintenant : rotative ou aucun sous-cycle en cours → liste vide")
    void distribuerMaintenantSansRien() {
      when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
          .thenReturn(List.of());
      assertThat(service.distribuerMaintenant(gestionnaire, tontine)).isEmpty();

      tontine.setTontineType(TontineType.ROTATIVE);
      assertThat(service.distribuerMaintenant(gestionnaire, tontine)).isEmpty();
    }
  }
}
