package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.dto.EtatTour;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.impl.SuiviTourServiceImpl;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.EcheanceCycleRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SuiviTourService : jackpot prévu, tous payés, échéance courante (B32)")
class SuiviTourServiceImplTest {

  @Mock private CotisationRepository cotisationRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private EcheanceCycleRepository echeanceRepository;
  @InjectMocks private SuiviTourServiceImpl service;

  private Tontine tontine;
  private CycleTontine cycle;
  private EcheanceCycle passee;
  private EcheanceCycle courante;
  private List<TontineMembre> actifs;

  /** Échéance d'une semaine, identifiée (les tests comparent les identifiants). */
  private EcheanceCycle echeance(int numero, LocalDate debut) {
    EcheanceCycle e =
        EcheanceCycle.builder()
            .id(UUID.randomUUID())
            .cycle(cycle)
            .numero(numero)
            .dateDebut(debut)
            .echeanceAt(debut.plusDays(6).atTime(23, 59))
            .build();
    cycle.getEcheances().add(e);
    return e;
  }

  private void membres(int n) {
    actifs = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      actifs.add(Fixtures.membre(tontine, Fixtures.user("M" + i), i));
    }
    when(membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
            tontine.getId(), MembreStatut.ACTIF))
        .thenReturn(actifs);
  }

  private Object[] ligne(EcheanceCycle e, TontineMembre m, CotisationStatut statut) {
    return new Object[] {e.getId(), m.getId(), statut};
  }

  @BeforeEach
  void setUp() {
    tontine = Fixtures.tontine(Fixtures.user("Gestionnaire"));
    cycle = Fixtures.cycle(tontine, 1, CycleStatut.EN_COURS);
    // Deux échéances hebdomadaires : la première échue, la seconde en cours
    passee = echeance(1, LocalDate.now().minusDays(8));
    courante = echeance(2, LocalDate.now().minusDays(1));
  }

  @Test
  @DisplayName("tous payés : chaque membre actif VALIDÉ sur chaque échéance ; jackpot prévu × N")
  void tousPayes() {
    membres(2);
    when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId()))
        .thenReturn(
            List.of(
                ligne(passee, actifs.get(0), CotisationStatut.VALIDE),
                ligne(passee, actifs.get(1), CotisationStatut.VALIDE),
                ligne(courante, actifs.get(0), CotisationStatut.VALIDE),
                ligne(courante, actifs.get(1), CotisationStatut.VALIDE)));

    EtatTour etat = service.etat(cycle);

    assertThat(etat.tousPayes()).isTrue();
    assertThat(etat.membresActifs()).isEqualTo(2);
    assertThat(etat.nombreEcheances()).isEqualTo(2);
    // 1 000 F × 2 membres × 2 échéances
    assertThat(etat.montantJackpotPrevu()).isEqualByComparingTo("4000");
    assertThat(etat.echeanceCouranteId()).isEqualTo(courante.getId());
    assertThat(etat.numeroEcheanceCourante()).isEqualTo(2);
    assertThat(etat.membresAyantPayeEcheance()).isEqualTo(2);
    assertThat(etat.membresEnAttenteEcheance()).isZero();
  }

  @Test
  @DisplayName("une déclaration EN_ATTENTE ou une échéance en retard : pas tous payés")
  void pasTousPayes() {
    membres(2);
    when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId()))
        .thenReturn(
            List.of(
                ligne(passee, actifs.get(0), CotisationStatut.VALIDE),
                ligne(passee, actifs.get(1), CotisationStatut.EN_RETARD),
                ligne(courante, actifs.get(0), CotisationStatut.VALIDE),
                ligne(courante, actifs.get(1), CotisationStatut.EN_ATTENTE)));

    EtatTour etat = service.etat(cycle);

    assertThat(etat.tousPayes()).isFalse();
    assertThat(etat.membresAyantPayeEcheance()).isEqualTo(1);
    assertThat(etat.membresEnAttenteEcheance()).isEqualTo(1);
  }

  @Test
  @DisplayName("aucun membre actif, ou aucune échéance : jamais « tous payés »")
  void sansMembreOuSansEcheance() {
    membres(0);
    when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId())).thenReturn(List.of());
    assertThat(service.etat(cycle).tousPayes()).isFalse();

    membres(1);
    cycle.getEcheances().clear();
    EtatTour sansEcheance = service.etat(cycle);
    assertThat(sansEcheance.tousPayes()).isFalse();
    assertThat(sansEcheance.echeanceCouranteId()).isNull();
    assertThat(sansEcheance.numeroEcheanceCourante()).isNull();
  }

  @Test
  @DisplayName("tour non EN_COURS : jackpot prévu seulement, cotisations non lues")
  void tourNonEnCours() {
    membres(3);
    cycle.setStatut(CycleStatut.TERMINE);

    EtatTour etat = service.etat(cycle);

    assertThat(etat.tousPayes()).isFalse();
    assertThat(etat.montantJackpotPrevu()).isEqualByComparingTo("6000");
    assertThat(etat.echeanceCouranteId()).isNull();
    verify(cotisationRepository, never()).findEcheanceMembreStatutByCycle(cycle.getId());
  }

  @Test
  @DisplayName("montant libre sans plancher : pas de jackpot prévu")
  void montantLibreSansPlancher() {
    tontine.setMontantLibre(true);
    tontine.setMontantMinimum(null);
    membres(2);
    when(cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId())).thenReturn(List.of());

    assertThat(service.etat(cycle).montantJackpotPrevu()).isNull();
  }

  @Test
  @DisplayName("a payé l'échéance courante : cotisation active sur la dernière échéance ouverte")
  void aPayeEcheanceCourante() {
    UUID membreId = UUID.randomUUID();
    when(cotisationRepository.existsActiveByEcheanceAndMembre(courante.getId(), membreId))
        .thenReturn(true);

    assertThat(service.aPayeEcheanceCourante(cycle, membreId)).isTrue();

    cycle.getEcheances().clear();
    assertThat(service.aPayeEcheanceCourante(cycle, membreId)).isFalse();
  }

  @Test
  @DisplayName("tours payés : toutes les échéances du tour VALIDÉES, sinon le tour ne compte pas")
  void toursPayes() {
    UUID m1 = UUID.randomUUID();
    UUID m2 = UUID.randomUUID();
    UUID tourA = UUID.randomUUID();
    UUID tourB = UUID.randomUUID();
    UUID tourSansEcheance = UUID.randomUUID();
    List<UUID> membreIds = List.of(m1, m2);
    List<UUID> tontineIds = List.of(tontine.getId());
    when(echeanceRepository.countByCycleForTontines(tontineIds))
        .thenReturn(List.of(new Object[] {tourA, 4L}, new Object[] {tourB, 1L}));
    when(cotisationRepository.countValideByMembreAndCycle(membreIds))
        .thenReturn(
            List.of(
                new Object[] {m1, tourA, 4L},
                new Object[] {m1, tourB, 1L},
                new Object[] {m1, tourSansEcheance, 1L},
                new Object[] {m2, tourA, 3L}));

    Map<UUID, Long> r = service.toursPayes(membreIds, tontineIds);

    assertThat(r).containsEntry(m1, 3L).doesNotContainKey(m2);
    assertThat(service.toursPayes(List.of(), tontineIds)).isEmpty();
    assertThat(service.toursPayes(membreIds, List.of())).isEmpty();
  }
}
