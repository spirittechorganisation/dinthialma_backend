package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.common.util.Fuseau;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.CycleService;
import com.africa.dinthialma_backend.tontine.service.interfaces.ToursPlanifiesService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Ouverture des tours à leur date et remise automatique à l'échéance (B33, lot 3).
 *
 * <p>Pas de transaction ici : chaque appel à {@link CycleService} ouvre la sienne et verrouille la
 * tontine, ce qui sérialise ce traitement avec le gestionnaire et entre instances.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToursPlanifiesServiceImpl implements ToursPlanifiesService {

  /** Intervalle du planificateur, en minutes. */
  private static final int MINUTES_ENTRE_PASSAGES = 5;

  private final CycleTontineRepository cycleRepository;
  private final CycleService cycleService;

  @Override
  @Scheduled(cron = "0 */5 * * * *", zone = Fuseau.NOM)
  public void planifie() {
    Bilan bilan = traiter(Fuseau.maintenant());
    if (bilan.toursOuverts() > 0 || bilan.toursRemis() > 0) {
      log.info(
          "Tours planifiés – {} ouvert(s), {} remis", bilan.toursOuverts(), bilan.toursRemis());
    }
  }

  @Override
  public Bilan traiter(LocalDateTime maintenant) {
    return new Bilan(ouvrir(maintenant.toLocalDate()), remettre(maintenant));
  }

  private int ouvrir(LocalDate aujourdHui) {
    Set<UUID> tontines = new LinkedHashSet<>();
    cycleRepository.findAOuvrir(aujourdHui).forEach(c -> tontines.add(c.getTontine().getId()));
    int ouverts = 0;
    for (UUID tontineId : tontines) {
      try {
        ouverts += cycleService.ouvrirToursArrives(tontineId, aujourdHui);
      } catch (RuntimeException e) {
        log.error("Ouverture des tours de la tontine {} en échec : {}", tontineId, e.getMessage());
      }
    }
    return ouverts;
  }

  private int remettre(LocalDateTime maintenant) {
    int remis = 0;
    // Remise impossible déjà signalée : retentée à chaque cotisation validée ; ici une fois par
    // heure seulement, pour les autres changements (réglage, membres, commission)
    boolean heurePleine = maintenant.getMinute() < MINUTES_ENTRE_PASSAGES;
    for (CycleTontine cycle : cycleRepository.findARemettre(maintenant, heurePleine)) {
      try {
        if (cycleService.remettreAEcheance(cycle.getTontine().getId(), cycle.getId(), maintenant)) {
          remis++;
        }
      } catch (RuntimeException e) {
        log.error("Remise automatique du tour {} en échec : {}", cycle.getId(), e.getMessage());
      }
    }
    return remis;
  }
}
