package com.africa.dinthialma_backend.notification.service;

import com.africa.dinthialma_backend.notification.codeList.CategorieWhatsapp;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Service WhatsApp – OpenWA (wa-automate).
 *
 * <p>Canal principal de notifications : OTP, invitations tontine, rappels cotisation, jackpot. Mode
 * mock activé par défaut en développement via {@code openwa.mock=true}.
 */
@Slf4j
@Service
public class WhatsappService {

  @Value("${openwa.api-url}")
  private String apiUrl;

  @Value("${openwa.mock}")
  private boolean mock;

  @Value("${dinthialma.app.url:https://www.dinthialma.com}")
  private String appUrl;

  /**
   * Familles de messages réellement envoyées ({@code OPENWA_CATEGORIES}). Les autres sont ignorées
   * : utile pour rester sous les quotas d'un compte de test.
   */
  @Value("${openwa.categories:OTP,INVITATION,JACKPOT}")
  private Set<CategorieWhatsapp> categories = EnumSet.allOf(CategorieWhatsapp.class);

  /** Vrai si les messages de cette famille partent réellement. */
  public boolean estActive(CategorieWhatsapp categorie) {
    return categories.contains(categorie);
  }

  /** Envoie le message si sa famille est active, sinon l'ignore (trace en debug). */
  private void send(CategorieWhatsapp categorie, String phone, String message) {
    if (!estActive(categorie)) {
      log.debug("WhatsApp {} désactivé – message non envoyé à {}", categorie, phone);
      return;
    }
    send(phone, message);
  }

  /** Message de suivi d'une cotisation (déclaration, encaissement, validation, refus). */
  public void sendCotisation(String phone, String message) {
    send(CategorieWhatsapp.COTISATION, phone, message);
  }

  /** Rappel d'échéance planifié (veille, N heures avant). */
  public void sendRappelEcheance(String phone, String message) {
    send(CategorieWhatsapp.RAPPEL, phone, message);
  }

  private final RestClient restClient = RestClient.create();

  /**
   * Envoie un message WhatsApp via l'API OpenWA, sans filtre de famille. En mode mock, affiche le
   * message dans les logs.
   *
   * @param phone numéro sans le «+» (ex : 221781234567)
   * @param message contenu du message (supporte le formatage WhatsApp : *gras*, _italique_)
   */
  public void send(String phone, String message) {
    if (mock) {
      log.info("╔══════════════════════════════════════════╗");
      log.info("║      WHATSAPP MOCK (non envoyé)          ║");
      log.info("║  À      : {}", phone);
      log.info("║  Message: {}", message);
      log.info("╚══════════════════════════════════════════╝");
      return;
    }

    try {
      String chatId = phone + "@c.us";
      restClient
          .post()
          .uri(apiUrl + "/message/sendText/" + chatId)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("text", message))
          .retrieve()
          .toBodilessEntity();
      log.info("WhatsApp envoyé à {} – message : {}", phone, message);
    } catch (Exception e) {
      log.error("Échec envoi WhatsApp à {} : {}", phone, e.getMessage());
    }
  }

  /** Envoie un OTP par WhatsApp. */
  public void sendOtp(String phone, String otp) {
    String message =
        "🔐 *Dinthialma* – Votre code de vérification : *"
            + otp
            + "*\nValable 10 minutes. Ne le partagez pas.";
    send(CategorieWhatsapp.OTP, phone, message);
  }

  /** Envoie un rappel de cotisation par WhatsApp. */
  public void sendContributionReminder(String phone, String tontineName, String amount) {
    String message =
        "💰 *Dinthialma* – Rappel : votre cotisation de *"
            + amount
            + "* pour la tontine *"
            + tontineName
            + "* est en attente.\n👉 Connectez-vous pour régulariser : "
            + appUrl;
    send(CategorieWhatsapp.RAPPEL, phone, message);
  }

  /** Informe un nouveau membre pré-inscrit de son ajout à une tontine. */
  public void sendTontineInvite(String phone, String tontineName) {
    String message =
        "👋 Vous avez été ajouté(e) à la tontine *"
            + tontineName
            + "* sur *Dinthialma*.\nInscrivez-vous pour accéder à votre compte et suivre vos cotisations :\n👉 "
            + appUrl;
    send(CategorieWhatsapp.INVITATION, phone, message);
  }

  /** Notifie le bénéficiaire du prochain jackpot. */
  public void sendJackpotNotification(String phone, String tontineName, String amount) {
    String message =
        "🎉 *Félicitations !* Vous êtes le(la) bénéficiaire du prochain jackpot de la tontine *"
            + tontineName
            + "*.\nMontant prévu : *"
            + amount
            + "*.\n👉 Consultez les détails sur : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /**
   * Rappel cotisation EN_ATTENTE pour une tontine EVENEMENTIELLE.
   *
   * <p>Envoyé quotidiennement aux membres dont la cotisation est en attente de validation par
   * l'admin.
   */
  public void sendEvenementielleRappelAttente(
      String phone, String tontineName, String nomEvenement, long joursRestants, String amount) {
    String message =
        "💰 *Dinthialma* – Rappel cotisation\n"
            + "Votre versement de *"
            + amount
            + "* pour la tontine *"
            + tontineName
            + "* est en attente de validation.\n"
            + "📅 *"
            + nomEvenement
            + "* dans *J-"
            + joursRestants
            + "*\nContactez votre gestionnaire si besoin.\n👉 "
            + appUrl;
    send(CategorieWhatsapp.RAPPEL, phone, message);
  }

  /**
   * Rappel de période non cotisée pour une tontine EVENEMENTIELLE.
   *
   * <p>Envoyé quotidiennement (hors jours clés) aux membres qui n'ont pas encore cotisé sur le
   * sous-cycle courant.
   */
  public void sendEvenementielleRappelPeriode(
      String phone,
      String tontineName,
      String nomEvenement,
      long joursRestants,
      String montantTotal) {
    String message =
        "⏰ *Dinthialma* – La tontine *"
            + tontineName
            + "* vous attend !\n"
            + "Vous n'avez pas encore cotisé pour cette période.\n"
            + "📅 *"
            + nomEvenement
            + "* dans *J-"
            + joursRestants
            + "*\n"
            + "💵 Votre épargne totale à ce jour : *"
            + montantTotal
            + "*\n👉 Enregistrez votre cotisation : "
            + appUrl;
    send(CategorieWhatsapp.RAPPEL, phone, message);
  }

  /**
   * Rappel urgent J-30 / J-7 / J-3 / J-1 pour une tontine EVENEMENTIELLE.
   *
   * <p>Envoyé à tous les membres actifs aux jalons clés avant la date de l'événement.
   */
  public void sendEvenementielleUrgent(
      String phone,
      String tontineName,
      String nomEvenement,
      long joursRestants,
      String montantTotal) {
    String emoji;
    String intro;
    String suffix;
    if (joursRestants == 1) {
      emoji = "🔔";
      intro = "*" + nomEvenement + "* c'est demain !";
      suffix = "La clôture et la distribution ont lieu demain. Bonne célébration ! 🎉";
    } else if (joursRestants == 3) {
      emoji = "🚨";
      intro = "*" + nomEvenement + "* dans *3 jours* !";
      suffix = "Dernière chance de cotiser avant la clôture et la distribution. Bon courage ! 💪";
    } else if (joursRestants == 7) {
      emoji = "⚠️";
      intro = "Plus que *7 jours* avant *" + nomEvenement + "* !";
      suffix = "Pensez à enregistrer votre cotisation si ce n'est pas encore fait 🙏";
    } else {
      emoji = "📢";
      intro = "Dans *" + joursRestants + " jours* : *" + nomEvenement + "* !";
      suffix = "Continuez à cotiser régulièrement pour atteindre votre objectif 💪";
    }
    String message =
        emoji
            + " *Dinthialma* – "
            + intro
            + "\nTontine : *"
            + tontineName
            + "*\n"
            + "💵 Votre épargne à ce jour : *"
            + montantTotal
            + "*\n"
            + suffix
            + "\n👉 "
            + appUrl;
    send(CategorieWhatsapp.RAPPEL, phone, message);
  }

  /** Notifie chaque membre de la distribution finale d'une tontine événementielle. */
  public void sendDistributionFinale(
      String phone, String tontineName, String montantNet, String montantCotise) {
    String message =
        "🎉 *Dinthialma* – La tontine événementielle *"
            + tontineName
            + "* est clôturée !\n"
            + "Vous recevez *"
            + montantNet
            + " FCFA* (votre mise totale : "
            + montantCotise
            + " FCFA).\n👉 Consultez les détails sur : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Notifie l'admin créateur du résumé de clôture d'une tontine événementielle. */
  public void sendDistributionFinaleAdmin(
      String phone, String tontineName, String cagnotteTotal, int nbMembres) {
    String message =
        "✅ *Dinthialma* – Tontine *"
            + tontineName
            + "* clôturée avec succès.\n"
            + "Cagnotte totale : *"
            + cagnotteTotal
            + " FCFA* distribuée entre "
            + nbMembres
            + " membre(s).\n👉 Voir le rapport : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Notifie l'admin de la désignation manuelle d'un bénéficiaire de jackpot. */
  public void sendJackpotManuelAdmin(
      String phone, String noms, int numeroCycle, String tontineName) {
    String message =
        "✅ *Dinthialma* – Vous avez désigné *"
            + noms
            + "* comme gagnant(s) du jackpot (cycle "
            + numeroCycle
            + ", tontine *"
            + tontineName
            + "*).\n👉 Suivre la tontine : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /**
   * Retrait anticipé d'épargne remis au membre (B42) : famille {@code JACKPOT} (argent remis, déjà
   * active par défaut).
   */
  public void sendRetraitRemis(
      String phone, String tontineName, String montant, String soldeRestant) {
    String message =
        "💵 *Dinthialma* – Retrait de *"
            + montant
            + " FCFA* remis sur votre épargne *"
            + tontineName
            + "*.\nSolde restant : "
            + soldeRestant
            + " FCFA.\n👉 Détails : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Demande de retrait d'un membre, au gestionnaire (B42, lot 2). */
  public void sendRetraitDemande(
      String phone, String membreNom, String tontineName, String montant) {
    String message =
        "💵 *Dinthialma* – "
            + membreNom
            + " demande un retrait de *"
            + montant
            + " FCFA* sur l'épargne *"
            + tontineName
            + "*.\n👉 Accepter, remettre ou refuser : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Demande de retrait acceptée, remise à venir (B42, lot 2). */
  public void sendRetraitAccepte(String phone, String tontineName, String montant) {
    String message =
        "💵 *Dinthialma* – Votre demande de retrait de *"
            + montant
            + " FCFA* sur *"
            + tontineName
            + "* est acceptée : votre gestionnaire vous remettra l'argent.\n👉 Détails : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Demande de retrait refusée, avec le motif (B42, lot 2). */
  public void sendRetraitRefuse(String phone, String tontineName, String montant, String motif) {
    String message =
        "💵 *Dinthialma* – Votre demande de retrait de *"
            + montant
            + " FCFA* sur *"
            + tontineName
            + "* est refusée.\nMotif : "
            + motif
            + "\n👉 Détails : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }

  /** Solde de départ repris du cahier, à la reprise figée (B41) : le membre peut le contester. */
  public void sendSoldeDepart(
      String phone, String tontineName, String montant, String dateDebutReelle) {
    String message =
        "📒 *Dinthialma* – Votre épargne *"
            + tontineName
            + "* est maintenant suivie dans l'application.\nSolde de départ repris du cahier"
            + " (depuis le "
            + dateDebutReelle
            + ") : *"
            + montant
            + " FCFA*.\nUne erreur ? Signalez-la à votre gestionnaire.\n👉 Détails : "
            + appUrl;
    send(CategorieWhatsapp.JACKPOT, phone, message);
  }
}
