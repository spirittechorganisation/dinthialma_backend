package com.africa.dinthialma_backend.contribution.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Suivi d'un tour par échéance (B32, lot 3), calculé à la demande (rien n'est enregistré).
 *
 * @param membresActifs membres ACTIFS de la tontine (base du jackpot prévu)
 * @param nombreEcheances échéances actives du tour
 * @param montantJackpotPrevu jackpot brut prévu du tour = montant attendu par membre × membres
 *     actifs × échéances (tous gagnants confondus, comparable à {@code montantJackpot}) ; null si
 *     montant libre sans plancher
 * @param tousPayes tour EN_COURS dont chaque membre actif a une cotisation VALIDÉE sur chaque
 *     échéance ; toujours faux hors tour EN_COURS
 * @param echeanceCouranteId échéance payée par défaut (tour EN_COURS) ; null sinon
 * @param numeroEcheanceCourante numéro de cette échéance ; null sinon
 * @param membresAyantPayeEcheance membres dont la cotisation de l'échéance courante est VALIDÉE
 * @param membresEnAttenteEcheance membres dont la cotisation de l'échéance courante est EN_ATTENTE
 */
public record EtatTour(
    long membresActifs,
    int nombreEcheances,
    BigDecimal montantJackpotPrevu,
    boolean tousPayes,
    UUID echeanceCouranteId,
    Integer numeroEcheanceCourante,
    long membresAyantPayeEcheance,
    long membresEnAttenteEcheance) {}
