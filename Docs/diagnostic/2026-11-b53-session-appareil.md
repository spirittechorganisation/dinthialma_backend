# B42 (complément) + SE07 + B53 : retrait total, verrouillage du PIN, session longue par appareil

Branche `feature/b53-session-appareil` (depuis `develop` à jour, 0022ae7). Aucun commit ni push.
**Lot 1 indexé** (`git add`), **lot 2 non indexé** : deux commits possibles (§ 6).

**Lot 0 (B42, retrait total avec cotisations en attente)** : déjà fusionné dans `develop` (PR #61). Tu as choisi de le garder tel quel : `data.cotisationsEnAttente`, et blocage aussi sur `EN_RETARD`. Aucun changement, aucun commit.

---

## 1. Lot 1 (SE07) : ce qui existait, ce qui a été ajouté

### PIN

| Question | Avant (code de `develop`) | Après |
|---|---|---|
| Verrouillage après N échecs ? | Oui : 5 échecs **consécutifs** → verrou de **30 min** (`Constants.Pin`, `PinServiceImpl#handlePinFailure`) | 5 échecs → verrou de **15 min** |
| Où est le compteur ? | `users.pin_attempts`, `users.pin_locked_until` (en base) | + `users.pin_echecs_cumules` (V045) |
| Remis à zéro ? | Après un succès, une configuration ou une réinitialisation par OTP ; **et aussi au verrouillage** (le compteur repartait de 0 après chaque verrou, donc aucun plafond global) | `pin_attempts` : inchangé. `pin_echecs_cumules` : remis à zéro par un succès, une configuration ou une réinitialisation, **pas par le verrou** |
| Plafond global ? | Aucun : 5 essais toutes les 30 min, indéfiniment | **10 échecs cumulés → PIN invalidé** (`pin_hash` effacé), toutes les sessions révoquées, 401 `PIN_DISABLED` ; ensuite `PIN_NOT_CONFIGURED` jusqu'à un nouveau PIN |
| Déverrouillage | Attendre la fin du verrou, ou réinitialiser le PIN par OTP (`/pin/reset/**`) | Idem. Après `PIN_DISABLED` : connexion par mot de passe puis nouveau PIN, ou réinitialisation par OTP |
| `retryAfterSeconds` | Oui : `PIN_LOCKED` → 429, `data.retryAfterSeconds` | Oui (900 s à l'instant du verrou) |
| Deux appareils en parallèle | **Échecs perdus possibles** : lecture puis écriture sans verrou | `UserRepository#findByPhoneForUpdate` (`SELECT … FOR UPDATE`) : les essais d'un même compte sont traités l'un après l'autre |

### OTP

| Question | Avant | Après |
|---|---|---|
| Limite d'envoi par numéro | **Aucune.** `OTP_RATE_LIMITED` existait dans `ErrorCode` mais n'était jamais levé (« Réservé ») | **3 envois par numéro et par heure, tous motifs confondus** (inscription, mot de passe oublié, PIN oublié, changement de numéro) → 429 `OTP_RATE_LIMITED`, `data.retryAfterSeconds` |
| Limite par IP | **Aucune** | Non ajoutée (hors de la cible) ; voir § 4 |
| Essais par code | **Illimités** pendant les 10 min de validité | **5 essais faux** → code invalidé, 400 `OTP_TOO_MANY_ATTEMPTS` ; le bon code ne passe plus |
| Anti-énumération | Réponse silencieuse pour un numéro inconnu (mot de passe et PIN oubliés) | Conservée : la limite est appliquée **avant** le test d'existence, donc la réponse est la même pour un numéro inconnu |
| Concurrence | — | Verrou PostgreSQL par numéro (`pg_advisory_xact_lock(hashtext(phone))`) pendant le comptage |

**Ce qui a été fait (lot 1)** :
- **Migration `V045__securite_pin_otp.sql`** :
  - `users.pin_echecs_cumules` ;
  - `otp_verifications.tentatives` et `phone_change_requests.tentatives` (reprise à 0, puis `NOT NULL`) ;
  - table `envois_otp` (numéro, motif, date ; jamais le code).
- **Nouveau** `OtpGardeService(Impl)` :
  - `autoriserEnvoi` (verrou par numéro, comptage sur la dernière heure, journal) ;
  - `enregistrerEchec` (OTP et changement de numéro) ;
  - `erreurCodeFaux`.
- **Branché** dans :
  - `RegistrationServiceImpl#sendOtp/#verifyOtp` ;
  - `PasswordResetServiceImpl#sendForgotPasswordOtp/#verifyForgotPasswordOtp` ;
  - `PinServiceImpl#sendResetPinOtp/#verifyResetPinOtp` ;
  - `UserProfileServiceImpl#requestPhoneChange/#verifyPhoneChange`.
- **`PinServiceImpl`** : `#loginWithPin` (relecture verrouillée), `#handlePinFailure` (compteur cumulé), `#invaliderPin`. `Constants.Pin.LOCK_DURATION_MINUTES = 15`, `MAX_ECHECS_CUMULES = 10`, `Constants.Otp.MAX_ENVOIS_PAR_HEURE = 3`, `MAX_ESSAIS_PAR_CODE = 5`.
- **Codes** :
  - `OTP_TOO_MANY_ATTEMPTS` et `PIN_DISABLED` (ajoutés) ;
  - `OTP_RATE_LIMITED` (désormais utilisé ; sa Javadoc « Réservé » est corrigée).
- **Tests** :
  - `OtpGardeServiceImplTest` (5) ;
  - `PinServiceImplTest.Securite` (5) ;
  - `UserProfileServiceImplTest#garde` ;
  - IT `SecuritePinOtpIT` (5) : verrou puis délai écoulé, **deux appareils en parallèle (6 essais simultanés → exactement 4 `PIN_INVALID` et 2 `PIN_LOCKED`)**, invalidation au 10ᵉ échec, limite d'envoi (numéro inconnu compris, tous motifs), 5 essais par code.

---

## 2. Lot 2 (B53) : session longue par appareil de confiance

### Comment l'appareil est reconnu

- **Avant** : il n'y avait pas d'identité d'appareil. Une session par **(utilisateur, `clientType`)** ; `deviceInfo` est un texte libre écrasé à chaque connexion. Deux téléphones en `MOBILE` partageaient donc la même ligne : une connexion sur le téléphone B remplaçait le jeton du téléphone A, et le PIN tenté en MOBILE se rabattait même sur la session WEB.
- **Maintenant** :
  - le front envoie un **`appareilId`** stable dans `POST /login`, `/register/complete`, `/pin/setup`, `/login-pin` et `/logout` ;
  - l'`appareilId` est un UUID généré au premier lancement et gardé dans le stockage sécurisé (Keychain/Keystore) en natif, dans `localStorage` (ou IndexedDB) en PWA ;
  - il doit correspondre à `[A-Za-z0-9._-]{8,100}` ;
  - une session par (utilisateur, `appareilId`), avec un index unique partiel en V046 ;
  - `deviceInfo` reste un libellé d'affichage, jamais une clé.
- **Même téléphone, navigateur puis PWA installée** :
  - **Android/Chrome** : la PWA installée partage le stockage de l'origine avec le navigateur, donc même `appareilId` et pas de nouveau mot de passe.
  - **iOS/Safari** : la PWA ajoutée à l'écran d'accueil a **un stockage séparé** de Safari. C'est un nouvel `appareilId`, et le mot de passe est redemandé **une fois** dans la PWA. Le backend ne peut pas l'éviter. Les deux apparaissent comme deux appareils dans `/me/sessions`.
  - Navigation privée, ou données du site effacées : nouvel identifiant, mot de passe une fois.
- **Sans `appareilId`** (ancienne version du front) : le fonctionnement d'avant est conservé. Une session par type de client, **jamais** de jeton hors ligne, et le PIN expire avec SSO Session Idle.

### Ce qui a été fait

- **Migration `V046__sessions_appareil_confiance.sql`** :
  - `user_sessions.appareil_id` et `confiance` (reprise `false`) ;
  - index unique `(user_id, appareil_id)` sur les lignes où `appareil_id` est renseigné.
- **Keycloak** :
  - `KeycloakAuthServiceImpl#login(…, horsLigne)` : scope `openid profile email offline_access` ;
  - `#revoquerJeton` : endpoint `…/protocol/openid-connect/revoke` (RFC 7009), qui supprime la session hors ligne ;
  - non bloquant, aucun jeton journalisé.
- **Création de la session de confiance** (option 1 retenue) :
  - `LoginServiceImpl#login` : `appareilId`, PIN configuré et compte autre que SUPER_ADMIN donnent un jeton hors ligne et une session `confiance = true` (`#sessionDeConfiancePossible`) ;
  - `PinServiceImpl#setupPin` : nouveaux champs facultatifs `motDePasse`, `appareilId`, `clientType`, `deviceInfo`. Avec le mot de passe, cet appareil devient de confiance dès la création du PIN (réponse `{confiance}`) ;
  - mot de passe faux : 401 `INVALID_CREDENTIALS`, et le PIN n'est **pas** enregistré ;
  - SUPER_ADMIN : jamais de confiance.
- **`UserSessionServiceImpl`** :
  - `#enregistrerSessionAppareil` chiffre le jeton (`TokenEncryptionUtil`) et le remplace à chaque renouvellement. L'ancien jeton n'est révoqué chez Keycloak que s'il appartient à une **autre** session (`sid` différent), après une nouvelle connexion par mot de passe sur l'appareil. Une rotation (même `sid`) ne révoque rien, sinon elle supprimerait la session tout juste rafraîchie ;
  - `#trouverSessionAppareil` ;
  - `#synchroniserJeton` : après `/refresh`, comparaison à temps constant (`MessageDigest.isEqual`) ;
  - `#tracerRejeu` ;
  - `#revokeSession` / `#revokeAllSessions` révoquent les jetons hors ligne chez Keycloak.
- **`PinServiceImpl#loginWithPin`** :
  - rejoue la session de **cet** `appareilId` uniquement ; le repli sur une session d'un autre type ou d'un autre appareil est **supprimé** ;
  - aucune session, ou jeton refusé par Keycloak (révoqué, expiré, compte désactivé) : la session est purgée, 401 `PIN_SESSION_EXPIRED` ;
  - rotation de la session, rejeu audité.
- **`POST /v1/auth/refresh`** → `LoginService#rafraichir` : accepte le jeton hors ligne et met à jour la session de l'appareil qui le portait. Le contrôleur ne fait plus d'appel direct à Keycloak.
- **Révocation chez Keycloak** (endpoint de révocation) :
  - **déconnexion complète** : `POST /logout` avec `appareilId` révoque cet appareil (`LoginServiceImpl#logout(…, appareilId)`) ;
  - **`DELETE /v1/me/sessions/{id}`** : `CompteServiceImpl#revoquer` utilise `revoquerJeton` pour une session de confiance ;
  - **changement de mot de passe** : tous les appareils de confiance sauf celui en cours, même sans `deconnecterAutresAppareils` ; avec l'option, tous les autres comme avant (`CompteServiceImpl#changerMotDePasse`) ;
  - **changement de numéro** : tous (`revokeAllSessions`, existant, révoque désormais chez Keycloak) ;
  - **compte désactivé par un admin** : tous (`AdminDashboardServiceImpl#disableUser`, ajouté) ;
  - **également** : réinitialisation du PIN par OTP, `PIN_DISABLED`, mot de passe oublié (appels existants à `revokeAllSessions`).
- **Déconnexion douce** (retour à l'écran PIN) : le front oublie seulement le jeton d'accès, sans appeler `/logout`. Le jeton hors ligne reste sur le serveur.
- **`GET /v1/me/sessions`** : nouveaux champs `confiance`, `derniereUtilisation` et `appareilId` (`derniereActivite` est conservé).
- **Audit** dans `tontine_audit_log`, table `user_sessions`, champ `confiance` : `CREEE` (création), `REJOUEE` (chaque connexion par PIN), `REVOQUEE`. Aucune valeur de jeton n'est enregistrée.
- **Realm de test** (`src/test/resources/keycloak/realm-dinthialma.json`) : `revokeRefreshToken: true` et `offlineSessionIdleTimeout: 7776000` (90 j). `offline_access` était déjà un scope optionnel du client et un rôle par défaut du realm. Aucun autre export de realm n'existe dans le dépôt.
- **Tests** :
  - unitaires :
    - `PinServiceImplTest.SessionDeConfiance` (6) ;
    - `UserSessionServiceImplTest.SessionsParAppareil` (9) ;
    - `LoginServiceImplTest.AppareilDeConfiance` (2) et `.RafraichissementEtDeconnexion` (4) ;
    - `CompteServiceImplTest.AppareilsDeConfiance` (3) ;
    - `KeycloakAuthServiceImplTest#loginHorsLigne`, `#revocation` ;
  - **IT `SessionAppareilIT` (8)**, avec le Keycloak de Testcontainers :
    1. **Survit à la session SSO** : PIN créé avec mot de passe, jeton `typ = Offline` chiffré en base. La session SSO normale est supprimée chez Keycloak (son refresh token est alors refusé), et le PIN fonctionne encore.
    2. **Déconnexion à distance** : le jeton du téléphone est révoqué chez Keycloak, son PIN donne `PIN_SESSION_EXPIRED`, l'ordinateur fonctionne.
    3. **Changement de mot de passe** : l'autre appareil de confiance est révoqué.
    4. **Rotation** : après un rejeu par PIN, le dernier jeton reçu rafraîchit, la session suit, et le PIN marche toujours.
    5. **Rejeu d'un ancien jeton** : refusé, et Keycloak ferme la session ; le PIN demande une nouvelle connexion.
    6. **SUPER_ADMIN** : aucun jeton hors ligne, ni à la connexion ni à la création du PIN.
    7. **Sessions d'avant B53 et appareil inconnu** : `PIN_SESSION_EXPIRED`, puis la connexion par mot de passe avec `appareilId` rend l'appareil de confiance.
    8. **Compte désactivé** : sessions supprimées, jeton révoqué.
- **« SSO Session Idle très court »** : remplacé par la **suppression de la session SSO** via l'API d'administration (`deleteSession(sid, false)`). Keycloak ajoute une marge d'environ 2 min au délai d'inactivité, donc un délai très court obligerait le test à attendre plus de 2 min. La suppression produit le même état (session normale disparue, session hors ligne intacte), immédiatement.
- **« 5 PIN faux → `PIN_LOCKED`, puis à nouveau possible »** : couvert par `SecuritePinOtpIT#verrouillageEtDelai` (lot 1).

---

## 3. Réglages Keycloak à appliquer en production (realm `dinthialma`)

Console d'administration Keycloak 26. Je n'ai rien modifié en production.

| # | Écran | Champ | Valeur |
|---|---|---|---|
| 1 | *Realm settings* → onglet **Sessions** → bloc *Offline session settings* | **Offline Session Idle** | **90 Days** |
| 2 | même bloc | **Offline Session Max Limited** | **Off** (ou **On** avec **Offline Session Max** = **365 Days** si tu veux une reconnexion annuelle obligatoire) |
| 3 | *Realm settings* → **Sessions** → *SSO Session Settings* | SSO Session Idle / Max | **inchangés** (30 min / 10 h) : ils ne concernent plus le PIN des appareils de confiance |
| 4 | *Realm settings* → onglet **Tokens** → bloc *Refresh tokens* | **Revoke Refresh Token** | **On** |
| 5 | même bloc | **Refresh Token Max Reuse** | **0** |
| 6 | *Realm settings* → **Tokens** → *Access tokens* | **Access Token Lifespan** | **5 Minutes** (inchangé) |
| 7 | *Clients* → `dinthialma-client` → onglet **Client scopes** | `offline_access` | présent, type **Optional** (c'est le défaut ; sinon *Add client scope* → `offline_access` → *Optional*) |
| 8 | *Realm settings* → onglet **User registration** → *Default roles* | rôle `offline_access` | présent dans `default-roles-dinthialma` (défaut ; sinon *Assign role* → `offline_access`). Pour les comptes déjà créés : présent par héritage du rôle par défaut |
| 9 | *Clients* → `dinthialma-client` → onglet **Settings** → *Capability config* | **Direct access grants** | **On** (déjà nécessaire au login) |
| 10 | même client → *Login settings* | **Consent required** | **Off** (sinon `offline_access` exige un consentement) |
| 11 | même client → onglet **Advanced** → *Advanced settings* | **Client Offline Session Idle / Max** | **vides** (hériter du realm) |

**Revoke Refresh Token (4, 5)** :
- **Recommandé**, parce que chaque jeton ne sert qu'une fois et que le backend remplace celui de la session à chaque usage (PIN et `/refresh`).
- **Effet constaté en IT** : rejouer un ancien jeton est traité comme un vol, et Keycloak ferme la session de l'appareil. L'utilisateur repasse alors par le mot de passe. Le front doit donc **toujours enregistrer le dernier `refresh_token` reçu** et ne jamais réessayer avec un ancien (voir § 5).
- **Sans ce réglage** : un jeton volé reste utilisable jusqu'à son expiration (90 jours d'inactivité).

**Ordre** : appliquer 1 à 11 avant ou en même temps que le déploiement. Sans 7 et 8, la connexion avec `offline_access` échoue (`invalid_scope`) : le login de tout utilisateur ayant un PIN et un `appareilId` serait refusé.

---

## 4. Choix faits sans toi

1. **Option 1** : mot de passe facultatif à la création du PIN pour rendre l'appareil de confiance. À la connexion par mot de passe, jeton hors ligne seulement si le PIN existe déjà (règle « pas de jeton hors ligne sans PIN » respectée).
2. **WEB et MOBILE** : la confiance n'est **pas** réservée à un `clientType`. La PWA se présente en `WEB`, et c'est elle qui doit tenir des mois. La protection repose sur l'`appareilId` (connu du seul appareil), le PIN (verrou à 5, invalidation à 10) et la révocation.
3. **`appareilId` obligatoire pour la confiance** : sans identifiant, fonctionnement d'avant (compatibilité avec l'ancienne version du front pendant le déploiement).
4. **`PIN_SESSION_EXPIRED`** remplace `SESSION_EXPIRED` quand Keycloak refuse le jeton rejoué par `login-pin`. Le front n'a qu'un code à traiter pour repasser par le mot de passe. `/refresh` garde `SESSION_EXPIRED`.
5. **Repli entre types d'appareils supprimé** (règle « nouvel appareil »).
6. **Changement de mot de passe** : les appareils de confiance sont toujours révoqués (sauf celui en cours). Les autres sessions ne le sont que si `deconnecterAutresAppareils` (comme avant).
7. **`PIN_DISABLED`** en 401. Le compteur cumulé est remis à zéro par un succès, une configuration ou une réinitialisation, jamais par le verrou de 15 min.
8. **OTP** :
   - limite **par numéro, tous motifs confondus**. Une inscription suivie d'un « mot de passe oublié » et d'un « PIN oublié » dans l'heure fait 3 envois ; le 4ᵉ attend ;
   - les demandes pour un **numéro inconnu** comptent aussi (anti-énumération) ;
   - **pas de limite par IP**. L'IP réelle n'est pas fiable derrière le proxy (pas de configuration `X-Forwarded-For` de confiance). À faire avec la configuration du proxy si nécessaire.
9. **5ᵉ code faux** : le code est invalidé (`used = true`). Pour un changement de numéro, la demande expire.
10. **Audit de chaque rejeu** par PIN : une ligne par connexion par PIN (volume à surveiller).
11. **Nouvelle connexion par mot de passe** sur un appareil déjà de confiance : l'ancien jeton hors ligne (autre `sid`) est révoqué chez Keycloak, pour ne pas laisser de session hors ligne orpheline.
12. **SUPER_ADMIN** : il peut toujours créer un PIN, mais sans jamais de session de confiance (PIN limité à la session courte, 2FA en SE16).
13. **Journalisation** : aucune nouvelle ligne de journal ne contient de jeton, de PIN ou de mot de passe. Les erreurs Keycloak journalisent le message d'erreur, jamais le corps de la requête.

---

## 5. Impacts pour le front

**Codes** :

| Code | HTTP | Quand | Action du front |
|---|---|---|---|
| `PIN_SESSION_EXPIRED` | 401 | `login-pin` : pas de session pour cet appareil (nouvel appareil, utilisateur d'avant B53, session révoquée), ou jeton refusé par Keycloak | **Rediriger vers la connexion complète** (mot de passe), avec `appareilId` |
| `PIN_LOCKED` | 429 | 5 PIN faux | Afficher l'attente (`data.retryAfterSeconds`, 900 s au départ) ou proposer « PIN oublié » |
| `PIN_DISABLED` | 401 | 10ᵉ PIN faux cumulé | Message, puis connexion par mot de passe et création d'un nouveau PIN (ou « PIN oublié ») ; ensuite `login-pin` renvoie `PIN_NOT_CONFIGURED` |
| `OTP_RATE_LIMITED` | 429 | 4ᵉ envoi d'OTP dans l'heure pour ce numéro | Désactiver « Renvoyer le code » pendant `data.retryAfterSeconds` |
| `OTP_TOO_MANY_ATTEMPTS` | 400 | 5ᵉ code faux | « Demandez un nouveau code » |
| `INVALID_CREDENTIALS` | 401 | `pin/setup` avec un mot de passe faux | Redemander le mot de passe ; le PIN n'est pas créé |

**Flux** :
1. **Premier lancement** : générer `appareilId` (UUID), le stocker, et l'envoyer dans `login`, `register/complete`, `pin/setup`, `login-pin` et `logout`.
2. **Inscription (OTP)** → création du PIN : écran PIN **+ champ mot de passe** (le mot de passe qui vient d'être choisi). `pin/setup {pin, confirmPin, motDePasse, appareilId, clientType}` renvoie `{confiance: true}`. Ensuite, le PIN suffit pendant des mois.
3. **Connexion par mot de passe** d'un utilisateur qui a déjà un PIN : l'appareil devient de confiance directement.
4. **Au déploiement** (confirmé par l'IT `sessionsAnciennes`) : les utilisateurs actuels n'ont pas de session de confiance. Avec la nouvelle version du front, le premier `login-pin` renvoie **401 `PIN_SESSION_EXPIRED`**, avec le message « Aucune session active. Veuillez d'abord vous connecter avec votre mot de passe ». Le front redirige vers la connexion par mot de passe ; si le PIN existe, l'appareil devient de confiance immédiatement, et le PIN suffit ensuite. Sans PIN, il faut passer par la création du PIN avec mot de passe.
5. **Rafraîchissement** : toujours **remplacer** le `refresh_token` stocké par celui de la réponse (de `login`, `login-pin` ou `refresh`) avant toute autre requête, et ne jamais réessayer avec l'ancien. Rejouer un ancien jeton ferme la session de l'appareil (rotation Keycloak).
6. **Déconnexion douce** (verrouiller l'app, retour à l'écran PIN) : oublier le jeton d'accès, **ne pas** appeler `/logout`. **Déconnexion complète** (« Se déconnecter de cet appareil ») : `POST /logout {refreshToken, appareilId}`.
7. **Appareils connectés** (`GET /v1/me/sessions`) : badge « Appareil de confiance » (`confiance`), date `derniereUtilisation`, et repérage de l'appareil courant avec `courante` ou `appareilId`. La révocation à distance coupe le PIN de cet appareil.

---

## 6. `./mvnw clean verify` et messages de commit

**Lot 1 seul** (copie isolée du projet) : `./mvnw clean verify` → **BUILD SUCCESS** : 535 tests unitaires, 80 tests d'intégration, « All coverage checks have been met ».

**Lots 1 + 2** (branche complète) : `./mvnw clean verify` → **BUILD SUCCESS** : 560 tests unitaires, 88 tests d'intégration (dont `SecuritePinOtpIT` 5 et `SessionAppareilIT` 8), « All coverage checks have been met ».

**Tests existants modifiés** (tu as autorisé les adaptations liées aux règles du brief) :

| Test | Lot | Modification | Raison |
|---|---|---|---|
| `PinServiceImplTest#verrouillage` | 1 | verrou attendu ≈ 15 min (au lieu de > 29 min) | cible 15 min |
| `PinServiceImplTest` (`setUp`, `refusPrealables`) | 1 | stub `findByPhoneForUpdate` en plus de `findByPhone` | relecture verrouillée (deux appareils) |
| `PinServiceImplTest#repli` | 2 | **supprimé** | repli entre types d'appareils interdit (règle « nouvel appareil ») |
| `PinServiceImplTest#sansSession` | 2 | stub `findAnyActiveSession` retiré, remplacé par `verify(never())` | idem |
| `AdminDashboardServiceImplTest#desactivation` | 2 | **assertion ajoutée** : `revokeAllSessions` | révocation à la désactivation |
| `PinServiceImplTest`, `RegistrationServiceImplTest`, `PasswordResetServiceImplTest`, `UserProfileServiceImplTest` (argument du constructeur), `UserSessionServiceImplTest`, `LoginServiceImplTest`, `AdminDashboardServiceImplTest` | 1–2 | ajout de `@Mock` pour les nouvelles dépendances | sans eux, `@InjectMocks` passe `null` ; aucune assertion existante changée |
| `realm-dinthialma.json` (test) | 2 | `revokeRefreshToken: true`, `offlineSessionIdleTimeout: 7776000` | rotation testée, réglage cible de la production |

Le test existant `CompteServiceImplTest#changementMotDePasse` (« aucune lecture des sessions sans l'option ») est **resté inchangé** : le code passe par une requête dédiée aux seules sessions de confiance.

**Pour commiter** (le lot 1 est indexé, le lot 2 ne l'est pas) :

```bash
git commit -F- <<'EOF'
feat(auth): verrouillage du PIN et limites des OTP (SE07)

- PIN : verrou de 15 min après 5 échecs, PIN invalidé au 10e échec cumulé
  (PIN_DISABLED, sessions révoquées) ; relecture verrouillée de l'utilisateur
  pour deux appareils en parallèle
- OTP : 3 envois par numéro et par heure tous motifs confondus
  (OTP_RATE_LIMITED, numéro inconnu compris), 5 essais par code
  (OTP_TOO_MANY_ATTEMPTS) ; V045 (compteurs en base, journal envois_otp)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF

git add -A && git reset mvnw.cmd
git commit -F- <<'EOF'
feat(auth): session longue par appareil de confiance (B53)

- appareilId stable envoyé par le front ; une session par appareil (V046)
- jeton hors ligne Keycloak (offline_access) chiffré sur la session :
  connexion par mot de passe avec PIN, ou création du PIN avec le mot de
  passe ; jamais pour SUPER_ADMIN ni sans PIN
- login-pin rejoue la session de cet appareil uniquement (repli supprimé),
  PIN_SESSION_EXPIRED si Keycloak refuse ; rotation, /refresh synchronisé
- révocation chez Keycloak : déconnexion de l'appareil, déconnexion à
  distance, changement de mot de passe, de numéro, compte désactivé
- /me/sessions : confiance, derniereUtilisation, appareilId ; audit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

`mvnw.cmd` était déjà modifié avant cette branche (fins de ligne) : je ne l'ai pas touché.
