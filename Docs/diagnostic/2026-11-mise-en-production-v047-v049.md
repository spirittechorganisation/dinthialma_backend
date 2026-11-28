# Mise en production : migrations V047, V048, V049

> Contenu : B33 lot 3 (V047), B41 lots 1 et 2 (V048), B42 lot 2 et `regleRetrait` figée hors BROUILLON (V049).
> V047 et V048 sont dans `main`. V049 est sur la branche `feature/b41-lot2-affichage-reprise` : la fusionner avant de déployer.

## 1. Avant le déploiement

- Sauvegarder la base (`pg_dump` du schéma `dinthialma`).
- Une seule instance du backend : les tâches planifiées n'ont pas de verrou partagé (ShedLock à ajouter avant une deuxième instance).
- Variable facultative : `SCHEDULING_POOL_SIZE` (défaut 4).

## 2. Migrations, dans l'ordre (Flyway les joue au démarrage)

| Version | Fichier | Effet sur les tontines existantes |
|---|---|---|
| V047 | `ouverture_tours_remise_automatique.sql` | `ouverture_tours = A_LA_REMISE`, `remise_automatique = false` ; `cycles_tontine.remise_auto_signalee_at` vide ; deux index. **Rien de visible** : le tour suivant s'ouvre toujours à la remise du précédent, aucune remise automatique. Les nouvelles tontines sont créées en `A_LA_DATE`. |
| V048 | `reprise_tontine_commencee.sql` | `date_debut_reelle` et `reprise_figee_le` vides ; `cotisations.origine` rempli (`DECLARATION` si la cotisation a été enregistrée par le membre, sinon `SAISIE`), puis `NOT NULL`. **Seul changement visible** : la colonne « Origine » en fin de ligne de l'export CSV des cotisations. |
| V049 | `demande_retrait_membre.sql` | Colonnes d'acceptation et de refus des retraits, vides ; index unique « une demande en cours par membre » (aucun conflit possible : avant V049, les retraits sont tous `REMIS` ou `ANNULE`). **Rien de visible.** |

Sans migration : `regleRetrait` est **conservée** pour toutes les tontines ; elle n'est plus modifiable une fois la tontine active (`REGLE_RETRAIT_NON_MODIFIABLE`).

## 3. Contrôles après le démarrage (lecture seule)

```sql
-- Les trois migrations sont passées
SELECT installed_rank, version, description, success, installed_on
FROM dinthialma.flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 5;

-- Tontines existantes : A_LA_REMISE et remise automatique désactivée
SELECT ouverture_tours, remise_automatique, COUNT(*)
FROM dinthialma.tontines
GROUP BY ouverture_tours, remise_automatique;

-- Aucune cotisation sans origine
SELECT origine, COUNT(*)
FROM dinthialma.cotisations
GROUP BY origine;

-- Index créés
SELECT indexname
FROM pg_indexes
WHERE schemaname = 'dinthialma'
  AND indexname IN ('idx_cycles_statut_date_debut', 'idx_cycles_statut_echeance',
                    'uq_retrait_en_cours_membre');
```

Attendu : `success = true` pour 47, 48 et 49 ; toutes les tontines antérieures en `A_LA_REMISE` / `false` ; seulement `DECLARATION` et `SAISIE` dans `origine` ; trois index.

## 4. Points à tester après le déploiement

1. **Tontine existante** : clôturer un tour → le tour suivant s'ouvre comme avant ; aucun tour ne s'ouvre seul.
2. **Nouvelle rotative** : `ouvertureTours = A_LA_DATE` dans la réponse de création ; `PATCH /reglages` accepte `ouvertureTours` et `remiseAutomatique`.
3. **Logs** : passage du planificateur des tours toutes les 5 minutes sans erreur (`Tours planifiés` n'apparaît que s'il ouvre ou remet un tour).
4. **Reprise (B41)** sur une épargne de test : `PUT /reprise`, `GET /reprise` (gestionnaire et membre), `POST /reprise/figer` → notification `SOLDE_DEPART` ; ligne `REPRISE` en bas de `/me/activite`.
5. **Demande de retrait (B42 lot 2)** : le membre demande → le gestionnaire reçoit `RETRAIT_DEMANDE` ; accepter, remettre, refuser ; `retraitEnCours` dans `/me/summary`.
6. **Règle de retrait** : `PATCH /reglages` avec une autre `regleRetrait` sur une tontine active → 400 `REGLE_RETRAIT_NON_MODIFIABLE`.
7. **Export CSV des cotisations** : dernière colonne « Origine ».
8. **WhatsApp** : les nouveaux messages (solde de départ, étapes des retraits) partent dans la famille `JACKPOT`, active par défaut.

## 5. Retour arrière

Flyway n'annule pas les migrations. Les colonnes ajoutées sont compatibles avec l'ancien code, **sauf** `cotisations.origine` (`NOT NULL` depuis V048) : un retour à une version antérieure sans `origine` ferait échouer toute nouvelle cotisation. En cas de retour arrière, rendre d'abord la colonne facultative (`ALTER TABLE dinthialma.cotisations ALTER COLUMN origine DROP NOT NULL;`), ou redéployer la version corrigée.
