package com.africa.dinthialma_backend.auth.repository;

import com.africa.dinthialma_backend.auth.entity.EnvoiOtp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EnvoiOtpRepository extends JpaRepository<EnvoiOtp, UUID> {

  /** Envois demandés pour ce numéro depuis {@code depuis}. */
  long countByPhoneAndCreatedAtAfter(String phone, LocalDateTime depuis);

  /** Plus ancien envoi de la fenêtre : son âge donne le délai d'attente. */
  Optional<EnvoiOtp> findTopByPhoneAndCreatedAtAfterOrderByCreatedAtAsc(
      String phone, LocalDateTime depuis);

  /**
   * Verrou transactionnel PostgreSQL propre à ce numéro : deux demandes simultanées pour le même
   * numéro sont comptées l'une après l'autre (libéré à la fin de la transaction).
   */
  @Query(
      value = "SELECT CAST(pg_advisory_xact_lock(hashtext(:phone)) AS VARCHAR)",
      nativeQuery = true)
  String verrouillerNumero(@Param("phone") String phone);
}
