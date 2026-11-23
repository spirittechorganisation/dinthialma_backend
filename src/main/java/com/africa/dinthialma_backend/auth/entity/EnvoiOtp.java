package com.africa.dinthialma_backend.auth.entity;

import com.africa.dinthialma_backend.common.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Demande d'envoi d'OTP (SE07, V045) : journal servant à limiter les envois par numéro et par
 * heure, tous motifs confondus. Le code lui-même n'y figure pas.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "envois_otp", schema = "dinthialma")
public class EnvoiOtp extends BaseEntity {

  @Column(name = "phone", nullable = false, length = 30)
  private String phone;

  @Column(name = "purpose", nullable = false, length = 30)
  private String purpose;
}
