package com.africa.dinthialma_backend.epargne.entity;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.base.BaseEntity;
import com.africa.dinthialma_backend.epargne.codeList.NatureRetrait;
import com.africa.dinthialma_backend.epargne.codeList.OrigineRetrait;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Retrait anticipé d'une épargne événementielle (B42, V044). Au lot 1 : saisi par le gestionnaire,
 * argent déjà remis ({@code REMIS}), ou annulé ({@code ANNULE}) après une saisie erronée.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Entity
@Table(name = "retraits_epargne", schema = "dinthialma")
public class RetraitEpargne extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "tontine_id", nullable = false)
  private Tontine tontine;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "membre_id", nullable = false)
  private TontineMembre membre;

  @Enumerated(EnumType.STRING)
  @Column(name = "nature", nullable = false, length = 20)
  private NatureRetrait nature;

  /** Montant remis au membre (francs entiers). */
  @Column(name = "montant", nullable = false, precision = 12, scale = 2)
  private BigDecimal montant;

  /** Frais retenus (0 au lot 1). */
  @Column(name = "frais", nullable = false, precision = 12, scale = 2)
  private BigDecimal frais;

  @Enumerated(EnumType.STRING)
  @Column(name = "statut", nullable = false, length = 20)
  private StatutRetrait statut;

  @Enumerated(EnumType.STRING)
  @Column(name = "origine", nullable = false, length = 20)
  private OrigineRetrait origine;

  /** Code list METHODE_PAIEMENT (CASH, WAVE, OM…). */
  @Column(name = "methode_paiement", length = 50)
  private String methodePaiement;

  @Column(name = "reference_transaction", length = 100)
  private String referenceTransaction;

  @Column(name = "motif", length = 500)
  private String motif;

  /** Date réelle de la remise (heure de Dakar), éventuellement passée (report du cahier). */
  @Column(name = "date_remise")
  private LocalDateTime dateRemise;

  /** Gestionnaire qui a remis (ou saisi) le retrait. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "traite_par")
  private User traitePar;

  @Column(name = "motif_annulation", length = 500)
  private String motifAnnulation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "annule_par")
  private User annulePar;

  @Column(name = "date_annulation")
  private LocalDateTime dateAnnulation;

  /** Mouvement de caisse associé (réservé à B29). */
  @Column(name = "mouvement_id")
  private UUID mouvementId;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;
}
