package com.africa.dinthialma_backend.tontine.repository;

import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DistributionEvenementielleRepository
    extends JpaRepository<DistributionEvenementielle, UUID> {

  /**
   * Répartition finale d'une tontine (B51), membre, utilisateur et sous-cycle chargés, dans l'ordre
   * de passage puis d'adhésion. Vide tant que la distribution n'est pas faite.
   */
  @Query(
      "SELECT d FROM DistributionEvenementielle d"
          + " JOIN FETCH d.membre m"
          + " JOIN FETCH m.user"
          + " JOIN FETCH d.cycle"
          + " WHERE d.tontine.id = :tontineId AND d.deletedAt IS NULL"
          + " ORDER BY m.ordreJackpot ASC NULLS LAST, m.createdAt ASC")
  List<DistributionEvenementielle> findByTontineAvecMembres(@Param("tontineId") UUID tontineId);

  /** Répartition enregistrée à la clôture de ce sous-cycle (B51) ; vide pour les autres. */
  @Query(
      "SELECT d FROM DistributionEvenementielle d"
          + " JOIN FETCH d.membre m"
          + " JOIN FETCH m.user"
          + " WHERE d.cycle.id = :cycleId AND d.deletedAt IS NULL"
          + " ORDER BY m.ordreJackpot ASC NULLS LAST, m.createdAt ASC")
  List<DistributionEvenementielle> findByCycleAvecMembres(@Param("cycleId") UUID cycleId);

  /** Distribution déjà enregistrée pour cette tontine (protection contre une double clôture). */
  boolean existsByTontine_IdAndDeletedAtIsNull(UUID tontineId);

  /**
   * Montant net et nombre de distributions reçues, par adhésion : lignes {@code [membreId (UUID),
   * total (BigDecimal), nombre (Long)]} ; une adhésion sans distribution n'a pas de ligne.
   */
  @Query(
      "SELECT d.membre.id, COALESCE(SUM(d.montantNet), 0), COUNT(d) FROM DistributionEvenementielle d"
          + " WHERE d.membre.id IN :membreIds"
          + "   AND d.deletedAt IS NULL"
          + "   AND d.tontine.deletedAt IS NULL"
          + " GROUP BY d.membre.id")
  List<Object[]> sumAndCountByMembreIds(@Param("membreIds") Collection<UUID> membreIds);

  /** Total net reçu par un utilisateur, toutes tontines événementielles confondues. */
  @Query(
      "SELECT COALESCE(SUM(d.montantNet), 0) FROM DistributionEvenementielle d"
          + " WHERE d.membre.user.id = :userId"
          + "   AND d.deletedAt IS NULL"
          + "   AND d.tontine.deletedAt IS NULL")
  BigDecimal sumMontantNetByUserId(@Param("userId") UUID userId);

  /** Distributions reçues par un utilisateur, cycle et tontine chargés (plus récentes d'abord). */
  @Query(
      "SELECT d FROM DistributionEvenementielle d"
          + " JOIN FETCH d.cycle c"
          + " JOIN FETCH d.tontine t"
          + " WHERE d.membre.user.id = :userId"
          + "   AND d.deletedAt IS NULL"
          + "   AND t.deletedAt IS NULL"
          + " ORDER BY c.dateFin DESC")
  List<DistributionEvenementielle> findRecuesByUserId(@Param("userId") UUID userId);
}
