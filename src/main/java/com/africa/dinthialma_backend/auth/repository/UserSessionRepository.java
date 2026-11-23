package com.africa.dinthialma_backend.auth.repository;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.entity.UserSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

  /**
   * Session d'avant B53 (sans identifiant d'appareil), active ou expirée, pour un type de client.
   * Utilisé par {@code createOrUpdateSession} pour savoir s'il faut créer ou mettre à jour.
   */
  @Query(
      "SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.clientType = :clientType"
          + " AND s.appareilId IS NULL")
  Optional<UserSession> findByUserIdAndClientType(
      @Param("userId") UUID userId, @Param("clientType") ClientType clientType);

  /**
   * Session active d'avant B53 (sans identifiant d'appareil) pour un type de client. Filtre sur
   * {@code expiresAt > now} pour ne retourner que les sessions valides.
   */
  @Query(
      "SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.clientType = :clientType"
          + " AND s.appareilId IS NULL AND s.expiresAt > :now")
  Optional<UserSession> findByUserIdAndClientTypeAndExpiresAtAfter(
      @Param("userId") UUID userId,
      @Param("clientType") ClientType clientType,
      @Param("now") LocalDateTime now);

  /** Session de cet appareil (B53), active ou expirée. */
  Optional<UserSession> findByUserIdAndAppareilId(UUID userId, String appareilId);

  /** Sessions de confiance d'un utilisateur (jetons hors ligne à révoquer chez Keycloak). */
  List<UserSession> findByUserIdAndConfianceTrue(UUID userId);

  /**
   * Recherche n'importe quelle session active de l'utilisateur, tous types confondus. Retourne la
   * session avec la date d'expiration la plus éloignée (la plus durable).
   */
  Optional<UserSession> findTopByUserIdAndExpiresAtAfterOrderByExpiresAtDesc(
      UUID userId, LocalDateTime now);

  /** Sessions d'un utilisateur, la plus récemment utilisée d'abord (appareils connectés). */
  List<UserSession> findByUserIdOrderByLastUsedAtDesc(UUID userId);

  /** Toutes les sessions (actives et expirées) d'un utilisateur, paginées. */
  Page<UserSession> findByUserId(UUID userId, Pageable pageable);

  /** Supprime toutes les sessions d'un utilisateur (déconnexion totale / reset PIN). */
  void deleteAllByUserId(UUID userId);

  /** Supprime la session d'un utilisateur pour un type de client précis. */
  void deleteByUserIdAndClientType(UUID userId, ClientType clientType);

  /** Sessions expirées à purger (job de nettoyage). */
  List<UserSession> findAllByExpiresAtBefore(LocalDateTime threshold);
}
