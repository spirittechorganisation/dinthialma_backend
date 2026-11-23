package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.adhesion.codeList.StatutDemande;
import com.africa.dinthialma_backend.adhesion.entity.DemandeAdhesion;
import com.africa.dinthialma_backend.adhesion.repository.DemandeAdhesionRepository;
import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ConflictException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.jackpot.codeList.StatutPriorite;
import com.africa.dinthialma_backend.jackpot.entity.PrioriteJackpot;
import com.africa.dinthialma_backend.jackpot.repository.PrioriteJackpotRepository;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.MonRoleTontine;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.ClotureEnSuspensResponse;
import com.africa.dinthialma_backend.tontine.dto.CloturerTontineRequest;
import com.africa.dinthialma_backend.tontine.dto.CycleResponse;
import com.africa.dinthialma_backend.tontine.dto.TontineResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleGagnant;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.mapper.TontineMapper;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.impl.ClotureTontineServiceImpl;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClotureTontineService : clôture de la tontine par son gestionnaire (B33, lot 4)")
class ClotureTontineServiceImplTest {

  @Mock private AccessControl accessControl;
  @Mock private TontineRepository tontineRepository;
  @Spy private TontineMapper tontineMapper = Mappers.getMapper(TontineMapper.class);
  @Mock private CycleTontineRepository cycleRepository;
  @Mock private CotisationRepository cotisationRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private DemandeAdhesionRepository demandeRepository;
  @Mock private PrioriteJackpotRepository prioriteRepository;
  @Mock private CycleService cycleService;
  @Mock private AuditService auditService;
  @Mock private NotificationService notificationService;
  @InjectMocks private ClotureTontineServiceImpl service;

  private User gestionnaire;
  private Tontine tontine;
  private List<TontineMembre> membres;
  private String kc;

  @BeforeEach
  void setUp() throws Exception {
    gestionnaire = Fixtures.user("Gestionnaire");
    kc = gestionnaire.getKeycloakId();
    tontine = Fixtures.tontine(gestionnaire);
    membres = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      TontineMembre membre = Fixtures.membre(tontine, Fixtures.user("M" + i), i);
      // Adhésion ancienne : toutes les échéances des tours de test leur sont dues
      membre.setDateAdhesion(LocalDate.now().minusYears(1));
      membres.add(membre);
    }
    lenient().when(accessControl.utilisateur(kc)).thenReturn(gestionnaire);
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    lenient().when(tontineRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
                tontine.getId(), MembreStatut.ACTIF))
        .thenReturn(membres);
  }

  private void tours(CycleTontine... tours) {
    when(cycleRepository.findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(tontine.getId()))
        .thenReturn(List.of(tours));
  }

  /** Tour à une échéance, échue depuis {@code joursEchus} jours (négatif : pas encore échue). */
  private CycleTontine tour(int numero, CycleStatut statut, int joursEchus) {
    CycleTontine tour = Fixtures.cycle(tontine, numero, statut);
    tour.getEcheances()
        .add(
            EcheanceCycle.builder()
                .id(UUID.randomUUID())
                .cycle(tour)
                .numero(1)
                .dateDebut(LocalDate.now().minusDays(joursEchus + 30L))
                .echeanceAt(LocalDateTime.now().minusDays(joursEchus))
                .build());
    return tour;
  }

  /** Tour remis, payé par tous et avec un gagnant : rien en suspens. */
  private CycleTontine tourSolde(int numero) {
    CycleTontine tour = tour(numero, CycleStatut.TERMINE, 5);
    tour.getGagnants().add(CycleGagnant.builder().cycle(tour).membre(membres.get(0)).build());
    payes(tour, membres);
    return tour;
  }

  private void payes(CycleTontine tour, List<TontineMembre> payeurs) {
    UUID echeanceId = tour.getEcheances().get(0).getId();
    List<Object[]> lignes = new ArrayList<>();
    for (TontineMembre m : payeurs) {
      lignes.add(new Object[] {echeanceId, m.getId(), CotisationStatut.VALIDE});
    }
    when(cotisationRepository.findEcheanceMembreStatutByCycle(tour.getId())).thenReturn(lignes);
  }

  private static CloturerTontineRequest forcer(Boolean valeur) {
    CloturerTontineRequest request = new CloturerTontineRequest();
    request.setForcer(valeur);
    return request;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> enSuspens(CloturerTontineRequest request) {
    Map<String, Object>[] details = new Map[1];
    assertThatThrownBy(() -> service.cloturer(kc, tontine.getId(), request))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            e -> {
              CustomException ce = (CustomException) e;
              assertThat(ce.getCode()).isEqualTo(ErrorCode.TONTINE_CLOTURE_A_CONFIRMER);
              details[0] = ce.getDetails();
            });
    assertThat(tontine.getStatut()).isNotEqualTo(TontineStatut.TERMINEE);
    verify(tontineRepository, never()).save(any());
    verify(cycleService, never()).distribuerMaintenant(any(), any());
    return details[0];
  }

  @Nested
  @DisplayName("accès et statut")
  class Acces {

    @Test
    @DisplayName("tontine inconnue : 404 ; ni créateur ni SUPER_ADMIN : 403")
    void droits() throws Exception {
      assertThatThrownBy(() -> service.cloturer(kc, UUID.randomUUID(), null))
          .isInstanceOf(NotFoundException.class);

      User tiers = Fixtures.user("Tiers");
      when(accessControl.utilisateur(tiers.getKeycloakId())).thenReturn(tiers);
      assertThatThrownBy(() -> service.cloturer(tiers.getKeycloakId(), tontine.getId(), null))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("BROUILLON ou déjà TERMINEE : 400 TONTINE_NON_CLOTURABLE")
    void nonCloturable() {
      for (TontineStatut statut : List.of(TontineStatut.BROUILLON, TontineStatut.TERMINEE)) {
        tontine.setStatut(statut);
        assertThatThrownBy(() -> service.cloturer(kc, tontine.getId(), forcer(true)))
            .isInstanceOf(BadRequestException.class)
            .satisfies(
                e ->
                    assertThat(((CustomException) e).getCode())
                        .isEqualTo(ErrorCode.TONTINE_NON_CLOTURABLE));
      }
      verify(tontineRepository, never()).save(any());
    }

    @Test
    @DisplayName("SUPER_ADMIN : clôture une tontine SUSPENDUE qu'il n'a pas créée")
    void superAdmin() throws Exception {
      User admin = Fixtures.user("Admin");
      when(accessControl.utilisateur(admin.getKeycloakId())).thenReturn(admin);
      when(accessControl.estSuperAdmin(admin)).thenReturn(true);
      tontine.setStatut(TontineStatut.SUSPENDUE);
      tours();

      TontineResponse r = service.cloturer(admin.getKeycloakId(), tontine.getId(), null);

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
      verify(auditService)
          .log(admin, "tontines", tontine.getId(), "statut", "SUSPENDUE", "TERMINEE");
    }
  }

  @Nested
  @DisplayName("rien en suspens")
  class SansReserve {

    @Test
    @DisplayName(
        "tous les tours remis et payés : TERMINEE sans forcer, auditée, demandes refusées,"
            + " priorités annulées, membres actifs notifiés")
    void cloture() throws Exception {
      tours(tourSolde(1), tourSolde(2));
      DemandeAdhesion demande =
          DemandeAdhesion.builder().tontine(tontine).statut(StatutDemande.EN_ATTENTE).build();
      when(demandeRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), StatutDemande.EN_ATTENTE))
          .thenReturn(List.of(demande));
      PrioriteJackpot priorite =
          PrioriteJackpot.builder().tontine(tontine).statut(StatutPriorite.ACCEPTEE).build();
      when(prioriteRepository.findByTontine_IdAndStatutInAndDeletedAtIsNull(
              tontine.getId(), List.of(StatutPriorite.EN_ATTENTE, StatutPriorite.ACCEPTEE)))
          .thenReturn(List.of(priorite));
      when(membreRepository.countByTontine_IdAndDeletedAtIsNull(tontine.getId())).thenReturn(3L);

      TontineResponse r = service.cloturer(kc, tontine.getId(), null);

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
      assertThat(r.getMonRole()).isEqualTo(MonRoleTontine.GESTIONNAIRE);
      assertThat(tontine.getStatut()).isEqualTo(TontineStatut.TERMINEE);
      verify(auditService)
          .log(gestionnaire, "tontines", tontine.getId(), "statut", "ACTIVE", "TERMINEE");
      assertThat(demande.getStatut()).isEqualTo(StatutDemande.REFUSEE);
      assertThat(demande.getMotifRefus()).isEqualTo("Tontine clôturée");
      assertThat(demande.getTraiteePar()).isSameAs(gestionnaire);
      assertThat(demande.getDateTraitement()).isNotNull();
      assertThat(priorite.getStatut()).isEqualTo(StatutPriorite.ANNULEE);
      assertThat(priorite.getTraiteePar()).isSameAs(gestionnaire);
      assertThat(priorite.getDateTraitement()).isNotNull();
      for (TontineMembre m : membres) {
        verify(notificationService)
            .notify(
                eq(m.getUser().getId()),
                eq(NotificationType.TONTINE_CLOTUREE),
                anyString(),
                anyString(),
                eq(tontine.getId()));
      }
    }

    @Test
    @DisplayName("le gestionnaire qui cotise dans sa tontine ne se notifie pas lui-même")
    void gestionnaireMembre() throws Exception {
      membres.add(Fixtures.membre(tontine, gestionnaire, 4));
      tours();

      service.cloturer(kc, tontine.getId(), forcer(false));

      verify(notificationService, never())
          .notify(eq(gestionnaire.getId()), any(), anyString(), anyString(), any());
      verify(notificationService, Mockito.times(3))
          .notify(any(), eq(NotificationType.TONTINE_CLOTUREE), anyString(), anyString(), any());
    }

    @Test
    @DisplayName(
        "tour remis avant V043 (sans net prévu), tour sans gagnant quand tous sont servis,"
            + " échéance d'avant l'adhésion : rien en suspens")
    void casSansSuite() throws Exception {
      membres.forEach(m -> m.setARecuJackpot(true));
      membres.get(2).setDateAdhesion(LocalDate.now().minusDays(1));
      CycleTontine ancien = tour(1, CycleStatut.TERMINE, 5);
      ancien.setMontantNet(new BigDecimal("3000"));
      payes(ancien, membres.subList(0, 2));
      tours(ancien);

      TontineResponse r = service.cloturer(kc, tontine.getId(), null);

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
    }
  }

  @Nested
  @DisplayName("points en suspens : 409 TONTINE_CLOTURE_A_CONFIRMER puis forcer")
  class EnSuspens {

    @Test
    @DisplayName(
        "tours non remis (en cours, à venir), retard sur une échéance échue du tour en cours,"
            + " paiements à valider")
    @SuppressWarnings("unchecked")
    void toursNonRemis() {
      CycleTontine remis = tourSolde(1);
      CycleTontine enCours = tour(2, CycleStatut.EN_COURS, 2);
      CycleTontine nonEchu = tour(3, CycleStatut.EN_COURS, -10);
      CycleTontine aVenir = tour(4, CycleStatut.EN_ATTENTE, -40);
      payes(enCours, membres.subList(0, 2));
      tours(remis, enCours, nonEchu, aVenir);
      when(cotisationRepository.countByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.EN_ATTENTE))
          .thenReturn(1L);
      when(cotisationRepository.countByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.EN_RETARD))
          .thenReturn(2L);

      Map<String, Object> details = enSuspens(null);

      assertThat((List<ClotureEnSuspensResponse.Tour>) details.get("toursNonRemis"))
          .extracting(ClotureEnSuspensResponse.Tour::getNumeroCycle)
          .containsExactly(2, 3, 4);
      assertThat((List<ClotureEnSuspensResponse.Tour>) details.get("toursNonRemis"))
          .first()
          .satisfies(
              t -> {
                assertThat(t.getCycleId()).isEqualTo(enCours.getId());
                assertThat(t.getStatut()).isEqualTo(CycleStatut.EN_COURS);
                assertThat(t.getDateDebut()).isEqualTo(enCours.getDateDebut());
                assertThat(t.getDateFin()).isEqualTo(enCours.getDateFin());
              });
      assertThat((List<ClotureEnSuspensResponse.Retard>) details.get("retards"))
          .singleElement()
          .satisfies(
              r -> {
                assertThat(r.getMembreId()).isEqualTo(membres.get(2).getId());
                assertThat(r.getFirstName()).isEqualTo("M3");
                assertThat(r.getLastName()).isEqualTo("Test");
                assertThat(r.getEcheancesManquees()).isEqualTo(1);
                assertThat(r.getMontant()).isEqualByComparingTo("1000");
              });
      assertThat((List<?>) details.get("toursSansGagnant")).isEmpty();
      assertThat((List<?>) details.get("complements")).isEmpty();
      assertThat(details.get("cotisationsAValider")).isEqualTo(3L);
    }

    @Test
    @DisplayName(
        "tours remis : gagnants à désigner, retards cumulés par membre (déclaré non validé"
            + " compris), complément dû et avance à rembourser")
    @SuppressWarnings("unchecked")
    void toursRemis() {
      CycleTontine sansGagnant = tour(1, CycleStatut.TERMINE, 40);
      sansGagnant
          .getGagnants()
          .add(
              CycleGagnant.builder()
                  .cycle(sansGagnant)
                  .membre(membres.get(0))
                  .deletedAt(LocalDateTime.now())
                  .build());
      sansGagnant.setMontantNet(new BigDecimal("1600"));
      sansGagnant.setMontantNetPrevu(new BigDecimal("2400"));
      CycleTontine avance = tour(2, CycleStatut.TERMINE, 10);
      avance.getGagnants().add(CycleGagnant.builder().cycle(avance).membre(membres.get(1)).build());
      avance.setMontantNet(new BigDecimal("2400"));
      avance.setMontantNetPrevu(new BigDecimal("2400"));
      avance.setMontantAvance(new BigDecimal("500"));
      UUID e1 = sansGagnant.getEcheances().get(0).getId();
      when(cotisationRepository.findEcheanceMembreStatutByCycle(sansGagnant.getId()))
          .thenReturn(
              List.of(
                  new Object[] {e1, membres.get(0).getId(), CotisationStatut.VALIDE},
                  new Object[] {e1, membres.get(1).getId(), CotisationStatut.VALIDE},
                  new Object[] {e1, membres.get(2).getId(), CotisationStatut.EN_RETARD}));
      payes(avance, membres.subList(0, 1));
      tours(sansGagnant, avance);

      Map<String, Object> details = enSuspens(forcer(false));

      assertThat((List<?>) details.get("toursNonRemis")).isEmpty();
      assertThat((List<ClotureEnSuspensResponse.Tour>) details.get("toursSansGagnant"))
          .extracting(ClotureEnSuspensResponse.Tour::getNumeroCycle)
          .containsExactly(1);
      assertThat((List<ClotureEnSuspensResponse.Retard>) details.get("retards"))
          .extracting(
              ClotureEnSuspensResponse.Retard::getMembreId,
              ClotureEnSuspensResponse.Retard::getEcheancesManquees)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(membres.get(1).getId(), 1),
              org.assertj.core.groups.Tuple.tuple(membres.get(2).getId(), 2));
      List<ClotureEnSuspensResponse.Complement> complements =
          (List<ClotureEnSuspensResponse.Complement>) details.get("complements");
      assertThat(complements).hasSize(2);
      assertThat(complements.get(0).getCycleId()).isEqualTo(sansGagnant.getId());
      assertThat(complements.get(0).getNumeroCycle()).isEqualTo(1);
      assertThat(complements.get(0).getResteARecevoir()).isEqualByComparingTo("800");
      assertThat(complements.get(0).getMontantAvance()).isEqualByComparingTo("0");
      assertThat(complements.get(1).getResteARecevoir()).isEqualByComparingTo("0");
      assertThat(complements.get(1).getMontantAvance()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("montant libre sans plancher : retard listé sans montant")
    @SuppressWarnings("unchecked")
    void montantLibre() {
      tontine.setMontantLibre(true);
      tontine.setMontant(null);
      CycleTontine remis = tour(1, CycleStatut.TERMINE, 5);
      remis.getGagnants().add(CycleGagnant.builder().cycle(remis).membre(membres.get(0)).build());
      payes(remis, membres.subList(0, 2));
      tours(remis);

      Map<String, Object> details = enSuspens(null);

      assertThat((List<ClotureEnSuspensResponse.Retard>) details.get("retards"))
          .singleElement()
          .satisfies(r -> assertThat(r.getMontant()).isNull());
    }

    @Test
    @DisplayName("seulement des paiements à valider : 409 aussi")
    void paiementsAValider() {
      tours();
      when(cotisationRepository.countByTontine_IdAndStatutAndDeletedAtIsNull(
              tontine.getId(), CotisationStatut.EN_ATTENTE))
          .thenReturn(1L);

      assertThat(enSuspens(null).get("cotisationsAValider")).isEqualTo(1L);
    }

    @Test
    @DisplayName("forcer: true : clôturée malgré un tour en cours, qui reste tel quel")
    void forcee() throws Exception {
      CycleTontine enCours = tour(1, CycleStatut.EN_COURS, -10);
      tours(enCours);

      TontineResponse r = service.cloturer(kc, tontine.getId(), forcer(true));

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
      assertThat(enCours.getStatut()).isEqualTo(CycleStatut.EN_COURS);
      assertThat(enCours.getDeletedAt()).isNull();
      verify(cycleRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("tontine événementielle")
  class Evenementielle {

    @BeforeEach
    void evenementielle() {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
    }

    @Test
    @DisplayName(
        "sous-cycles restants : 409 sans retard ni complément ; forcer → distribution immédiate"
            + " avant de passer TERMINEE")
    void avantLeTerme() throws Exception {
      CycleTontine passe = tour(1, CycleStatut.TERMINE, 20);
      passe.setMontantNet(new BigDecimal("1000"));
      passe.setMontantNetPrevu(new BigDecimal("5000"));
      tours(passe, tour(2, CycleStatut.EN_COURS, 3), tour(3, CycleStatut.EN_ATTENTE, -20));

      Map<String, Object> details = enSuspens(null);
      assertThat((List<?>) details.get("toursNonRemis")).hasSize(2);
      assertThat((List<?>) details.get("toursSansGagnant")).isEmpty();
      assertThat((List<?>) details.get("retards")).isEmpty();
      assertThat((List<?>) details.get("complements")).isEmpty();
      verify(cotisationRepository, never()).findEcheanceMembreStatutByCycle(any());

      TontineResponse r = service.cloturer(kc, tontine.getId(), forcer(true));

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
      InOrder ordre = Mockito.inOrder(cycleService, tontineRepository);
      ordre.verify(cycleService).distribuerMaintenant(gestionnaire, tontine);
      ordre.verify(tontineRepository).save(tontine);
    }

    @Test
    @DisplayName("distribution finale déjà faite : TERMINEE sans forcer")
    void apresDistribution() throws Exception {
      tours(tour(1, CycleStatut.TERMINE, 20), tour(2, CycleStatut.TERMINE, 3));

      TontineResponse r = service.cloturer(kc, tontine.getId(), null);

      assertThat(r.getStatut()).isEqualTo(TontineStatut.TERMINEE);
    }
  }

  @Nested
  @DisplayName("B51 : répartition finale dans la réponse de la clôture forcée")
  class RepartitionDansLaReponse {

    @Test
    @DisplayName("événementielle arrêtée avant son terme : distributionParMembre renvoyée")
    void distributionRenvoyee() throws Exception {
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      tours(tour(1, CycleStatut.EN_COURS, -1));
      CycleResponse.MembreDistributionInfo part =
          CycleResponse.MembreDistributionInfo.builder()
              .membreId(membres.get(0).getId())
              .montantCotise(new BigDecimal("2000"))
              .montantCommission(BigDecimal.ZERO)
              .montantNet(new BigDecimal("2000"))
              .build();
      when(cycleService.distribuerMaintenant(gestionnaire, tontine)).thenReturn(List.of(part));

      TontineResponse r = service.cloturer(kc, tontine.getId(), forcer(true));

      assertThat(r.getDistributionParMembre()).containsExactly(part);
    }

    @Test
    @DisplayName("rien à distribuer (rotative, ou distribution déjà faite) : champ absent")
    void sansDistribution() throws Exception {
      tours(tourSolde(1));

      assertThat(service.cloturer(kc, tontine.getId(), null).getDistributionParMembre()).isNull();
    }
  }
}
