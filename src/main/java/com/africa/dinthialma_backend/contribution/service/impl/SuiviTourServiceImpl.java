package com.africa.dinthialma_backend.contribution.service.impl;

import com.africa.dinthialma_backend.common.util.Fuseau;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.dto.EtatTour;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.interfaces.SuiviTourService;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.tontine.calendrier.EcheancesTour;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.EcheanceCycleRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuiviTourServiceImpl implements SuiviTourService {

  private final CotisationRepository cotisationRepository;
  private final TontineMembreRepository membreRepository;
  private final EcheanceCycleRepository echeanceRepository;

  @Override
  public EtatTour etat(CycleTontine cycle) {
    Tontine tontine = cycle.getTontine();
    List<TontineMembre> actifs =
        membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(
            tontine.getId(), MembreStatut.ACTIF);
    List<EcheanceCycle> echeances = EcheancesTour.actives(cycle);
    BigDecimal parMembre = tontine.getMontantAttenduParMembre();
    BigDecimal prevu =
        parMembre == null
            ? null
            : parMembre.multiply(BigDecimal.valueOf((long) actifs.size() * echeances.size()));

    if (cycle.getStatut() != CycleStatut.EN_COURS) {
      return new EtatTour(actifs.size(), echeances.size(), prevu, false, null, null, 0, 0);
    }

    // Paires (échéance, membre) par statut, sur les cotisations actives du tour
    Set<String> validees = new HashSet<>();
    List<Object[]> lignes = cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId());
    for (Object[] ligne : lignes) {
      if (ligne[2] == CotisationStatut.VALIDE) {
        validees.add(cle(ligne[0], ligne[1]));
      }
    }
    boolean tousPayes =
        !actifs.isEmpty()
            && !echeances.isEmpty()
            && echeances.stream()
                .allMatch(
                    e ->
                        actifs.stream()
                            .allMatch(m -> validees.contains(cle(e.getId(), m.getId()))));

    EcheanceCycle courante = EcheancesTour.courante(cycle, Fuseau.maintenant()).orElse(null);
    long payees = 0;
    long enAttente = 0;
    if (courante != null) {
      for (Object[] ligne : lignes) {
        if (courante.getId().equals(ligne[0])) {
          if (ligne[2] == CotisationStatut.VALIDE) {
            payees++;
          } else if (ligne[2] == CotisationStatut.EN_ATTENTE) {
            enAttente++;
          }
        }
      }
    }
    return new EtatTour(
        actifs.size(),
        echeances.size(),
        prevu,
        tousPayes,
        courante != null ? courante.getId() : null,
        courante != null ? courante.getNumero() : null,
        payees,
        enAttente);
  }

  @Override
  public boolean aPayeEcheanceCourante(CycleTontine cycle, UUID membreId) {
    return EcheancesTour.courante(cycle, Fuseau.maintenant())
        .map(e -> cotisationRepository.existsActiveByEcheanceAndMembre(e.getId(), membreId))
        .orElse(false);
  }

  @Override
  public Map<UUID, Long> toursPayes(List<UUID> membreIds, List<UUID> tontineIds) {
    if (membreIds.isEmpty() || tontineIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Long> echeancesParTour = new HashMap<>();
    for (Object[] ligne : echeanceRepository.countByCycleForTontines(tontineIds)) {
      echeancesParTour.put((UUID) ligne[0], (Long) ligne[1]);
    }
    Map<UUID, Long> toursPayes = new HashMap<>();
    for (Object[] ligne : cotisationRepository.countValideByMembreAndCycle(membreIds)) {
      long dues = Math.max(1L, echeancesParTour.getOrDefault((UUID) ligne[1], 1L));
      if ((Long) ligne[2] >= dues) {
        toursPayes.merge((UUID) ligne[0], 1L, Long::sum);
      }
    }
    return toursPayes;
  }

  private static String cle(Object echeanceId, Object membreId) {
    return echeanceId + "|" + membreId;
  }
}
