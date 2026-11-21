# Diagnostic : tontine d'épargne déjà commencée (reprise + retraits anticipés)

- **Date** : 2026-11-21
- **Branche / commit analysé** : `develop` à jour (`git pull` : déjà à jour), **`76ea873`**
- **Méthode** : lecture du code (`src/main`), lancement de 6 tests d'intégration exploratoires
  (Testcontainers PostgreSQL 17 + Keycloak 26). Aucun fichier de `src/main`, aucune migration ni
  aucun test existant n'a été modifié. Seul ajout : `src/test/java/com/africa/dinthialma_backend/diagnostic/RepriseEtRetraitsDiagnosticIT.java`.
- **Légende** : ✅ marche · ⚠️ marche avec une limite · ❌ impossible · ❓ non vérifié

> Note sur le dossier : sous Windows, `docs/` et `Docs/` sont **le même dossier** (`core.ignorecase=true`).
> Git suit ce dossier sous le nom `Docs/`, donc ce rapport apparaîtra comme `Docs/diagnostic/…`.

---

## 1. Résumé (10 lignes)

1. **Oui, avec un contournement**, mais à trois conditions strictes. Sans elles, de l'argent disparaît du calcul.
2. Le type `EVENEMENTIELLE` + `montantLibre=true` + `montantMinimum=2000`, sans commission, donne bien « chacun récupère ce qu'il a cotisé » (test B : Awa 46 000, Binta 42 000, commission 0).
3. **Condition 1 : ne jamais passer un membre en `SORTI`, `SUSPENDU` ni le supprimer avant la distribution finale.** La distribution ne prend que les membres `ACTIF`. Les cotisations des autres **sortent du calcul sans aucune trace** (test A4 : 94 000 F sur 104 000 F évaporés).
4. **Condition 2 : clôturer chaque sous-cycle journalier à la main**, soit 41 clôtures du 21/11 au 31/12. Rien n'est automatique. Sans clôture, la saisie du lendemain est refusée (`NO_CYCLE_IN_PROGRESS`). Le code ne contrôle aucune date à la clôture : une clôture faite trop tôt sur le dernier jour déclenche la distribution finale de façon irréversible.
5. **Condition 3 : aucun retrait anticipé n'est possible.** Il n'existe ni notion de retrait, ni de solde, ni de mouvement négatif. Un membre qui reprend son argent avant la fin doit être géré hors de l'application, ce qui crée un risque de double paiement à la distribution.
6. La création avec `dateDebut` passée est refusée (`@FutureOrPresent`, 400 `VALIDATION_ERROR`). **En revanche, `PUT /v1/tontines/{id}` accepte une `dateDebut` passée en BROUILLON** (contrôle absent). L'activation génère alors les 61 jours depuis le 01/11. C'est une faille, mais aussi une voie de reprise détaillée qui fonctionne déjà (test A1).
7. Le report du cahier en une seule saisie (44 000 F) est accepté : montant ≥ minimum, sans plafond, note conservée. En contrepartie, les statistiques du jour sont faussées : 86 000 F collectés pour 6 000 F « attendus ».
8. Saisie : une seule cotisation par membre et par jour (index `uq_cot_echeance_membre_actif`). Il n'existe **pas de saisie groupée** : 20 membres × 41 jours = 820 appels `POST /cotisations/admin`.
9. Membres `PRE_ENROLLED` : un WhatsApp d'invitation à la création du compte, puis un WhatsApp à la distribution finale. Les messages de cotisation et les rappels WhatsApp sont **désactivés par défaut**. Les notifications in-app sont enregistrées mais illisibles tant que le membre n'a pas de compte.
10. Recommandation : lancer avec le contournement B (ou la reprise détaillée via PUT), en appliquant une **consigne écrite** au gestionnaire. Avant tout usage réel, corriger en priorité l'exclusion des membres non `ACTIF` de la distribution (lot 0 de B42, ~1 j).

---

## 2. Tableau A1 → A5

### A1. Création

| # | Question | Réponse | Preuve | Statut |
|---|----------|---------|--------|--------|
| 1a | Événementielle JOUR/1, 2 000 F, échéance au 31/12, sans commission ? | Acceptée. `commissions` absent = aucune commission. | `TontineServiceImpl#createTontine`, `#validerCreationEvenementielle` ; test `a1RythmeJournalier` | ✅ |
| 1b | Nombre de sous-cycles ? | **Un sous-cycle par jour, une échéance chacun** : 41 du 21/11 au 31/12 (61 depuis le 01/11). Le 1er est `EN_COURS`, les autres `EN_ATTENTE`. Le dernier vient du 31/12 à 23:59. | `TontineServiceImpl#generateEvenementielleSubCycles` → `CalendrierTours#generer(…, MAX_VALUE, dateEcheance)` + `grouper(…, 1)` ; DIAG « 41 sous-cycles, 6 dimanches » | ✅ |
| 1c | Dimanche inclus ? Excluable ? | Inclus (6 dimanches sur 41). **Impossible de l'exclure** : en `JOUR`, `jourEcheance` est refusé (400 `JOUR_ECHEANCE_INVALIDE`) et aucun calendrier de jours ouvrés n'existe. Un dimanche sans cotisation reste un « jour non payé » (rappels, `COTISATIONS_MANQUANTES`). | `Rythme#resoudre` (contrôle `jourEcheance`) ; DIAG « JOUR + jourEcheance=DIMANCHE → 400 » | ❌ |
| 2a | Où est le contrôle « aujourd'hui ou futur » ? | **Seulement dans le DTO de création** : `@FutureOrPresent` sur `CreateTontineRequest.dateDebut` → 400 `VALIDATION_ERROR` (« doit être une date dans le présent ou le futur »). Il n'y a aucun contrôle dans le service. | `CreateTontineRequest#dateDebut` ; DIAG « création dateDebut=2026-11-01 → 400 » | ✅ |
| 2b | Contournement possible ? | **Oui (faille)** : `UpdateTontineRequest.dateDebut` n'a ni `@FutureOrPresent` ni contrôle dans `TontineServiceImpl#updateTontine` / `#appliquerChampsCommuns`. Un `PUT` avec `dateDebut=2026-11-01` en BROUILLON renvoie 200. | test `a1DateDebutPassee` ; DIAG « PUT dateDebut=2026-11-01 en BROUILLON → 200 » | ⚠️ (faille) |
| 2c | Activation avec `dateDebut` passée ? | Aucun contrôle. `TontineServiceImpl#activateTontine` génère tous les jours depuis `dateDebut` (61 sous-cycles), et le **cycle du 01/11 devient `EN_COURS`**. Pour arriver au jour présent, il faut clôturer les jours passés un par un. Même effet si la tontine est créée aujourd'hui puis activée un autre jour : le cycle 1 démarre à `dateDebut`. | DIAG « 61 sous-cycles, cycle 1 du 2026-11-01 statut EN_COURS » | ⚠️ |
| 3 | `montantLibre=true` + `montantMinimum=2000` ? | Accepté. `montant` envoyé est ignoré et remplacé par `montantMinimum` (999 → 2000, cf. `#resolveMontant`). À chaque cotisation : montant ≥ 2000 (`AMOUNT_BELOW_MINIMUM`), **pas de plafond**. Le montant « attendu » par membre et par jour vaut 2000 (`Tontine#getMontantAttenduParMembre`). | `TontineServiceImpl#resolveMontant`, `CotisationServiceImpl#validateMontantCotisation` ; DIAG « montant retenu=2000 » | ✅ |

### A2. Membres

| # | Question | Réponse | Preuve | Statut |
|---|----------|---------|--------|--------|
| 4a | Parcours PRE_ENROLLED | `POST /membres {phone, firstName, lastName}`. Numéro inconnu : `#createPreEnrolledUser` crée un compte (`keycloakId=null`, `PRE_ENROLLED`) puis le membership `ACTIF`. Numéro connu : rattachement direct (nom complété s'il est vide). Le rôle MEMBER est attribué à l'inscription. La connexion est refusée (403) tant que le compte est PRE_ENROLLED. | `MembreServiceImpl#addMembre`, `#resolveByPhone`, `#ajouter` ; test B (recap : `accountStatus=PRE_ENROLLED`) | ✅ |
| 4b | Notifications sans l'app ? | **WhatsApp** : invitation (famille `INVITATION`, active) **seulement à la création du compte PRE_ENROLLED** (pas si le numéro existe déjà) ; cotisation enregistrée (famille `COTISATION`, **inactive par défaut**) ; rappels (`RAPPEL`, **inactive par défaut**) ; distribution finale (`JACKPOT`, active). **SMS : aucun.** `SmsService` n'est appelé nulle part, l'invitation passe par `WhatsappService#sendTontineInvite`. In-app : enregistré, mais `COTISATION_VALIDEE` n'est pas créé pour un PRE_ENROLLED. | `WhatsappService` (`openwa.categories` défaut `OTP,INVITATION,JACKPOT`), `CotisationServiceImpl#adminRecordCotisation` (l. 271-315), `CycleServiceImpl#notifyFinalDistribution` | ⚠️ |
| 5 | Limite de membres ? | **Aucune** pour une événementielle, à l'ajout direct comme par demande d'adhésion (`placesRestantes` = null hors ROTATIVE). `MembreServiceImpl#ajouter` ne contrôle ni effectif ni statut de la tontine. | `AdhesionServiceImpl#placesRestantes`, `MembreServiceImpl#ajouter` | ✅ (ajout sur tontine TERMINEE : ❓ non testé) |

### A3. Saisie des paiements par le gestionnaire

| # | Question | Réponse | Preuve | Statut |
|---|----------|---------|--------|--------|
| 6a | `montant` contrôlé pour la saisie gestionnaire ? | Oui, même règle que la déclaration : montant fixe → `AMOUNT_MISMATCH` ; libre → `AMOUNT_BELOW_MINIMUM` ; ≤ 0 → `VALIDATION_ERROR` (`@Positive`). Aucun plafond. | `CotisationServiceImpl#adminRecordCotisation` → `#validateMontantCotisation` ; DIAG A3 | ✅ |
| 6b | Bornes de `datePaiement` | `≤ maintenant` (`@PastOrPresent` → `VALIDATION_ERROR`) et `≥ début du **tour**` à 00:00 (`#verifierDatePaiement` → 400 `INVALID_PARAMETER`). En journalier, tour = échéance = jour. Elle n'est pas comparée au début de l'échéance (cas multi-échéances). | DIAG « datePaiement la veille → 400 INVALID_PARAMETER », « demain → 400 VALIDATION_ERROR » | ✅ |
| 6c | `echeanceId` sur une échéance passée ? | Accepté si l'échéance appartient au `cycleId` fourni et qu'elle est ouverte. En journalier, chaque échéance est la n° 1 de son tour, donc toujours « ouverte » (`EcheancesTour#ouverte`). Le **tour** doit être `EN_COURS` ou `TERMINE` : `EN_ATTENTE` → 400 `NO_CYCLE_IN_PROGRESS`. Une échéance d'un autre tour → 404 `ECHEANCE_INTROUVABLE`. | `#echeancePayee`, `#verifierTourPayable` ; DIAG A3 | ✅ |
| 6d | Échéance **avant `dateDebut`** ? | Sans objet : aucune échéance n'existe avant `dateDebut`, et un id inconnu → 404 `ECHEANCE_INTROUVABLE`. Seule voie : reculer `dateDebut` par `PUT` (faille 2b). | DIAG « echeanceId inconnu → 404 » | ❌ |
| 6e | Une cotisation active par membre et par échéance | Oui. En code : `#verifierUnicite` → 409 `COTISATION_ALREADY_EXISTS`. En base : index unique partiel `uq_cot_echeance_membre_actif (echeance_id, membre_id) WHERE deleted_at IS NULL AND statut <> 'REFUSE'` (V042). | `V042__add_echeance_to_cotisations.sql` ; DIAG « 2e saisie même jour → 409 » | ✅ |
| 6f | Saisie pour un membre SORTI / SUSPENDU ? | **Acceptée (201)** : `adminRecordCotisation` ne contrôle pas le statut du membre. Elle n'est refusée (404) que si le membre a été supprimé par `DELETE`. Combiné au point 11, l'argent saisi pour un SORTI n'est jamais redistribué. | DIAG A4 « saisie SORTI → 201 », « SUSPENDU → 201 » | ⚠️ |
| 7 | Validation groupée | `POST /v1/me/gestion/cotisations/valider` : 1 à 100 ids (`@NotEmpty`, `@Size(max=100)`), doublons ignorés, **tontines mélangées acceptées** (droits vérifiés cotisation par cotisation), une transaction par cotisation, toujours 200 avec `code` par échec. **Inutile ici** : la saisie gestionnaire est déjà `VALIDE`. Il n'existe **aucune saisie groupée**. | `ValidationGroupeeRequest`, `MeServiceImpl#validerCotisations` | ⚠️ |
| 8a | Clôture automatique des sous-cycles ? | **Non.** Seul `PUT /cycles/{id}/cloturer` fait passer au jour suivant (`CycleServiceImpl#closeCycleEvenementielle` → `#activateNextCycle`). Le gestionnaire doit clôturer **chaque jour**. Le code ne contrôle aucune date : on peut clôturer le jour 2 avant qu'il commence (DIAG : « clôture jour 1 puis jour 2 le même jour → 200 puis 200 »). Clôturer le dernier jour déclenche la distribution finale. Sans clôture, la saisie du lendemain retombe sur le même jour → 409. | `CycleServiceImpl#closeCycle` ; test `a3SaisieGestionnaire` | ⚠️ |
| 8b | Traitements planifiés | ① `SchedulerService#rappelCotisationsEnAttente`, **8h00 chaque jour** : en événementielle, rappel à chaque membre ACTIF sans cotisation sur le sous-cycle EN_COURS (in-app + WA `RAPPEL`). Les jours J-30/7/3/1, rappel « urgent » à **tous** les membres actifs. ② `#rappelsCyclesProches`, 8h00 : `CYCLE_BIENTOT_CLOTURE` (J-2) au gestionnaire. ③ `RappelsEcheanceService#planifie`, **toutes les 5 min** : veille 18h00 et N h avant l'échéance (membres), puis `COTISATIONS_MANQUANTES` au gestionnaire (une fois, dans les 24 h après l'échéance), sur les tours EN_COURS de tontines ACTIVE. ④ **Passage en retard** : uniquement à la clôture (EN_ATTENTE → EN_RETARD). Aucun traitement planifié ne le fait. | fichiers cités | ✅ (lecture du code ; les envois planifiés ne sont pas couverts par les tests exploratoires) |
| 9a | Réduire ou couper les rappels ? | Partiellement. `rappels.veille=false` et `rappels.heuresAvant=0` coupent ③ (création, `PUT` en BROUILLON, ou `PATCH /echeance` à tout moment). **Le rappel de 8h (①) et J-30/7/3/1 ne sont pas réglables par tontine.** Le WhatsApp se règle seulement au niveau de la plateforme (`OPENWA_CATEGORIES`). | `RappelsEcheance` (`@Min(0) @Max(24)`), `SchedulerService` | ⚠️ |
| 9b | Messages par jour, 20 membres ? | **Déduit du code, non mesuré.** Pour un membre sans paiement à l'heure du traitement : in-app 8h00 + veille (dès l'ouverture du sous-cycle, la fenêtre « veille 18h » étant déjà passée) + « 3 h avant » à 20h59, soit **jusqu'à 3 in-app par membre et par jour** (60 pour 20 membres, si personne n'a payé à 8h, ce qui est probable en espèces). S'y ajoute **1 `COTISATIONS_MANQUANTES`/jour** au gestionnaire si le jour n'est clôturé que le lendemain. WhatsApp : **0 par défaut** (famille `RAPPEL` inactive) ; si elle était activée, jusqu'à 60 WA/jour, hors 21h-8h pour ③. Rappels coupés : il reste 20 in-app/jour (8h). | `SchedulerService#processEvenementielleReminders`, `RappelsEcheanceService#traiterEcheance` | ❓ (estimation) |

### A4. Retrait anticipé

| # | Question | Réponse | Preuve | Statut |
|---|----------|---------|--------|--------|
| 10 | Notion de retrait / remboursement / solde ? | **Aucune.** Il n'existe ni entité, ni endpoint, ni type de mouvement, ni statut (recherche `retrait\|rembours\|solde\|caisse\|withdraw\|refund` dans `src/main`). `ModeRemise.CAISSE` existe mais il est refusé partout (`MODE_REMISE_INDISPONIBLE`, « jusqu'à B29 »). La remise B33 (`RemiseService`, `CalculRemise`) ne concerne que le jackpot **rotatif** (`regulariser` sort immédiatement hors ROTATIVE). B29 (caisses) n'est pas commencé. | `ModeRemise`, `TontineServiceImpl#modeRemise`, `RemiseServiceImpl#regulariser` | ❌ |
| 11a | SORTI : cotisations comptées dans la distribution ? | **Non.** `#computeFinalDistribution` additionne toutes les cotisations VALIDE de la tontine, mais ne garde que les membres de `findByTontine_IdAndStatutAndDeletedAtIsNull(…, ACTIF)` (`if (m == null) continue;`). **SORTI (PATCH), supprimé (DELETE) et SUSPENDU sont tous exclus.** Leur argent n'apparaît nulle part : ni dans `distributions_evenementielles`, ni dans le `montantJackpot` du dernier cycle (recalculé sur la distribution). Les commissions au prorata sont aussi calculées sur ces mises exclues (sans effet à 0 %). | `CycleServiceImpl#closeCycleEvenementielle` (l. 553-571), `#computeFinalDistribution` (l. 651-656) ; test `a4MembreSorti` : 4 membres (10 000 / 22 000 / 30 000 / 42 000 F) → distribution = **Actif 10 000 seul**, `montantJackpot=10000` | ❌ |
| 11b | Apparaît dans `distributionParMembre` ? | Non (même cause). Il disparaît aussi de `recap-total` et du récap par tour (filtre `statut <> SORTI`). SUSPENDU y reste visible mais exclu de la distribution. | DIAG A4 recap-total / distribution | ❌ |
| 11c | Peut-on le faire revenir ? | **SORTI par PATCH** : oui, `PATCH /statut {ACTIF}` → 200, et ses cotisations comptent de nouveau. **Supprimé par DELETE** (`deletedAt` + SORTI) : non, 404 (`findByIdAndTontine_IdAndDeletedAtIsNull`). Le ré-ajouter par téléphone crée un **nouveau** membership, et les anciennes cotisations restent liées à l'ancien id. | `MembreServiceImpl#updateStatut`, `#removeMembre` ; DIAG A4 | ⚠️ |
| 12 | Montant négatif / correction à la baisse ? | Négatif ou nul : refusé (`@Positive`, 400 `VALIDATION_ERROR`, à la saisie comme au PATCH). Baisse : `PATCH /cotisations/{id}` possible **uniquement sur le tour EN_COURS** et **≥ montantMinimum** (44 000 → 30 000 : 200 ; → 1 000 : `AMOUNT_BELOW_MINIMUM`). Une fois le jour clôturé : 400 `COTISATION_NOT_EDITABLE`. Un retrait partiel ne peut donc pas être simulé au-delà du jour courant. | `CotisationServiceImpl#updateCotisation` ; DIAG A4.12 | ❌ |
| 13a | Clôture anticipée (`forcer`) ? | Sans `forcer` : 409 `TONTINE_CLOTURE_A_CONFIRMER` (`toursNonRemis` = 41). Avec `forcer` : `CycleService#distribuerMaintenant` retire logiquement les sous-cycles EN_ATTENTE, clôture celui en cours et fait la **distribution finale immédiate** (VALIDE des membres ACTIF uniquement). La tontine passe TERMINEE. La réponse est un `TontineResponse` **sans `distributionParMembre`** : la répartition se lit dans `distributions_evenementielles`, via `/me/jackpots` ou dans les notifications. | `ClotureTontineServiceImpl#cloturer` ; test `a4ClotureAnticipee` : 46 000 F, 2 lignes | ✅ |
| 13b | Clôturer pour un seul membre ? | **Impossible.** La clôture concerne toute la tontine. Le seul effet « individuel » est la sortie d'un membre, qui l'exclut sans lui verser sa part (11a). | — | ❌ |

### A5. Clôture et distribution

| # | Question | Réponse | Preuve | Statut |
|---|----------|---------|--------|--------|
| 14a | Base de calcul | Somme des cotisations **VALIDE** de la tontine (tous sous-cycles), par membre ACTIF. | `#computeFinalDistribution` | ✅ (⚠️ exclusion des non-ACTIF) |
| 14b | EN_ATTENTE à la clôture | À chaque clôture de sous-cycle, EN_ATTENTE → EN_RETARD : **exclues** de la distribution. Elles restent validables (B33) **jusqu'à** la distribution finale. Après, toute cotisation est refusée (`REGULARISATION_IMPOSSIBLE`). Avec la seule saisie gestionnaire (déjà VALIDE), ce cas ne se présente pas. | `#closeCycleEvenementielle` l. 534-540, `CotisationServiceImpl#verifierTourPayable` | ✅ |
| 14c | Commission 0, arrondis | Sans commission, `commissionTotale` = 0, `Montants.repartirAuProrata(0, …)` = 0 par membre, et net = cotisé, au franc près. | DIAG B « commission=0 net=46000 » | ✅ |
| 14d | Statut de la tontine après le dernier jour | La clôture du dernier sous-cycle fait la distribution, mais **la tontine reste ACTIVE**. Le gestionnaire reçoit `TONTINE_PRETE_A_CLOTURER` et doit faire `PUT /cloturer`. | DIAG B « statut … : ACTIVE », notifications `[TONTINE_PRETE_A_CLOTURER, DISTRIBUTION_FINALE]` | ⚠️ |
| 15 | Que voit le membre (`/me`) ? | Rien tant qu'il est PRE_ENROLLED (connexion impossible). Une fois inscrit (memberships conservés), `/me/summary` affiche par tontine `totalCotiseValide`, `totalEnAttente`, `cotisationDue` (montant du jour), puis `totalJackpotsRecus` = part nette de la distribution finale. `/me/jackpots` affiche `nature=DISTRIBUTION_FINALE` et `/me/activite` `DISTRIBUTION_RECUE`. Aucun « solde » (pas de retraits). | `MeServiceImpl` (l. 108-144, 230-290, 460) ; `ClotureTontineIT#evenementielleAvantSonTerme` (existant) | ✅ (non retesté dans ce scénario) |

---

## 3. Partie B : test de bout en bout du contournement

Test `partieBContournement`. La durée est réduite à 3 jours (aujourd'hui → J+2) pour aller jusqu'à la clôture finale naturelle. Le code est le même pour 41 jours. Exécuté le 21/11/2026 vers 22h15 (heure de Dakar).

Déroulé : création (libre, min 2 000, sans commission) → Awa, Binta et Coumba pré-inscrites → activation (3 sous-cycles) → jour 1 : reports 44 000 / 40 000 / 2 000 avec la note → clôture du jour 1 → jour 2 : 2 000 chacune → Coumba `SORTI` → clôture des jours 2 et 3 → distribution.

Sorties utiles (extraits `DIAG|`) :

```
B jour 1 saisie 44000 → 201 statut=VALIDE ponctualite=A_TEMPS   (idem 40000 et 2000)
B cycle 1 : montantJackpotPrevu=6000.0 tousPayes=true
B clôture jour 1 → 200 montantJackpot=86000.0
B my-dashboard agrégats : montantTotalValide=92000.0 totalCollecteToursEnCours=6000.0
                          totalAttenduToursEnCours=6000.0 totalCotisationsEnRetard=0
B recap-total : Awa 46000 (2 cotisations) · Binta 42000 (2) · Coumba 4000 (2)
B Coumba SORTI → 200
B clôture finale → 200 montantJackpot=88000.0 montantNet=88000.0
B distribution : Awa  cotisé=46000.0 commission=0 net=46000.0
B distribution : Binta cotisé=42000.0 commission=0 net=42000.0
B statut de la tontine après clôture finale du dernier sous-cycle : ACTIVE
B notifications du gestionnaire : [TONTINE_PRETE_A_CLOTURER, DISTRIBUTION_FINALE]
```

Export CSV (extrait) :

```
Tour;Membre;Téléphone;Montant (FCFA);Statut;Méthode;Référence;Déclarée le;Validée le;Motif refus;Note;Échéance du tour;Ponctualité;Payée le;Rythme
1;Awa Cahier;2217…;44000.00;VALIDE;CASH;;2026-11-21T22:15:05;…;;Report du cahier du 01/11 au 21/11;2026-11-21T23:59;A_TEMPS;;chaque jour, avant 23h59
2;Awa Cahier;2217…;2000.00;VALIDE;CASH;;2026-11-21T22:15:05;…;;;2026-11-22T23:59;A_TEMPS;;chaque jour, avant 23h59
```

### Ce qui casse ou surprend

| # | Constat | Gravité |
|---|---------|---------|
| B1 | **Coumba (SORTI) disparaît de la distribution** : 4 000 F cotisés ne sont attribués à personne. Le `montantJackpot` final (88 000) ne correspond plus au total encaissé (92 000), sans aucune trace. | 🔴 bloquant |
| B2 | **Clôture quotidienne obligatoire et sans garde-fou de date** : 41 clôtures manuelles. On peut clôturer à l'avance (le jour 2 a été payé le 21/11, cf. « Déclarée le »), et clôturer par erreur le dernier jour déclenche une distribution irréversible. | 🔴 |
| B3 | **Montant « attendu » faux le jour du report** : `montantJackpotPrevu` = 6 000 (2 000 × 3) pour 86 000 collectés. Les jours suivants, le collecté et l'attendu redeviennent cohérents. `montantTotalValide` (92 000) inclut les reports, ce qui est juste. | 🟠 statistique |
| B4 | **Ponctualité** : le report est `A_TEMPS` et compte comme **une** cotisation (`nombreCotisationsValidees=2` pour 22 jours d'épargne). Les jours manqués du cahier sont invisibles et aucun retard ne peut être exprimé. | 🟠 |
| B5 | **Export CSV** : le report apparaît comme un paiement de 44 000 F au tour 1. Seule la colonne `Note` l'explique. | 🟡 |
| B6 | **Aucun plafond de montant** en libre : une faute de frappe (440 000) serait acceptée et seulement corrigeable le jour même (`PATCH`). | 🟠 |
| B7 | **Notifications** : le gestionnaire ne reçoit rien pour ses propres saisies. Les membres PRE_ENROLLED ne reçoivent rien non plus (WA `COTISATION` inactif, in-app non créé pour eux), sauf l'invitation à l'ajout et le WhatsApp de distribution finale. En production : jusqu'à 3 rappels in-app par jour et par membre (lus par personne) et `COTISATIONS_MANQUANTES` quotidien si la clôture est faite le lendemain (cf. A3-9b). | 🟡 |
| B8 | La tontine reste **ACTIVE** après la distribution : `PUT /cloturer` est nécessaire (suggéré par `TONTINE_PRETE_A_CLOTURER`). | 🟡 |
| B9 | Les dimanches restent des jours dus (rappels, manquants). | 🟡 |
| B10 | Pas de saisie groupée : environ 20 appels par jour plus une clôture. | 🟠 ergonomie |

### Variante sans développement : reprise détaillée via la faille du `PUT`

Le test `a1DateDebutPassee` montre que cette voie fonctionne **aujourd'hui** : création à la date du jour, puis `PUT dateDebut=2026-11-01`, activation (61 sous-cycles, le 01/11 `EN_COURS`), saisie jour par jour avec `datePaiement` = date du cahier (`A_TEMPS`), clôture des jours passés un par un. Un jour TERMINE accepte encore une saisie (régularisation B33), et aucune notification n'est envoyée au gestionnaire (`[]`).
Coût : environ 20 × 21 = 420 saisies et 21 clôtures. Les jours manqués restent exacts. Sans `datePaiement`, la ponctualité devient `EN_RETARD`.
Inconvénient : cette voie repose sur une **faille** que B41 devra encadrer. Si la famille WA `COTISATION` était activée, elle enverrait 420 WhatsApp rétroactifs.

### Consignes au gestionnaire si l'un des deux contournements est utilisé

1. Ne jamais passer un membre en SORTI ni SUSPENDU, et ne jamais le supprimer, avant la distribution finale.
2. Clôturer le jour seulement après la dernière saisie du jour, et jamais le dernier jour avant le 31/12.
3. Aucun retrait dans l'application. Un retrait anticipé reste hors application et doit être noté, avec le risque d'un double paiement à la distribution.
4. Rappels : `veille=false`, `heuresAvant=0`.

---

## 4. Conceptions

### B41 : reprise d'une tontine déjà commencée

**Constat de départ** : le moteur sait déjà générer des échéances passées et accepter des saisies datées sur des jours clôturés (test A1). Il manque une **règle explicite** (qui, jusqu'où), un **marquage** des lignes reprises et la **neutralisation** des effets annexes (ponctualité, rappels, retards, notifications).

**Option recommandée : solde d'ouverture par membre** (lot 1), puis le détail en option (lot 3).

| Critère | Solde d'ouverture (1 ligne / membre) | Détail membre × jour (CSV) |
|---|---|---|
| Effort du gestionnaire | 20 lignes | 420 lignes, souvent ressaisies depuis le cahier |
| Fidélité | totaux seulement | jours manqués, ponctualité |
| Code | petit : une cotisation marquée sur un « tour de reprise » | import, validation ligne à ligne, rapport d'erreurs |
| Contestation | « j'avais 44 000 » : vérifiable sur le cahier | plus fin, mais plus de lignes à contester |

Le cahier sert surtout à connaître « combien chacun a mis ». Le solde d'ouverture couvre donc le besoin et laisse le détail pour plus tard.

**Modèle de données (V044)** :
- `tontines.date_debut_reelle DATE` (nullable) : date du cahier, affichée (« démarrée le 01/11 »), sans générer de tours.
- `cotisations.origine VARCHAR(20)` (nullable = historique ; valeurs `DECLARATION`, `SAISIE`, `REPRISE`), reprise en base : `DECLARATION` si `enregistre_par = membre`, sinon `SAISIE`.
- **Tour de reprise** : un `cycles_tontine` `numero_cycle = 0`, statut `TERMINE`, `date_debut = date_debut_reelle`, `date_fin` = veille du premier tour, **sans échéance**. Ses cotisations (`origine=REPRISE`, `VALIDE`, `echeance_id NULL`) entrent automatiquement dans `#computeFinalDistribution` (somme des VALIDE) et dans `recap-total`. Comme ce tour n'est jamais EN_COURS, il échappe aux deux planificateurs de rappels. L'unicité par tour (`existsActiveByCycleAndMembre`, branche « sans échéance ») limite déjà à une reprise par membre.
- `tontines.reprise_figee_le TIMESTAMP` : verrou (voir plus bas).

**Règles** :
- Gestionnaire (créateur) ou SUPER_ADMIN seulement. Tontine BROUILLON ou ACTIVE, **avant la première distribution**.
- `date_debut_reelle` ≤ `dateDebut` et ≥ `dateDebut − 366 j` (plafond configurable). On garde `@FutureOrPresent` à la création et on **ajoute la même règle au `PUT`** (corrige la faille 2b, code `DATE_DEBUT_PASSEE`).
- Montant > 0, sans minimum journalier. Avertissement non bloquant si le montant dépasse `montantAttenduParMembre × jours` (rotative ou fixe).
- Ponctualité : sans échéance, `Ponctualite.de` se rabattrait sur l'échéance du tour 0 (résultat trompeur). Ajouter une valeur `REPRISE` à `Ponctualite` (on ajoute une valeur, on n'en change aucune), exclue des statistiques de ponctualité, des retards (S01), des amendes (B27) et de `echeancesARegulariser`.
- **Figer** : `POST /reprise/figer`, sinon figée automatiquement à la première clôture de tour. Ensuite, `PATCH` ou suppression d'une ligne `REPRISE` → 400 `REPRISE_FIGEE`. Avant le gel, la correction est libre et auditée.
- **Aucune notification rétroactive** : ni in-app ni WhatsApp à la saisie de reprise. Option : un seul message « votre solde de départ : X F » au gel (question ouverte Q3).

**Endpoints** :
- `PUT /v1/tontines/{id}/reprise` `{dateDebutReelle, soldes: [{membreId, montant, note}]}` : remplace l'ensemble tant que la reprise n'est pas figée (idempotent, transaction unique).
- `GET /v1/tontines/{id}/reprise` : soldes, total, état (`OUVERTE` | `FIGEE`).
- `POST /v1/tontines/{id}/reprise/figer`.
- Lot 3 : `POST /v1/tontines/{id}/reprise/import` (CSV `;` + BOM, colonnes `telephone;date;montant`), avec un rapport ligne par ligne. Ce lot génère alors de vraies échéances passées (mécanisme du test A1) marquées `REPRISE`.

**Impacts** :
- DTO : `CotisationResponse.origine`, `TontineResponse.dateDebutReelle`, `repriseFigee`. MapStruct : champs explicites (`unmappedTargetPolicy=ERROR`).
- Planificateurs : aucun changement pour le lot 1 (tour 0 TERMINE). Lot 3 : filtrer `origine=REPRISE` dans `RappelsEcheanceService#sansDeclaration` et `SchedulerService`.
- Export CSV : colonne `Origine` en fin de ligne (colonnes historiques inchangées). La ligne de reprise affiche « Reprise du cahier » au tour 0.
- `/me/summary` : `soldeReprise` par tontine. `/me/activite` : type `REPRISE` (ajout à `TypeActivite` et une branche dans l'UNION native d'`ActiviteRepository`).
- `my-dashboard` : le tour 0 n'est jamais « en cours », donc `montantAttendu` n'est pas faussé (corrige B3). `montantTotalValide` inclut la reprise.
- Audit : `tontines.date_debut_reelle`, chaque ligne de reprise (création, modification, gel).
- Codes d'erreur ajoutés : `DATE_DEBUT_PASSEE`, `REPRISE_FIGEE`, `REPRISE_NON_AUTORISEE`, `REPRISE_DATE_INVALIDE`.

**Risques** :
- Double comptage : reprise + saisie du même jour. Le tour 0 s'arrête la veille du tour 1, à afficher clairement. Contrôle : pas de reprise sur une date ≥ `dateDebut`.
- Fraude du gestionnaire (gonfler un proche) : audit, gel, et visibilité du solde de départ par le membre dès son inscription (le membre peut contester).
- Contestation : prévoir le motif et la trace (qui, quand, valeur avant/après). L'historique d'audit suffit au lot 1.
- Tontine rotative : la reprise n'a de sens qu'en événementielle ou épargne au lot 1. En rotative, il faudrait aussi reprendre qui a déjà gagné (`aRecuJackpot`) : hors périmètre, à refuser au lot 1 (`REPRISE_NON_AUTORISEE`).

**Découpage et estimation** :

| Lot | Contenu | Estimation |
|---|---|---|
| 0 | Contrôle `dateDebut` au `PUT` (faille 2b) + tests | 0,5 j |
| 1 | V044, tour 0, soldes d'ouverture, gel, `origine`, `Ponctualite.REPRISE`, export, `/me`, audit, tests unitaires + IT | 3 à 4 j |
| 2 | Affichage `dateDebutReelle`, `/me/activite`, notification unique au gel (si retenue) | 1 j |
| 3 (option) | Import CSV détaillé membre × jour, échéances passées marquées REPRISE, neutralisation dans les planificateurs | 3 j |
| **Total** | | **4,5 à 8,5 j** |

### B42 : retrait anticipé

**Préalable (lot 0, indépendant, à faire même sans B42)** : corriger `CycleServiceImpl#computeFinalDistribution` pour que **tout membre ayant des cotisations VALIDE** soit distribué, quel que soit son statut (ACTIF, SUSPENDU, SORTI, y compris supprimé), ou qu'il soit explicitement « soldé » (voir plus bas). Il faut aussi y joindre le contrôle du statut du membre dans `adminRecordCotisation` (refuser SORTI, question ouverte pour SUSPENDU). Les deux corrections changent le comportement : à couvrir par des tests de non-régression, et à vérifier contre les tests `[caractérisation]` s'il en existe ailleurs (aucun trouvé dans `src/test` sur ce commit).

**Modèle de données (V045)** : table `retraits_epargne`.

| Colonne | Rôle |
|---|---|
| `id`, `created_at`, `updated_at`, `deleted_at` | `BaseEntity` + suppression logique |
| `tontine_id`, `membre_id` | FK |
| `nature` | `PARTIEL` \| `TOTAL` |
| `montant` | montant demandé (francs entiers, `scale 2`) |
| `frais` | frais retenus (règle de la tontine), `Montants.pourcentage` |
| `statut` | `DEMANDE` → `ACCEPTE` → `REMIS`, ou `REFUSE` / `ANNULE` |
| `origine` | `MEMBRE` (demande) \| `GESTIONNAIRE` (saisie directe, créée `REMIS`) |
| `methode_paiement`, `reference_transaction` | code list `METHODE_PAIEMENT` (CASH, WAVE, OM…) |
| `motif`, `motif_refus` | texte |
| `demande_par`, `traite_par`, `date_remise` | traçabilité |

Index : un seul retrait `DEMANDE|ACCEPTE` actif par membre (index unique partiel, même modèle que V037).
`tontines` : `regle_retrait` (`INTERDIT` \| `LIBRE` \| `APRES_JOURS` \| `AVEC_FRAIS`), `retrait_delai_jours`, `retrait_frais_type` (`POURCENTAGE` \| `FIXE`), `retrait_frais_valeur`. Pour les tontines existantes, la reprise en base donne `INTERDIT` (comportement inchangé). Aucun `DEFAULT` SQL : valeur fixée en Java, selon les règles du projet.
`tontine_membres.solde_le TIMESTAMP` : retrait total remis. Le membre est « soldé » : il ne reçoit plus de rappels, ne doit plus de cotisation et n'entre plus dans la distribution. Je ne crée pas de nouveau `MembreStatut` : ajouter `SOLDE` toucherait tous les filtres existants. À trancher (Q6).
`distributions_evenementielles.montant_retire` : traçabilité de la part finale.

**Calculs** :
- `solde = Σ cotisations VALIDE − Σ (retraits REMIS : montant + frais)`. La fonction est pure dans `contribution/` ou `epargne/`, testée seule.
- Retrait : `montant + frais ≤ solde` (`RETRAIT_SOLDE_INSUFFISANT`). `TOTAL` ⇒ montant = solde − frais.
- Distribution finale : `montantCotise` inchangé, `montantNet = cotisé − commission − retiré`. Membre soldé : ligne à 0 conservée (trace) ou absente (Q7).
- Frais de retrait : ils restent dans la cagnotte. Ils sont **répartis entre les autres membres** ou **reviennent au gestionnaire** (commission). Choix métier (Q5).

**Endpoints** (`/v1/tontines/{id}/retraits`) :

| Endpoint | Accès | Effet |
|---|---|---|
| `POST /` | membre (lui-même) | demande → `DEMANDE`, gestionnaire notifié |
| `POST /admin` | créateur, SUPER_ADMIN | saisie directe → `REMIS` (espèces remises en main propre) |
| `PUT /{rid}/accepter` | créateur | `DEMANDE` → `ACCEPTE` |
| `PUT /{rid}/remettre` | créateur | `ACCEPTE` → `REMIS` (`methodePaiement`, `reference`) ; si `TOTAL` → `solde_le` |
| `PUT /{rid}/refuser` | créateur | motif obligatoire |
| `DELETE /{rid}` | membre (la sienne) / créateur | `DEMANDE`/`ACCEPTE` → `ANNULE` |
| `GET /` | membre (les siens) / créateur | pageable, `?statut=` |
| `GET /v1/tontines/{id}/membres/{mid}/solde` | membre concerné / créateur | solde détaillé |
| `PATCH /v1/tontines/{id}/reglages` | créateur | `regleRetrait`… (étendre B33 aux événementielles, aujourd'hui refusé hors ROTATIVE) |

Codes d'erreur ajoutés : `RETRAIT_INTERDIT`, `RETRAIT_DELAI_NON_ATTEINT` (`data.disponibleLe`), `RETRAIT_SOLDE_INSUFFISANT` (`data.solde`), `RETRAIT_NOT_PENDING`, `RETRAIT_DEJA_EN_COURS`, `MEMBRE_SOLDE`.
`NotificationType` ajoutés : `RETRAIT_DEMANDE` (gestionnaire), `RETRAIT_ACCEPTE`, `RETRAIT_REMIS`, `RETRAIT_REFUSE` (membre). WhatsApp : rattacher les retraits remis à la famille `JACKPOT` (argent remis, déjà active) ou créer une famille `RETRAIT`, via une méthode dédiée de `WhatsappService`.

**Impacts** :
- `/me/summary` : `solde`, `totalRetire`. `/me/activite` : types `RETRAIT_DEMANDE` / `RETRAIT_RECU` (UNION native). Export : `GET /retraits/export` + colonnes `Retiré` / `Solde` dans `recap-total`. Audit sur chaque transition.
- `my-dashboard` : `retraitsEnAttente` par tontine et `totalRetire`.
- Planificateurs : exclure les membres soldés des rappels (les deux services) et de `COTISATIONS_MANQUANTES`.
- `ClotureTontineServiceImpl#enSuspens` : `retraitsEnAttente` (point en suspens). Les retraits `DEMANDE` sont annulés à la clôture.

**Lien avec B29 (caisses) et B33 (remise)** :
- **B29** : un retrait est exactement un **mouvement sortant de la caisse commune**. Concevoir `retraits_epargne` pour qu'elle soit journalisée par B29 sans migration destructive (`mouvement_id` nullable ajouté par B29). Si B29 est fait avant, B42 devient un type de mouvement : c'est la meilleure option à long terme, mais cela retarde le client d'environ 6 à 8 j.
- **B33** : peu de choses à reprendre. `CalculRemise` et `RemiseService` portent sur le jackpot rotatif (prévu, collecté, avance du gestionnaire). On réutilise les **patterns** : réglage dans `PATCH /reglages`, statut de suivi (« Dinthialma ne détient aucun fonds »), audit, `methodePaiement` + `reference`.

**Découpage et estimation** :

| Lot | Contenu | Estimation |
|---|---|---|
| 0 | Distribution de tous les membres ayant cotisé + refus de saisie pour un SORTI, tests | 1 j |
| 1 | V045, retrait saisi par le gestionnaire (`/admin`, `REMIS`), solde, distribution sur solde, membre soldé, tests | 3 à 4 j |
| 2 | Demande du membre, accepter / remettre / refuser / annuler, notifications, `/me` | 2 j |
| 3 | Règles (délai, frais), `PATCH /reglages` pour les événementielles, export, activité, dashboard, clôture | 2 à 3 j |
| **Total** | | **8 à 10 j** (hors B29) |

**Ordre conseillé pour ce client** : B42 lot 0 → B41 lots 0 + 1 → B42 lot 1. Cela fait environ 8 j pour couvrir le cas sans contournement, avec des retraits saisis par le gestionnaire.

---

## 5. Tests exploratoires écrits

Fichier unique : `src/test/java/com/africa/dinthialma_backend/diagnostic/RepriseEtRetraitsDiagnosticIT.java`.
Il hérite de `AbstractIntegrationTest`. Docker est requis. Les 6 tests passent (exit 0). Le nom en `*IT.java` le fait **aussi passer dans `./mvnw verify` complet**. À supprimer, ou à renommer sans suffixe `IT`, si vous ne voulez pas le garder.

| Test | Prouve |
|---|---|
| `a1DateDebutPassee` | création passée refusée ; `PUT dateDebut` passée accepté ; activation de 61 sous-cycles ; saisie datée du 01/11 ; saisie sur un jour TERMINE ; aucune notification |
| `a1RythmeJournalier` | 41 sous-cycles au 31/12, 6 dimanches, dimanche non excluable ; montant libre → `montant` = minimum |
| `a3SaisieGestionnaire` | codes d'erreur de la saisie (montant, date, échéance, unicité) ; correction à la baisse (A4.12) ; clôture en avance sans contrôle de date |
| `a4MembreSorti` | SORTI / DELETE / SUSPENDU exclus de la distribution ; saisie acceptée pour SORTI et SUSPENDU ; retour de SORTI possible, de DELETE impossible |
| `a4ClotureAnticipee` | `PUT /cloturer` : 409 puis `forcer` → distribution immédiate, 2 lignes en base |
| `partieBContournement` | scénario de bout en bout de la partie B (sorties en section 3) |

Commande (tests unitaires, couverture et autres IT sautés) :

```bash
./mvnw verify -Dit.test=RepriseEtRetraitsDiagnosticIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true
```

Les lignes `DIAG|` de la sortie standard donnent les valeurs reprises dans ce rapport : `grep "DIAG|"` sur la sortie Maven.
Remarque : le plugin `fmt` (phase `format`) a reformaté ce fichier de test pendant le build. Aucun fichier de `src/main` n'a changé (`git status` : seul le dossier `diagnostic/` est nouveau ; `mvnw.cmd` était déjà modifié avant ce diagnostic).

---

## 6. Questions ouvertes

1. **Faille du `PUT dateDebut`** : la corriger tout de suite (lot 0 de B41) ou la garder temporairement comme voie de reprise détaillée pour ce client ?
2. **Membres SORTI ou SUSPENDU à la distribution** : faut-il les payer automatiquement (correction du lot 0 de B42), ou les exclure en les forçant d'abord à être « soldés » ? Aujourd'hui leur argent disparaît : est-ce déjà arrivé en production (V1) ? Une requête de contrôle sur `distributions_evenementielles` contre `cotisations` VALIDE le dirait.
3. **Reprise** : faut-il envoyer un message unique « votre solde de départ » au gel, ou aucun message du tout ?
4. **Reprise** : une limite de jours dans le passé (366 j proposés) ? Réservée au gestionnaire, ou validée par SUPER_ADMIN au-delà d'un seuil ?
5. **Frais de retrait** : redistribués aux autres membres ou acquis au gestionnaire ?
6. **Membre soldé** : nouveau statut `SOLDE`, ou drapeau `solde_le` sur un membership qui reste `ACTIF` (recommandé) ?
7. **Distribution** : garder une ligne à 0 F pour un membre soldé (trace), ou ne pas la créer ?
8. **Jours non cotisés (dimanche)** : faut-il un calendrier de jours exclus (B26 étendu), ou se contenter de couper les rappels ?
9. **Clôture quotidienne** : faut-il une clôture automatique des sous-cycles événementiels à l'échéance (proche du lot 3 de B33, « remise automatique à l'échéance »), plus un garde-fou interdisant de clôturer un jour pas encore commencé ?
10. **Saisie groupée** : un `POST /cotisations/admin/lot` (tous les membres d'un jour en un appel) est-il prioritaire pour l'écran « Encaisser » ?
11. **B29 avant B42** : attendre les caisses pour faire des retraits « propres », ou livrer B42 seul tout de suite, puis le brancher sur B29 ?
