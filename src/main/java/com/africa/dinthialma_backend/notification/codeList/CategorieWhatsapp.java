package com.africa.dinthialma_backend.notification.codeList;

/**
 * Familles de messages WhatsApp. Seules les familles listées dans {@code openwa.categories} partent
 * réellement ; les autres sont ignorées (les notifications in-app ne sont pas concernées).
 */
public enum CategorieWhatsapp {
  /** Codes de vérification : inscription, mot de passe oublié, PIN oublié, changement de numéro. */
  OTP,

  /** Invitation d'un membre pré-inscrit ajouté à une tontine. */
  INVITATION,

  /** Annonce des gagnants d'un jackpot et distribution finale d'une événementielle. */
  JACKPOT,

  /** Suivi d'une cotisation : déclaration, encaissement, validation, refus. */
  COTISATION,

  /** Rappels planifiés : rappel quotidien de 8h, veille et N heures avant l'échéance. */
  RAPPEL
}
