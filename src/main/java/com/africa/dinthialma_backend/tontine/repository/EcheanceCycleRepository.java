package com.africa.dinthialma_backend.tontine.repository;

import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.entity.EcheanceCycle;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EcheanceCycleRepository extends JpaRepository<EcheanceCycle, UUID> {

  /**
   * Échéances actives dont la limite tombe dans [de, a], sur un tour et une tontine aux statuts
   * donnés (rappels et cotisations manquantes, B26). Tour et tontine chargés avec l'échéance.
   */
  @Query(
      "SELECT e FROM EcheanceCycle e JOIN FETCH e.cycle c JOIN FETCH c.tontine t"
          + " WHERE e.deletedAt IS NULL AND c.deletedAt IS NULL AND t.deletedAt IS NULL"
          + " AND c.statut = :statutTour AND t.statut = :statutTontine"
          + " AND e.echeanceAt BETWEEN :de AND :a")
  List<EcheanceCycle> findActivesEntre(
      @Param("de") LocalDateTime de,
      @Param("a") LocalDateTime a,
      @Param("statutTour") CycleStatut statutTour,
      @Param("statutTontine") TontineStatut statutTontine);

  /** Échéance active d'un tour donné (paiement d'une échéance désignée, B32). */
  Optional<EcheanceCycle> findByIdAndCycle_IdAndDeletedAtIsNull(UUID id, UUID cycleId);

  /** Échéance active d'une tontine donnée (filtres par échéance, B32). */
  Optional<EcheanceCycle> findByIdAndCycle_Tontine_IdAndDeletedAtIsNull(UUID id, UUID tontineId);

  /**
   * Nombre d'échéances actives par tour, pour les tours actifs d'un ensemble de tontines. Chaque
   * ligne : {@code [cycleId (UUID), nombre (Long)]}.
   */
  @Query(
      "SELECT e.cycle.id, COUNT(e) FROM EcheanceCycle e"
          + " WHERE e.cycle.tontine.id IN :tontineIds"
          + "   AND e.deletedAt IS NULL AND e.cycle.deletedAt IS NULL"
          + " GROUP BY e.cycle.id")
  List<Object[]> countByCycleForTontines(@Param("tontineIds") List<UUID> tontineIds);

  /**
   * Échéances à régulariser (B33) : échéances des tours TERMINE de tontines rotatives sur
   * lesquelles un membership n'a aucune cotisation active (hors REFUSE). Chaque ligne : {@code
   * [échéance (EcheanceCycle), tour (CycleTontine), membreId (UUID)]}, par tour puis par échéance.
   */
  @Query(
      "SELECT e, c, m.id FROM EcheanceCycle e JOIN e.cycle c, TontineMembre m"
          + " WHERE m.id IN :membreIds AND c.tontine = m.tontine"
          + "   AND c.statut = com.africa.dinthialma_backend.tontine.codeList.CycleStatut.TERMINE"
          + "   AND c.tontine.tontineType ="
          + "       com.africa.dinthialma_backend.tontine.codeList.TontineType.ROTATIVE"
          + "   AND c.deletedAt IS NULL AND e.deletedAt IS NULL"
          + "   AND NOT EXISTS (SELECT 1 FROM Cotisation co WHERE co.echeance = e AND co.membre = m"
          + "     AND co.deletedAt IS NULL"
          + "     AND co.statut <> com.africa.dinthialma_backend.contribution.codeList.CotisationStatut.REFUSE)"
          + " ORDER BY c.numeroCycle, e.numero")
  List<Object[]> findARegulariserByMembreIds(@Param("membreIds") List<UUID> membreIds);
}
