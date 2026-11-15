package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.common.util.Montants;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.dto.NotificationLiens;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.tontine.calendrier.EcheancesTour;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.ModeRemise;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.RemiseApercuResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleGagnant;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.entity.TontineCommission;
import com.africa.dinthialma_backend.tontine.remise.CalculRemise;
import com.africa.dinthialma_backend.tontine.repository.CycleGagnantRepository;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineCommissionRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.RemiseService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RemiseServiceImpl implements RemiseService {

  private final AccessControl accessControl;
  private final TontineRepository tontineRepository;
  private final CycleTontineRepository cycleRepository;
  private final CycleGagnantRepository gagnantRepository;
  private final CotisationRepository cotisationRepository;
  private final TontineCommissionRepository commissionRepository;
  private final TontineMembreRepository membreRepository;
  private final NotificationService notificationService;

  // ─── Aperçu avant remise ─────────────────────────────────────────────────

  @Override
  @Transactional(readOnly = true)
  public RemiseApercuResponse apercu(String keycloakId, UUID tontineId, UUID cycleId)
      throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine =
        tontineRepository
            .findByIdAndDeletedAtIsNull(tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
    if (!accessControl.estSuperAdmin(caller)
        && !tontine.getCreePar().getId().equals(caller.getId())) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }
    CycleTontine cycle =
        cycleRepository
            .findByIdAndTontine_IdAndDeletedAtIsNull(cycleId, tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.CYCLE_NOT_FOUND));
    if (tontine.getTontineType() != TontineType.ROTATIVE
        || cycle.getStatut() != CycleStatut.EN_COURS) {
      throw new BadRequestException(
          "L'aperçu de remise concerne un tour EN_COURS d'une tontine rotative");
    }

    List<TontineMembre> actifs =
        membreRepository.findByTontine_IdAndStatutAndDeletedAtIsNull(tontineId, MembreStatut.ACTIF);
    List<EcheanceCycle> echeances = EcheancesTour.actives(cycle);
    BigDecimal parMembre = tontine.getMontantAttenduParMembre();
    BigDecimal collecte = collecte(cycle);
    BigDecimal prevu =
        parMembre == null
            ? collecte
            : parMembre.multiply(BigDecimal.valueOf((long) actifs.size() * echeances.size()));
    List<TontineCommission> commissions =
        commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontineId);
    int gagnants = Math.max(1, tontine.getNombreGagnants());

    List<RemiseApercuResponse.Option> options = new ArrayList<>();
    for (ModeRemise mode : ModeRemise.values()) {
      CalculRemise.Resultat r = CalculRemise.remise(mode, prevu, collecte, commissions);
      options.add(
          RemiseApercuResponse.Option.builder()
              .modeRemise(mode)
              .possible(r.possible())
              .montantNet(r.montantNet())
              .montantBeneficiaire(
                  Montants.repartir(r.montantNet().max(BigDecimal.ZERO), gagnants).get(0))
              .commission(r.commission())
              .avanceGestionnaire(r.avanceGestionnaire())
              .resteARecevoir(r.resteARecevoir())
              .montantARecuperer(r.aRecuperer())
              .build());
    }

    return RemiseApercuResponse.builder()
        .cycleId(cycle.getId())
        .numeroCycle(cycle.getNumeroCycle())
        .jackpotPrevu(prevu)
        .collecte(collecte)
        .manquant(CalculRemise.manquant(prevu, collecte))
        .modeRemiseParDefaut(tontine.getModeRemiseEffectif())
        .retardataires(retardataires(cycle, actifs, echeances, parMembre))
        .options(options)
        .build();
  }

  /** Membres actifs à qui il manque une cotisation VALIDÉE sur au moins une échéance du tour. */
  private List<RemiseApercuResponse.Retardataire> retardataires(
      CycleTontine cycle,
      List<TontineMembre> actifs,
      List<EcheanceCycle> echeances,
      BigDecimal parMembre) {
    Set<String> validees = new HashSet<>();
    for (Object[] ligne : cotisationRepository.findEcheanceMembreStatutByCycle(cycle.getId())) {
      if (ligne[2] == CotisationStatut.VALIDE) {
        validees.add(ligne[0] + "|" + ligne[1]);
      }
    }
    List<RemiseApercuResponse.Retardataire> retardataires = new ArrayList<>();
    for (TontineMembre membre : actifs) {
      int manquees =
          (int)
              echeances.stream()
                  .filter(e -> !validees.contains(e.getId() + "|" + membre.getId()))
                  .count();
      if (manquees > 0) {
        retardataires.add(
            RemiseApercuResponse.Retardataire.builder()
                .membreId(membre.getId())
                .firstName(membre.getUser().getFirstName())
                .lastName(membre.getUser().getLastName())
                .echeancesManquees(manquees)
                .montant(
                    parMembre != null ? parMembre.multiply(BigDecimal.valueOf(manquees)) : null)
                .build());
      }
    }
    return retardataires;
  }

  // ─── Régularisation sur un tour remis ────────────────────────────────────

  @Override
  public void regulariser(CycleTontine cycle) {
    Tontine tontine = cycle.getTontine();
    if (tontine.getTontineType() != TontineType.ROTATIVE
        || cycle.getStatut() != CycleStatut.TERMINE) {
      return;
    }
    BigDecimal collecte = collecte(cycle);
    BigDecimal dejaRemis = zeroSiNul(cycle.getMontantNet());
    CalculRemise.Resultat etat =
        CalculRemise.apresRegularisation(
            dejaRemis,
            cycle.getMontantNetPrevu(),
            collecte,
            commissionRepository.findByTontine_IdAndDeletedAtIsNull(tontine.getId()));
    BigDecimal complement = etat.montantNet().subtract(dejaRemis);

    cycle.setMontantJackpot(collecte);
    cycle.setMontantCommission(etat.commission());
    cycle.setMontantNet(etat.montantNet());
    cycle.setMontantAvance(etat.avanceGestionnaire());
    cycleRepository.save(cycle);
    log.info(
        "Régularisation sur tour remis – cycleId={} collecte={} net={} complément={} avance={}",
        cycle.getId(),
        collecte,
        etat.montantNet(),
        complement,
        etat.avanceGestionnaire());

    if (complement.signum() > 0) {
      crediterGagnants(tontine, cycle, complement);
    }
  }

  /**
   * Répartit le complément entre les gagnants du tour (francs entiers, somme exacte) et prévient
   * chacun, puis le gestionnaire. Sans gagnant désigné, le net du tour suffit : il sera partagé à
   * la désignation.
   */
  private void crediterGagnants(Tontine tontine, CycleTontine cycle, BigDecimal complement) {
    List<CycleGagnant> gagnants = gagnantRepository.findByCycle_IdAndDeletedAtIsNull(cycle.getId());
    if (gagnants.isEmpty()) {
      return;
    }
    List<BigDecimal> parts = Montants.repartir(complement, gagnants.size());
    List<String> noms = new ArrayList<>();
    for (int i = 0; i < gagnants.size(); i++) {
      CycleGagnant gagnant = gagnants.get(i);
      gagnant.setMontantRecu(zeroSiNul(gagnant.getMontantRecu()).add(parts.get(i)));
      gagnantRepository.save(gagnant);
      User beneficiaire = gagnant.getMembre().getUser();
      noms.add(beneficiaire.getFirstName() + " " + beneficiaire.getLastName());
      if (parts.get(i).signum() > 0) {
        notificationService.notify(
            beneficiaire.getId(),
            NotificationType.JACKPOT_DISTRIBUE,
            "Complément de jackpot",
            "Un retard a été régularisé sur le tour #"
                + cycle.getNumeroCycle()
                + " : vous recevez un complément de "
                + parts.get(i).toPlainString()
                + " FCFA · "
                + tontine.getNom(),
            tontine.getId(),
            NotificationLiens.cycle(cycle.getId(), gagnant.getMembre().getId()));
      }
    }
    notificationService.notify(
        tontine.getCreePar().getId(),
        NotificationType.COMPLEMENT_A_REMETTRE,
        "Complément à remettre",
        "Retard régularisé sur le tour #"
            + cycle.getNumeroCycle()
            + " : "
            + complement.toPlainString()
            + " FCFA à remettre à "
            + String.join(", ", noms)
            + " · "
            + tontine.getNom(),
        tontine.getId(),
        NotificationLiens.cycle(cycle.getId(), null));
  }

  private BigDecimal collecte(CycleTontine cycle) {
    return cotisationRepository
        .findByCycle_IdAndStatutAndDeletedAtIsNull(cycle.getId(), CotisationStatut.VALIDE)
        .stream()
        .map(c -> zeroSiNul(c.getMontant()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal zeroSiNul(BigDecimal montant) {
    return montant != null ? montant : BigDecimal.ZERO;
  }
}
