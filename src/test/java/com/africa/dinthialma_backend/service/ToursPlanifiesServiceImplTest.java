package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.service.impl.ToursPlanifiesServiceImpl;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import com.africa.dinthialma_backend.tontine.service.interfaces.ToursPlanifiesService.Bilan;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToursPlanifiesService : ouverture à la date et remise automatique (B33, lot 3)")
class ToursPlanifiesServiceImplTest {

  @Mock private CycleTontineRepository cycleRepository;
  @Mock private CycleService cycleService;
  @InjectMocks private ToursPlanifiesServiceImpl service;

  private final LocalDateTime maintenant = LocalDateTime.of(2026, 12, 6, 0, 5);
  private final LocalDate jour = maintenant.toLocalDate();
  private Tontine t1;
  private Tontine t2;

  @BeforeEach
  void setUp() {
    User gestionnaire = Fixtures.user("Gestionnaire");
    t1 = Fixtures.tontine(gestionnaire);
    t2 = Fixtures.tontine(gestionnaire);
  }

  @Test
  @DisplayName("une ouverture par tontine, une remise par tour ; un échec n'arrête pas les autres")
  void passage() {
    when(cycleRepository.findAOuvrir(jour))
        .thenReturn(
            List.of(
                Fixtures.cycle(t1, 2, CycleStatut.EN_ATTENTE),
                Fixtures.cycle(t1, 3, CycleStatut.EN_ATTENTE),
                Fixtures.cycle(t2, 2, CycleStatut.EN_ATTENTE)));
    when(cycleService.ouvrirToursArrives(t1.getId(), jour)).thenReturn(2);
    when(cycleService.ouvrirToursArrives(t2.getId(), jour))
        .thenThrow(new IllegalStateException("verrou"));
    CycleTontine remis = Fixtures.cycle(t1, 1, CycleStatut.EN_COURS);
    CycleTontine impossible = Fixtures.cycle(t2, 1, CycleStatut.EN_COURS);
    CycleTontine enEchec = Fixtures.cycle(t2, 2, CycleStatut.EN_COURS);
    when(cycleRepository.findARemettre(maintenant, false))
        .thenReturn(List.of(remis, impossible, enEchec));
    when(cycleService.remettreAEcheance(t1.getId(), remis.getId(), maintenant)).thenReturn(true);
    when(cycleService.remettreAEcheance(t2.getId(), impossible.getId(), maintenant))
        .thenReturn(false);
    when(cycleService.remettreAEcheance(t2.getId(), enEchec.getId(), maintenant))
        .thenThrow(new IllegalStateException("base"));

    Bilan bilan = service.traiter(maintenant);

    assertThat(bilan.toursOuverts()).isEqualTo(2);
    assertThat(bilan.toursRemis()).isEqualTo(1);
    verify(cycleService, times(1)).ouvrirToursArrives(t1.getId(), jour);
  }

  @Test
  @DisplayName("passage planifié : rien à faire, puis un tour ouvert")
  void planifie() {
    when(cycleRepository.findAOuvrir(any())).thenReturn(List.of());
    when(cycleRepository.findARemettre(any(), anyBoolean())).thenReturn(List.of());
    service.planifie();

    when(cycleRepository.findAOuvrir(any()))
        .thenReturn(List.of(Fixtures.cycle(t1, 2, CycleStatut.EN_ATTENTE)));
    when(cycleService.ouvrirToursArrives(eq(t1.getId()), any())).thenReturn(1);
    service.planifie();

    verify(cycleRepository, times(2)).findARemettre(any(), anyBoolean());
  }

  @Test
  @DisplayName(
      "remises impossibles déjà signalées : reprises au passage de l'heure pleine seulement")
  void heurePleine() {
    service.traiter(LocalDateTime.of(2026, 12, 6, 14, 0));
    verify(cycleRepository).findARemettre(LocalDateTime.of(2026, 12, 6, 14, 0), true);
    service.traiter(LocalDateTime.of(2026, 12, 6, 14, 5));
    verify(cycleRepository).findARemettre(LocalDateTime.of(2026, 12, 6, 14, 5), false);
  }
}
