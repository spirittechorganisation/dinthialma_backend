package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CommissionType;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.RemiseApercuResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleGagnant;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import com.africa.dinthialma_backend.tontine.repository.CycleGagnantRepository;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineCommissionRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.impl.RemiseServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RemiseService : aperçu avant remise et régularisation sur un tour remis (B33)")
class RemiseServiceImplTest {

  @Mock private AccessControl accessControl;
  @Mock private TontineRepository tontineRepository;
  @Mock private CycleTontineRepository cycleRepository;
  @Mock private CycleGagnantRepository gagnantRepository;
  @Mock private CotisationRepository cotisationRepository;
  @Mock private TontineCommissionRepository commissionRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private NotificationService notificationService;
  @InjectMocks private RemiseServiceImpl service;

  private User gestionnaire;
  private Tontine tontine;
  private CycleTontine cycle;
  private List<TontineMembre> membres;
  private String kc;

  private void valides(int nombre) {
    List<Cotisation> cotisations = new ArrayList<>();
    for (int i = 0; i < nombre; i++) {
      cotisations.add(Fixtures.cotisation(membres.get(i), cycle, CotisationStatut.VALIDE));
    }
    when(cotisationRepository.findByCycle_IdAndStatutAndDeletedAtIsNull(
            cycle.getId(), CotisationStatut.VALIDE))
        .thenReturn(cotisations);
  }

  private void commissionDe(String pourcentage) {
    when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(
            List.of(
                TontineCommission.builder()
                    .type(CommissionType.POURCENTAGE_JACKPOT)
                    .valeur(new BigDecimal(pourcentage))
                    .build()));
  }

  private CycleGagnant gagnant(TontineMembre membre, String montantRecu) {
    return CycleGagnant.builder()
        .cycle(cycle)
        .membre(membre)
        .montantRecu(new BigDecimal(montantRecu))
        .build();
  }

  @BeforeEach
  void setUp() throws Exception {
    gestionnaire = Fixtures.user("Gestionnaire");
    kc = gestionnaire.getKeycloakId();
    tontine = Fixtures.tontine(gestionnaire);
    cycle = Fixtures.cycle(tontine, 3, CycleStatut.EN_COURS);
    membres = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      membres.add(Fixtures.membre(tontine, Fixtures.user("M" + i), i));
    }
    lenient().when(accessControl.utilisateur(kc)).thenReturn(gestionnaire);
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    lenient()
        .when(
            cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(cycle.getId(), tontine.getId()))
        .thenReturn(Optional.of(cycle));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
                tontine.getId(), MembreStatut.ACTIF))
        .thenReturn(membres);
  }

  @Nested
  @DisplayName("aperçu avant remise")
  class Apercu {

    private EcheanceCycle echeance;

    @BeforeEach
    void uneEcheance() {
      echeance =
          EcheanceCycle.builder()
              .id(UUID.randomUUID())
              .cycle(cycle)
              .numero(1)
              .dateDebut(LocalDate.now())
              .echeanceAt(LocalDate.now().plusDays(30).atTime(23, 59))
              .build();
      cycle.getEcheances().add(echeance);
    }

    @Test
    @DisplayName(
        "3 payés sur 4 à 1 000 F, commission 10 % : prévu, collecté, manquant, retardataire et"
            + " montants de chaque option")
    void complet() throws Exception {
      valides(3);
      commissionDe("10");
      List<Object[]> lignes = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        lignes.add(
            new Object[] {echeance.getId(), membres.get(i).getId(), CotisationStatut.VALIDE});
      }
      lignes.add(
          new Object[] {echeance.getId(), membres.get(3).getId(), CotisationStatut.EN_ATTENTE});
      when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId())).thenReturn(lignes);
      tontine.setModeRemise(ModeRemise.ATTENDRE);

      RemiseApercuResponse r = service.apercu(kc, tontine.getId(), cycle.getId());

      assertThat(r.getNumeroCycle()).isEqualTo(3);
      assertThat(r.getJackpotPrevu()).isEqualByComparingTo("4000");
      assertThat(r.getCollecte()).isEqualByComparingTo("3000");
      assertThat(r.getManquant()).isEqualByComparingTo("1000");
      assertThat(r.getModeRemiseParDefaut()).isEqualTo(ModeRemise.ATTENDRE);
      assertThat(r.getRetardataires())
          .singleElement()
          .satisfies(
              x -> {
                assertThat(x.getMembreId()).isEqualTo(membres.get(3).getId());
                assertThat(x.getEcheancesManquees()).isEqualTo(1);
                assertThat(x.getMontant()).isEqualByComparingTo("1000");
              });
      assertThat(r.getOptions()).hasSize(ModeRemise.values().length);
      RemiseApercuResponse.Option attendre = option(r, ModeRemise.ATTENDRE);
      assertThat(attendre.isPossible()).isFalse();
      RemiseApercuResponse.Option disponible = option(r, ModeRemise.DISPONIBLE);
      assertThat(disponible.isPossible()).isTrue();
      assertThat(disponible.getMontantNet()).isEqualByComparingTo("2700");
      assertThat(disponible.getMontantBeneficiaire()).isEqualByComparingTo("2700");
      assertThat(disponible.getCommission()).isEqualByComparingTo("300");
      assertThat(disponible.getResteARecevoir()).isEqualByComparingTo("900");
      // La commission prévue (400 F) ne couvre pas le manque (1 000 F)
      assertThat(option(r, ModeRemise.AVANCE_COMMISSION).isPossible()).isFalse();
      RemiseApercuResponse.Option avance = option(r, ModeRemise.AVANCE_GESTIONNAIRE);
      assertThat(avance.isPossible()).isTrue();
      assertThat(avance.getMontantNet()).isEqualByComparingTo("3600");
      assertThat(avance.getCommission()).isEqualByComparingTo("0");
      assertThat(avance.getAvanceGestionnaire()).isEqualByComparingTo("600");
      assertThat(avance.getMontantARecuperer()).isEqualByComparingTo("1000");
      assertThat(option(r, ModeRemise.CAISSE).isPossible()).isFalse();
    }

    @Test
    @DisplayName("deux gagnants par tour : la part d'un bénéficiaire est la moitié du net")
    void plusieursGagnants() throws Exception {
      tontine.setNombreGagnants(2);
      valides(4);
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of());
      when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId()))
          .thenReturn(List.of());

      RemiseApercuResponse r = service.apercu(kc, tontine.getId(), cycle.getId());

      RemiseApercuResponse.Option disponible = option(r, ModeRemise.DISPONIBLE);
      assertThat(disponible.getMontantNet()).isEqualByComparingTo("4000");
      assertThat(disponible.getMontantBeneficiaire()).isEqualByComparingTo("2000");
      assertThat(r.getManquant()).isEqualByComparingTo("0");
      assertThat(option(r, ModeRemise.ATTENDRE).isPossible()).isTrue();
    }

    @Test
    @DisplayName(
        "refus : tiers (403), tontine ou tour inconnu (404), tour non EN_COURS ou événementielle"
            + " (400)")
    void refus() throws Exception {
      User tiers = Fixtures.user("Tiers");
      when(accessControl.utilisateur(tiers.getKeycloakId())).thenReturn(tiers);
      when(accessControl.estSuperAdmin(tiers)).thenReturn(false);
      assertThatThrownBy(
              () -> service.apercu(tiers.getKeycloakId(), tontine.getId(), cycle.getId()))
          .isInstanceOf(ForbiddenException.class);

      UUID inconnu = UUID.randomUUID();
      when(tontineRepository.findByIdAndDeletedAtIsNull(inconnu)).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.apercu(kc, inconnu, cycle.getId()))
          .isInstanceOf(NotFoundException.class);
      when(cycleRepository.findByIdAndTontine_IdAndDeletedAtIsNull(inconnu, tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.apercu(kc, tontine.getId(), inconnu))
          .isInstanceOf(NotFoundException.class);

      cycle.setStatut(CycleStatut.TERMINE);
      assertThatThrownBy(() -> service.apercu(kc, tontine.getId(), cycle.getId()))
          .isInstanceOf(BadRequestException.class);
      cycle.setStatut(CycleStatut.EN_COURS);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      assertThatThrownBy(() -> service.apercu(kc, tontine.getId(), cycle.getId()))
          .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("SUPER_ADMIN : accès à l'aperçu d'une tontine qu'il n'a pas créée")
    void superAdmin() throws Exception {
      User admin = Fixtures.user("Admin");
      when(accessControl.utilisateur(admin.getKeycloakId())).thenReturn(admin);
      when(accessControl.estSuperAdmin(admin)).thenReturn(true);
      valides(4);
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of());
      when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId()))
          .thenReturn(List.of());

      assertThat(service.apercu(admin.getKeycloakId(), tontine.getId(), cycle.getId())).isNotNull();
    }

    private RemiseApercuResponse.Option option(RemiseApercuResponse r, ModeRemise mode) {
      return r.getOptions().stream()
          .filter(o -> o.getModeRemise() == mode)
          .findFirst()
          .orElseThrow();
    }
  }

  @Nested
  @DisplayName("régularisation sur un tour remis")
  class Regularisation {

    @BeforeEach
    void tourRemis() {
      cycle.setStatut(CycleStatut.TERMINE);
    }

    @Test
    @DisplayName(
        "DISPONIBLE : le 4ᵉ paiement arrive, le gagnant est crédité du complément net et prévenu,"
            + " le gestionnaire reçoit COMPLEMENT_A_REMETTRE")
    void complementAuBeneficiaire() {
      cycle.setModeRemise(ModeRemise.DISPONIBLE);
      cycle.setMontantJackpot(new BigDecimal("3000"));
      cycle.setMontantCommission(new BigDecimal("300"));
      cycle.setMontantNet(new BigDecimal("2700"));
      cycle.setMontantNetPrevu(new BigDecimal("3600"));
      valides(4);
      commissionDe("10");
      CycleGagnant gagnant = gagnant(membres.get(0), "2700");
      when(gagnantRepository.findByCycle_IdAndDeletedAtIsNull(cycle.getId()))
          .thenReturn(List.of(gagnant));

      service.regulariser(cycle);

      assertThat(cycle.getMontantJackpot()).isEqualByComparingTo("4000");
      assertThat(cycle.getMontantCommission()).isEqualByComparingTo("400");
      assertThat(cycle.getMontantNet()).isEqualByComparingTo("3600");
      assertThat(cycle.getMontantAvance()).isEqualByComparingTo("0");
      assertThat(gagnant.getMontantRecu()).isEqualByComparingTo("3600");
      verify(notificationService)
          .notify(
              eq(membres.get(0).getUser().getId()),
              eq(NotificationType.JACKPOT_DISTRIBUE),
              eq("Complément de jackpot"),
              contains("900"),
              eq(tontine.getId()),
              any());
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.COMPLEMENT_A_REMETTRE),
              anyString(),
              contains("900"),
              eq(tontine.getId()),
              any());
    }

    @Test
    @DisplayName("deux gagnants : le complément est partagé au franc près, somme exacte")
    void partageEntreGagnants() {
      cycle.setMontantNet(new BigDecimal("2000"));
      valides(3);
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of());
      CycleGagnant premier = gagnant(membres.get(0), "1000");
      CycleGagnant second = gagnant(membres.get(1), "1000");
      when(gagnantRepository.findByCycle_IdAndDeletedAtIsNull(cycle.getId()))
          .thenReturn(List.of(premier, second));

      service.regulariser(cycle);

      assertThat(premier.getMontantRecu().add(second.getMontantRecu()))
          .isEqualByComparingTo("3000");
      assertThat(premier.getMontantRecu()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName(
        "avance du gestionnaire : la régularisation rembourse l'avance, le bénéficiaire ne reçoit"
            + " rien de plus, personne n'est notifié")
    void remboursementAvance() {
      cycle.setModeRemise(ModeRemise.AVANCE_GESTIONNAIRE);
      cycle.setMontantJackpot(new BigDecimal("3000"));
      cycle.setMontantCommission(BigDecimal.ZERO);
      cycle.setMontantNet(new BigDecimal("3600"));
      cycle.setMontantNetPrevu(new BigDecimal("3600"));
      cycle.setMontantAvance(new BigDecimal("600"));
      valides(4);
      commissionDe("10");

      service.regulariser(cycle);

      assertThat(cycle.getMontantNet()).isEqualByComparingTo("3600");
      assertThat(cycle.getMontantAvance()).isEqualByComparingTo("0");
      assertThat(cycle.getMontantCommission()).isEqualByComparingTo("400");
      verify(gagnantRepository, never()).findByCycle_IdAndDeletedAtIsNull(any());
      verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("gagnants pas encore désignés : le net du tour est mis à jour, sans notification")
    void sansGagnant() {
      cycle.setMontantNet(new BigDecimal("2000"));
      valides(3);
      when(commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(List.of());
      when(gagnantRepository.findByCycle_IdAndDeletedAtIsNull(cycle.getId())).thenReturn(List.of());

      service.regulariser(cycle);

      assertThat(cycle.getMontantNet()).isEqualByComparingTo("3000");
      verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tour non remis ou tontine événementielle : rien ne change")
    void horsPerimetre() {
      cycle.setStatut(CycleStatut.EN_COURS);
      service.regulariser(cycle);

      cycle.setStatut(CycleStatut.TERMINE);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      service.regulariser(cycle);

      verify(cycleRepository, never()).save(any());
      verify(cotisationRepository, never()).findByCycle_IdAndStatutAndDeletedAtIsNull(any(), any());
    }
  }
}
