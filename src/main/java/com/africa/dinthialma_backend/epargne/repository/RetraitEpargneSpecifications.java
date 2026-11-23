package com.africa.dinthialma_backend.epargne.repository;

import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.entity.RetraitEpargne;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Critères combinables de la liste des retraits ; {@code null} quand le filtre est absent (ignoré
 * par {@link Specification#allOf}).
 */
public final class RetraitEpargneSpecifications {

  private RetraitEpargneSpecifications() {}

  /** Retraits non supprimés d'une tontine. */
  public static Specification<RetraitEpargne> deTontine(UUID tontineId) {
    return (root, query, cb) ->
        cb.and(
            cb.equal(root.get("tontine").get("id"), tontineId), cb.isNull(root.get("deletedAt")));
  }

  public static Specification<RetraitEpargne> deMembre(UUID membreId) {
    if (membreId == null) return null;
    return (root, query, cb) -> cb.equal(root.get("membre").get("id"), membreId);
  }

  public static Specification<RetraitEpargne> statutParmi(Collection<StatutRetrait> statuts) {
    if (statuts == null || statuts.isEmpty()) return null;
    return (root, query, cb) -> root.get("statut").in(statuts);
  }
}
