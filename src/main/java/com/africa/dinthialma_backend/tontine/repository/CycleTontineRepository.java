package com.africa.dinthialma_backend.tontine.repository;

import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Accès base pour les cycles de tontine. */
@Repository
public interface CycleTontineRepository extends JpaRepository<CycleTontine, UUID> {

  /** Cycles d'une tontine triés par numéro croissant. */
  List<CycleTontine> findByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleAsc(UUID tontineId);

  /** Cycles d'une tontine paginés (tri via Pageable). */
  Page<CycleTontine> findByTontine_IdAndDeletedAtIsNull(UUID tontineId, Pageable pageable);

  /** Cycle par id dans le contexte d'une tontine. */
  Optional<CycleTontine> findByIdAndTontine_IdAndDeletedAtIsNull(UUID id, UUID tontineId);

  /** Tour d'un numéro donné (tour de reprise n° 0, B41). */
  Optional<CycleTontine> findByTontine_IdAndNumeroCycleAndDeletedAtIsNull(
      UUID tontineId, int numeroCycle);

  /** Vrai si la tontine a des tours au-delà d'un numéro (tours générés, hors reprise n° 0). */
  boolean existsByTontine_IdAndNumeroCycleGreaterThanAndDeletedAtIsNull(
      UUID tontineId, int numeroCycle);

  /** Vérifie si des cycles existent pour une tontine (utile avant regénération). */
  boolean existsByTontine_IdAndDeletedAtIsNull(UUID tontineId);

  /** Nombre de cycles d'une tontine. */
  long countByTontine_IdAndDeletedAtIsNull(UUID tontineId);

  /** Dernier numéro de cycle utilisé (pour générer le suivant en mode MANUEL). */
  Optional<CycleTontine> findTopByTontine_IdAndDeletedAtIsNullOrderByNumeroCycleDesc(
      UUID tontineId);

  /**
   * Cycles d'un statut donné d'une tontine, par numéro croissant. Plusieurs tours peuvent être
   * EN_COURS à la fois quand les tours s'ouvrent à leur date (B33, lot 3).
   */
  List<CycleTontine> findByTontine_IdAndStatutAndDeletedAtIsNullOrderByNumeroCycleAsc(
      UUID tontineId, CycleStatut statut);

  /**
   * Tours à ouvrir à leur date (B33, lot 3) : EN_ATTENTE, date de début atteinte, tontine ACTIVE en
   * ouverture A_LA_DATE.
   */
  @Query(
      "SELECT c FROM CycleTontine c WHERE c.statut ="
          + " com.africa.dinthialma_backend.tontine.codeList.CycleStatut.EN_ATTENTE"
          + " AND c.dateDebut <= :jour AND c.deletedAt IS NULL AND c.tontine.deletedAt IS NULL"
          + " AND c.tontine.statut ="
          + " com.africa.dinthialma_backend.tontine.codeList.TontineStatut.ACTIVE"
          + " AND c.tontine.ouvertureTours ="
          + " com.africa.dinthialma_backend.tontine.codeList.OuvertureTours.A_LA_DATE"
          + " ORDER BY c.numeroCycle")
  List<CycleTontine> findAOuvrir(@Param("jour") LocalDate jour);

  /**
   * Tours à remettre automatiquement (B33, lot 3) : EN_COURS, échéance passée, tontine rotative
   * ACTIVE avec la remise automatique. Les tours dont la remise a déjà été jugée impossible ne sont
   * repris que si {@code inclureSignales} (passage horaire) : ils sont surtout retentés à chaque
   * cotisation validée.
   */
  @Query(
      "SELECT c FROM CycleTontine c WHERE c.statut ="
          + " com.africa.dinthialma_backend.tontine.codeList.CycleStatut.EN_COURS"
          + " AND c.echeanceAt <= :maintenant AND c.deletedAt IS NULL"
          + " AND (:inclureSignales = true OR c.remiseAutoSignaleeAt IS NULL)"
          + " AND c.tontine.deletedAt IS NULL AND c.tontine.remiseAutomatique = true"
          + " AND c.tontine.statut ="
          + " com.africa.dinthialma_backend.tontine.codeList.TontineStatut.ACTIVE"
          + " AND c.tontine.tontineType ="
          + " com.africa.dinthialma_backend.tontine.codeList.TontineType.ROTATIVE"
          + " ORDER BY c.numeroCycle")
  List<CycleTontine> findARemettre(
      @Param("maintenant") LocalDateTime maintenant,
      @Param("inclureSignales") boolean inclureSignales);

  /** Cycles d'un statut donné pour un ensemble de tontines (ex : tours EN_COURS). */
  List<CycleTontine> findByTontine_IdInAndStatutAndDeletedAtIsNull(
      Collection<UUID> tontineIds, CycleStatut statut);

  /**
   * Nombre de cycles par tontine, hors tour de reprise (B41). Chaque ligne : {@code [tontineId
   * (UUID), nombre (Long)]}.
   */
  @Query(
      "SELECT c.tontine.id, COUNT(c) FROM CycleTontine c"
          + " WHERE c.tontine.id IN :tontineIds AND c.deletedAt IS NULL AND c.numeroCycle > 0"
          + " GROUP BY c.tontine.id")
  List<Object[]> countByTontineIds(@Param("tontineIds") Collection<UUID> tontineIds);

  /** Tous les cycles d'un statut donné sur toute la plateforme (usage scheduler). */
  List<CycleTontine> findByStatutAndDeletedAtIsNull(CycleStatut statut);

  /** Cycles EN_COURS dont la date de fin est exactement à une date donnée (rappels scheduler). */
  @Query(
      "SELECT c FROM CycleTontine c WHERE c.statut = :statut"
          + " AND c.dateFin = :dateFin AND c.deletedAt IS NULL")
  List<CycleTontine> findByStatutAndDateFinAndDeletedAtIsNull(
      @Param("statut") CycleStatut statut, @Param("dateFin") LocalDate dateFin);

  /** Vrai si la tontine a au moins un cycle TERMINÉ sans gagnant désigné. */
  @Query(
      "SELECT COUNT(c) > 0 FROM CycleTontine c WHERE c.tontine.id = :tontineId"
          + " AND c.statut = com.africa.dinthialma_backend.tontine.codeList.CycleStatut.TERMINE"
          + " AND c.deletedAt IS NULL"
          + " AND NOT EXISTS (SELECT g FROM CycleGagnant g WHERE g.cycle = c AND g.deletedAt IS NULL)")
  boolean existsTermineSansGagnant(@Param("tontineId") UUID tontineId);

  /** Cycles TERMINÉ ayant au moins un gagnant désigné, pour l'historique jackpots d'une tontine. */
  @Query(
      value =
          "SELECT c FROM CycleTontine c WHERE c.tontine.id = :tontineId"
              + " AND c.statut = :statut AND c.deletedAt IS NULL"
              + " AND EXISTS (SELECT g FROM CycleGagnant g WHERE g.cycle = c AND g.deletedAt IS NULL)",
      countQuery =
          "SELECT COUNT(c) FROM CycleTontine c WHERE c.tontine.id = :tontineId"
              + " AND c.statut = :statut AND c.deletedAt IS NULL"
              + " AND EXISTS (SELECT g FROM CycleGagnant g WHERE g.cycle = c AND g.deletedAt IS NULL)")
  Page<CycleTontine> findTerminesAvecGagnants(
      @Param("tontineId") UUID tontineId, @Param("statut") CycleStatut statut, Pageable pageable);
}
