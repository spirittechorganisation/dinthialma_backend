package com.africa.dinthialma_backend.epargne.repository;

import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.entity.RetraitEpargne;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RetraitEpargneRepository
    extends JpaRepository<RetraitEpargne, UUID>, JpaSpecificationExecutor<RetraitEpargne> {

  Optional<RetraitEpargne> findByIdAndTontine_IdAndDeletedAtIsNull(UUID id, UUID tontineId);

  /** Total retiré par un membre : retraits REMIS, montant + frais. */
  @Query(
      "SELECT COALESCE(SUM(r.montant + r.frais), 0) FROM RetraitEpargne r"
          + " WHERE r.membre.id = :membreId"
          + "   AND r.statut = 'REMIS'"
          + "   AND r.deletedAt IS NULL")
  BigDecimal sumRemisByMembre(@Param("membreId") UUID membreId);

  /** Total retiré par membre d'une tontine : lignes {@code [membreId (UUID), total]}. */
  @Query(
      "SELECT r.membre.id, COALESCE(SUM(r.montant + r.frais), 0) FROM RetraitEpargne r"
          + " WHERE r.tontine.id = :tontineId"
          + "   AND r.statut = 'REMIS'"
          + "   AND r.deletedAt IS NULL"
          + " GROUP BY r.membre.id")
  List<Object[]> sumRemisGroupByMembre(@Param("tontineId") UUID tontineId);

  /** Total retiré pour ces memberships : lignes {@code [membreId (UUID), total]}. */
  @Query(
      "SELECT r.membre.id, COALESCE(SUM(r.montant + r.frais), 0) FROM RetraitEpargne r"
          + " WHERE r.membre.id IN :membreIds"
          + "   AND r.statut = 'REMIS'"
          + "   AND r.deletedAt IS NULL"
          + " GROUP BY r.membre.id")
  List<Object[]> sumRemisGroupByMembreIn(@Param("membreIds") Collection<UUID> membreIds);

  /** Vrai si le membre a déjà un retrait dans l'un de ces statuts (demande en cours, B42). */
  boolean existsByMembre_IdAndStatutInAndDeletedAtIsNull(
      UUID membreId, Collection<StatutRetrait> statuts);

  /** Retraits d'une tontine dans ces statuts (demandes à annuler à la distribution, B42). */
  List<RetraitEpargne> findByTontine_IdAndStatutInAndDeletedAtIsNull(
      UUID tontineId, Collection<StatutRetrait> statuts);

  /** Retraits de ces memberships dans ces statuts (demande en cours dans /me/summary). */
  List<RetraitEpargne> findByMembre_IdInAndStatutInAndDeletedAtIsNull(
      Collection<UUID> membreIds, Collection<StatutRetrait> statuts);

  /** Nombre de retraits d'une tontine dans ces statuts (demandes à traiter, my-dashboard). */
  long countByTontine_IdAndStatutInAndDeletedAtIsNull(
      UUID tontineId, Collection<StatutRetrait> statuts);

  /** Tous les retraits d'une tontine (export), membre et utilisateur chargés. */
  @Query(
      "SELECT r FROM RetraitEpargne r"
          + " JOIN FETCH r.membre m"
          + " JOIN FETCH m.user"
          + " WHERE r.tontine.id = :tontineId AND r.deletedAt IS NULL"
          + " ORDER BY r.dateRemise ASC, r.createdAt ASC")
  List<RetraitEpargne> findPourExport(@Param("tontineId") UUID tontineId);
}
