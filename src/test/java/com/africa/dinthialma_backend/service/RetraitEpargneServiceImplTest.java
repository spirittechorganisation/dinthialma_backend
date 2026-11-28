package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.common.util.FichierExport;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.interfaces.RepriseService;
import com.africa.dinthialma_backend.epargne.codeList.NatureRetrait;
import com.africa.dinthialma_backend.epargne.codeList.OrigineRetrait;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.dto.RetraitRequests;
import com.africa.dinthialma_backend.epargne.dto.SoldeEpargneResponse;
import com.africa.dinthialma_backend.epargne.entity.RetraitEpargne;
import com.africa.dinthialma_backend.epargne.mapper.RetraitEpargneMapper;
import com.africa.dinthialma_backend.epargne.repository.RetraitEpargneRepository;
import com.africa.dinthialma_backend.epargne.service.impl.RetraitEpargneServiceImpl;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.dto.NotificationLiens;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.RegleRetrait;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetraitEpargneServiceImpl : retraits anticipés saisis par le gestionnaire (B42)")
class RetraitEpargneServiceImplTest {

  /** Maintenant : 30/11/2026 à 10h00, heure de Dakar (= UTC). */
  private static final LocalDateTime MAINTENANT = LocalDateTime.of(2026, 11, 30, 10, 0);

  @Mock private RetraitEpargneRepository retraitRepository;
  @Spy private RetraitEpargneMapper retraitMapper = Mappers.getMapper(RetraitEpargneMapper.class);
  @Mock private TontineRepository tontineRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private CotisationRepository cotisationRepository;
  @Mock private DistributionEvenementielleRepository distributionRepository;
  @Mock private AccessControl accessControl;
  @Mock private AuditService auditService;
  @Mock private NotificationService notificationService;
  @Mock private WhatsappService whatsappService;
  @Mock private RepriseService repriseService;

  @Spy private Clock horloge = Clock.fixed(MAINTENANT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  @InjectMocks private RetraitEpargneServiceImpl service;

  private User gestionnaire;
  private User awa;
  private User binta;
  private User etranger;
  private Tontine tontine;
  private TontineMembre membreAwa;
  private TontineMembre membreBinta;
  private String kc;

  @BeforeEach
  void setUp() throws CustomException {
    gestionnaire = Fixtures.user("Gestionnaire");
    awa = Fixtures.user("Awa");
    binta = Fixtures.user("Binta");
    etranger = Fixtures.user("Étranger");
    kc = gestionnaire.getKeycloakId();
    tontine = Fixtures.tontine(gestionnaire);
    tontine.setTontineType(TontineType.EVENEMENTIELLE);
    tontine.setRegleRetrait(RegleRetrait.LIBRE);
    tontine.setDateDebut(LocalDate.of(2026, 11, 25));
    membreAwa = Fixtures.membre(tontine, awa, 1);
    membreBinta = Fixtures.membre(tontine, binta, 2);
    for (User u : List.of(gestionnaire, awa, binta, etranger)) {
      lenient().when(accessControl.utilisateur(u.getKeycloakId())).thenReturn(u);
    }
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    for (TontineMembre m : List.of(membreAwa, membreBinta)) {
      lenient()
          .when(
              membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(m.getId(), tontine.getId()))
          .thenReturn(Optional.of(m));
      lenient()
          .when(
              membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                  tontine.getId(), m.getUser().getId()))
          .thenReturn(Optional.of(m));
    }
    cotise(membreAwa, "46000");
    cotise(membreBinta, "42000");
    lenient()
        .when(retraitRepository.save(any()))
        .thenAnswer(
            i -> {
              RetraitEpargne r = i.getArgument(0);
              if (r.getId() == null) r.setId(UUID.randomUUID());
              return r;
            });
  }

  private void cotise(TontineMembre membre, String total) {
    lenient()
        .when(
            cotisationRepository.sumMontantValideByTontineAndMembre(
                tontine.getId(), membre.getId()))
        .thenReturn(new BigDecimal(total));
  }

  private void dejaRetire(TontineMembre membre, String total) {
    lenient()
        .when(retraitRepository.sumRemisByMembre(membre.getId()))
        .thenReturn(new BigDecimal(total));
  }

  private static RetraitRequests.Saisir saisie(
      TontineMembre membre, NatureRetrait nature, String montant, LocalDateTime dateRemise) {
    RetraitRequests.Saisir r = new RetraitRequests.Saisir();
    r.setMembreId(membre.getId());
    r.setNature(nature);
    r.setMontant(montant != null ? new BigDecimal(montant) : null);
    r.setMethodePaiement("CASH");
    r.setMotif("Frais de scolarité");
    r.setDateRemise(dateRemise);
    return r;
  }

  private RetraitEpargne retrait(TontineMembre membre, NatureRetrait nature, StatutRetrait statut) {
    RetraitEpargne r =
        RetraitEpargne.builder()
            .id(UUID.randomUUID())
            .tontine(tontine)
            .membre(membre)
            .nature(nature)
            .montant(new BigDecimal("10000"))
            .frais(BigDecimal.ZERO)
            .statut(statut)
            .origine(OrigineRetrait.GESTIONNAIRE)
            .methodePaiement("CASH")
            .dateRemise(MAINTENANT.minusDays(1))
            .createdAt(MAINTENANT.minusDays(1))
            .build();
    lenient()
        .when(retraitRepository.findByIdAndTontine_IdAndDeletedAtIsNull(r.getId(), tontine.getId()))
        .thenReturn(Optional.of(r));
    return r;
  }

  private static void code(ThrowingCallable appel, ErrorCode attendu) {
    assertThatThrownBy(appel)
        .isInstanceOf(CustomException.class)
        .satisfies(e -> assertThat(((CustomException) e).getCode()).isEqualTo(attendu));
  }

  @Nested
  @DisplayName("saisie d'un retrait remis")
  class Saisie {

    @Test
    @DisplayName(
        "partiel daté d'hier (report du cahier, > 24 h) : REMIS, solde restant, aucune notification")
    void partielReporte() throws Exception {
      LocalDateTime hier = LocalDateTime.of(2026, 11, 29, 8, 0);

      RetraitEpargneResponse r =
          service.enregistrer(
              kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "10000", hier));

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.REMIS);
      assertThat(r.getOrigine()).isEqualTo(OrigineRetrait.GESTIONNAIRE);
      assertThat(r.getMontant()).isEqualByComparingTo("10000");
      assertThat(r.getFrais()).isEqualByComparingTo("0");
      assertThat(r.getDateRemise()).isEqualTo(hier);
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("36000");
      assertThat(r.getMembreId()).isEqualTo(membreAwa.getId());
      assertThat(membreAwa.getSoldeLe()).isNull();
      verify(auditService).logCreate(eq(gestionnaire), eq("retraits_epargne"), any());
      verifyNoInteractions(notificationService, whatsappService);
    }

    @Test
    @DisplayName("partiel sans date : maintenant, membre notifié (in-app + WhatsApp)")
    void partielNotifie() throws Exception {
      dejaRetire(membreAwa, "6000");

      RetraitEpargneResponse r =
          service.enregistrer(
              kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "10000", null));

      assertThat(r.getDateRemise()).isEqualTo(MAINTENANT);
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("30000");
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.RETRAIT_REMIS),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any(NotificationLiens.class));
      verify(whatsappService).sendRetraitRemis(awa.getPhone(), tontine.getNom(), "10000", "30000");
    }

    @Test
    @DisplayName(
        "total avec des cotisations en attente ou en retard → RETRAIT_COTISATIONS_EN_ATTENTE,"
            + " rien n'est enregistré")
    void totalAvecCotisationsEnAttente() {
      when(cotisationRepository.countByMembre_IdAndStatutInAndDeletedAtIsNull(
              membreBinta.getId(),
              List.of(CotisationStatut.EN_ATTENTE, CotisationStatut.EN_RETARD)))
          .thenReturn(2L);

      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), saisie(membreBinta, NatureRetrait.TOTAL, null, null)))
          .isInstanceOf(BadRequestException.class)
          .satisfies(
              e -> {
                CustomException ce = (CustomException) e;
                assertThat(ce.getCode()).isEqualTo(ErrorCode.RETRAIT_COTISATIONS_EN_ATTENTE);
                assertThat(ce.getDetails()).containsEntry("cotisationsEnAttente", 2L);
              });
      verify(retraitRepository, never()).save(any());
      assertThat(membreBinta.getSoldeLe()).isNull();
    }

    @Test
    @DisplayName("retrait partiel malgré des cotisations en attente : accepté (aucun contrôle)")
    void partielAvecCotisationsEnAttente() throws Exception {
      assertThat(
              service
                  .enregistrer(
                      kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null))
                  .getStatut())
          .isEqualTo(StatutRetrait.REMIS);
      verify(cotisationRepository, never())
          .countByMembre_IdAndStatutInAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("total : montant = solde, membre soldé ; montant différent du solde refusé")
    void total() throws Exception {
      dejaRetire(membreBinta, "2000");
      LocalDateTime avantHier = MAINTENANT.minusDays(2);

      RetraitEpargneResponse r =
          service.enregistrer(
              kc, tontine.getId(), saisie(membreBinta, NatureRetrait.TOTAL, null, avantHier));

      assertThat(r.getMontant()).isEqualByComparingTo("40000");
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("0");
      assertThat(membreBinta.getSoldeLe()).isEqualTo(avantHier);
      verify(membreRepository).save(membreBinta);

      membreBinta.setSoldeLe(null);
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreBinta, NatureRetrait.TOTAL, "1000", null)),
          ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName(
        "solde insuffisant → RETRAIT_SOLDE_INSUFFISANT avec data.solde ; total d'un solde nul aussi")
    void soldeInsuffisant() {
      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "46001", null)))
          .isInstanceOf(BadRequestException.class)
          .satisfies(
              e -> {
                CustomException ce = (CustomException) e;
                assertThat(ce.getCode()).isEqualTo(ErrorCode.RETRAIT_SOLDE_INSUFFISANT);
                assertThat(ce.getDetails()).containsEntry("solde", new BigDecimal("46000"));
              });

      dejaRetire(membreBinta, "42000");
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreBinta, NatureRetrait.TOTAL, null, null)),
          ErrorCode.RETRAIT_SOLDE_INSUFFISANT);
      verify(retraitRepository, never()).save(any());
    }

    @Test
    @DisplayName("partiel sans montant ou en centimes → 400 INVALID_PARAMETER")
    void montantInvalide() {
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, null, null)),
          ErrorCode.INVALID_PARAMETER);
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000.50", null)),
          ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("date de remise future ou avant le début de la tontine → 400 INVALID_PARAMETER")
    void dateRemise() {
      code(
          () ->
              service.enregistrer(
                  kc,
                  tontine.getId(),
                  saisie(membreAwa, NatureRetrait.PARTIEL, "1000", MAINTENANT.plusMinutes(1))),
          ErrorCode.INVALID_PARAMETER);
      code(
          () ->
              service.enregistrer(
                  kc,
                  tontine.getId(),
                  saisie(
                      membreAwa,
                      NatureRetrait.PARTIEL,
                      "1000",
                      LocalDate.of(2026, 11, 24).atTime(23, 59))),
          ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName(
        "B41 : reprise d'un cahier -> retrait daté depuis le début du cahier accepté, reprise"
            + " figée au premier retrait")
    void depuisLeCahier() throws Exception {
      tontine.setDateDebutReelle(LocalDate.of(2026, 11, 1));
      LocalDateTime pendantLeCahier = LocalDate.of(2026, 11, 10).atTime(9, 0);

      RetraitEpargneResponse r =
          service.enregistrer(
              kc,
              tontine.getId(),
              saisie(membreAwa, NatureRetrait.PARTIEL, "1000", pendantLeCahier));

      assertThat(r.getDateRemise()).isEqualTo(pendantLeCahier);
      verify(repriseService).figerSiOuverte(gestionnaire, tontine);
      code(
          () ->
              service.enregistrer(
                  kc,
                  tontine.getId(),
                  saisie(
                      membreAwa,
                      NatureRetrait.PARTIEL,
                      "1000",
                      LocalDate.of(2026, 10, 31).atTime(23, 59))),
          ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("règle INTERDIT → RETRAIT_INTERDIT ; règle absente = INTERDIT")
    void interdit() {
      tontine.setRegleRetrait(RegleRetrait.INTERDIT);
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null)),
          ErrorCode.RETRAIT_INTERDIT);
      tontine.setRegleRetrait(null);
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null)),
          ErrorCode.RETRAIT_INTERDIT);
    }

    @Test
    @DisplayName("rotative, brouillon, terminée ou déjà distribuée → RETRAIT_NON_DISPONIBLE")
    void nonDisponible() {
      RetraitRequests.Saisir req = saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null);
      tontine.setTontineType(TontineType.ROTATIVE);
      code(() -> service.enregistrer(kc, tontine.getId(), req), ErrorCode.RETRAIT_NON_DISPONIBLE);
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      tontine.setStatut(TontineStatut.TERMINEE);
      code(() -> service.enregistrer(kc, tontine.getId(), req), ErrorCode.RETRAIT_NON_DISPONIBLE);
      tontine.setStatut(TontineStatut.BROUILLON);
      code(() -> service.enregistrer(kc, tontine.getId(), req), ErrorCode.RETRAIT_NON_DISPONIBLE);
      tontine.setStatut(TontineStatut.SUSPENDUE);
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(true);
      code(() -> service.enregistrer(kc, tontine.getId(), req), ErrorCode.RETRAIT_NON_DISPONIBLE);
    }

    @Test
    @DisplayName("membre déjà soldé → MEMBRE_SOLDE ; membre SORTI accepté")
    void membre() throws Exception {
      membreAwa.setSoldeLe(MAINTENANT.minusDays(3));
      code(
          () ->
              service.enregistrer(
                  kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null)),
          ErrorCode.MEMBRE_SOLDE);

      membreBinta.setStatut(MembreStatut.SORTI);
      assertThat(
              service
                  .enregistrer(
                      kc, tontine.getId(), saisie(membreBinta, NatureRetrait.PARTIEL, "1000", null))
                  .getStatut())
          .isEqualTo(StatutRetrait.REMIS);
    }

    @Test
    @DisplayName("membre, tiers → 403 ; membre inconnu → 404")
    void droits() {
      RetraitRequests.Saisir req = saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null);
      assertThatThrownBy(() -> service.enregistrer(awa.getKeycloakId(), tontine.getId(), req))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> service.enregistrer(etranger.getKeycloakId(), tontine.getId(), req))
          .isInstanceOf(ForbiddenException.class);

      TontineMembre inconnu = Fixtures.membre(tontine, etranger, 3);
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
              inconnu.getId(), tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), saisie(inconnu, NatureRetrait.PARTIEL, "1000", null)))
          .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("SUPER_ADMIN peut saisir")
    void superAdmin() throws Exception {
      when(accessControl.estSuperAdmin(etranger)).thenReturn(true);

      assertThat(
              service
                  .enregistrer(
                      etranger.getKeycloakId(),
                      tontine.getId(),
                      saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null))
                  .getStatut())
          .isEqualTo(StatutRetrait.REMIS);
    }
  }

  @Nested
  @DisplayName("annulation")
  class Annulation {

    private RetraitRequests.Annuler motif() {
      RetraitRequests.Annuler a = new RetraitRequests.Annuler();
      a.setMotif("Saisi deux fois");
      return a;
    }

    @Test
    @DisplayName("retrait total annulé : ANNULE, motif, le membre n'est plus soldé, audit")
    void total() throws Exception {
      RetraitEpargne r = retrait(membreBinta, NatureRetrait.TOTAL, StatutRetrait.REMIS);
      membreBinta.setSoldeLe(MAINTENANT.minusDays(1));

      RetraitEpargneResponse reponse = service.annuler(kc, tontine.getId(), r.getId(), motif());

      assertThat(reponse.getStatut()).isEqualTo(StatutRetrait.ANNULE);
      assertThat(reponse.getMotifAnnulation()).isEqualTo("Saisi deux fois");
      assertThat(reponse.getDateAnnulation()).isEqualTo(MAINTENANT);
      assertThat(reponse.getSoldeRestant()).isEqualByComparingTo("42000");
      assertThat(r.getAnnulePar()).isEqualTo(gestionnaire);
      assertThat(membreBinta.getSoldeLe()).isNull();
      verify(membreRepository).save(membreBinta);
      verify(auditService)
          .log(gestionnaire, "retraits_epargne", r.getId(), "statut", "REMIS", "ANNULE");
    }

    @Test
    @DisplayName("partiel annulé : membre non soldé inchangé")
    void partiel() throws Exception {
      RetraitEpargne r = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);

      assertThat(service.annuler(kc, tontine.getId(), r.getId(), motif()).getStatut())
          .isEqualTo(StatutRetrait.ANNULE);
      verify(membreRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "déjà annulé, distribution faite, ou partiel d'un membre soldé → RETRAIT_NON_ANNULABLE")
    void nonAnnulable() {
      RetraitEpargne annule = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.ANNULE);
      code(
          () -> service.annuler(kc, tontine.getId(), annule.getId(), motif()),
          ErrorCode.RETRAIT_NON_ANNULABLE);

      RetraitEpargne partiel = retrait(membreBinta, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      membreBinta.setSoldeLe(MAINTENANT);
      code(
          () -> service.annuler(kc, tontine.getId(), partiel.getId(), motif()),
          ErrorCode.RETRAIT_NON_ANNULABLE);

      RetraitEpargne remis = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(true);
      code(
          () -> service.annuler(kc, tontine.getId(), remis.getId(), motif()),
          ErrorCode.RETRAIT_NON_ANNULABLE);
    }

    @Test
    @DisplayName("retrait inconnu → 404 ; membre → 403")
    void refus() {
      UUID inconnu = UUID.randomUUID();
      when(retraitRepository.findByIdAndTontine_IdAndDeletedAtIsNull(inconnu, tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.annuler(kc, tontine.getId(), inconnu, motif()))
          .isInstanceOf(NotFoundException.class);

      RetraitEpargne r = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      assertThatThrownBy(
              () -> service.annuler(awa.getKeycloakId(), tontine.getId(), r.getId(), motif()))
          .isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  @DisplayName("lecture")
  class Lecture {

    @Test
    @DisplayName("liste : gestionnaire et membre ; tiers → 403")
    @SuppressWarnings("unchecked")
    void liste() throws Exception {
      Pageable page = PageRequest.of(0, 20);
      RetraitEpargne r = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      when(retraitRepository.findAll(any(Specification.class), eq(page)))
          .thenReturn(new PageImpl<>(List.of(r)));

      assertThat(
              service
                  .lister(kc, tontine.getId(), null, List.of(StatutRetrait.REMIS), page)
                  .getContent())
          .extracting(RetraitEpargneResponse::getId)
          .containsExactly(r.getId());
      assertThat(
              service
                  .lister(awa.getKeycloakId(), tontine.getId(), membreBinta.getId(), null, page)
                  .getContent())
          .hasSize(1);
      assertThatThrownBy(
              () -> service.lister(etranger.getKeycloakId(), tontine.getId(), null, null, page))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("solde : gestionnaire et membre concerné ; autre membre → 403")
    void solde() throws Exception {
      dejaRetire(membreAwa, "10000");
      membreAwa.setSoldeLe(null);

      SoldeEpargneResponse s =
          service.solde(awa.getKeycloakId(), tontine.getId(), membreAwa.getId());

      assertThat(s.getMembreId()).isEqualTo(membreAwa.getId());
      assertThat(s.getTotalCotiseValide()).isEqualByComparingTo("46000");
      assertThat(s.getTotalRetire()).isEqualByComparingTo("10000");
      assertThat(s.getSolde()).isEqualByComparingTo("36000");
      assertThat(service.solde(kc, tontine.getId(), membreAwa.getId()).getSolde())
          .isEqualByComparingTo("36000");
      assertThatThrownBy(
              () -> service.solde(binta.getKeycloakId(), tontine.getId(), membreAwa.getId()))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName(
        "export CSV : en-tête et une ligne par retrait ; membre → 403 ; format inconnu → 400")
    void export() throws Exception {
      RetraitEpargne r = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      r.setReferenceTransaction("WAVE-123");
      when(retraitRepository.findPourExport(tontine.getId())).thenReturn(List.of(r));

      FichierExport f = service.exporter(kc, tontine.getId(), "csv");

      String csv = new String(f.contenu(), StandardCharsets.UTF_8);
      assertThat(f.nomFichier()).isEqualTo("retraits-" + tontine.getId() + ".csv");
      assertThat(csv).contains("Date de remise;Membre;Téléphone;Nature;Montant (FCFA)");
      assertThat(csv)
          .contains("Awa Test")
          .contains("PARTIEL")
          .contains("10000")
          .contains("WAVE-123");
      assertThatThrownBy(() -> service.exporter(awa.getKeycloakId(), tontine.getId(), "csv"))
          .isInstanceOf(ForbiddenException.class);
      code(() -> service.exporter(kc, tontine.getId(), "pdf"), ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("ArgumentCaptor : le retrait enregistré porte le gestionnaire et la méthode")
    void champsEnregistres() throws Exception {
      service.enregistrer(
          kc, tontine.getId(), saisie(membreAwa, NatureRetrait.PARTIEL, "1000", null));

      ArgumentCaptor<RetraitEpargne> captor = ArgumentCaptor.forClass(RetraitEpargne.class);
      verify(retraitRepository).save(captor.capture());
      assertThat(captor.getValue().getTraitePar()).isEqualTo(gestionnaire);
      assertThat(captor.getValue().getMethodePaiement()).isEqualTo("CASH");
      assertThat(captor.getValue().getMotif()).isEqualTo("Frais de scolarité");
      assertThat(captor.getValue().getTontine()).isEqualTo(tontine);
    }
  }

  @Nested
  @DisplayName("B42 lot 2 : demande du membre")
  class DemandeDuMembre {

    private RetraitRequests.Demander demande(NatureRetrait nature, String montant) {
      RetraitRequests.Demander r = new RetraitRequests.Demander();
      r.setNature(nature);
      r.setMontant(montant != null ? new BigDecimal(montant) : null);
      r.setMotif(" Frais médicaux ");
      return r;
    }

    private RetraitRequests.Remettre remise() {
      RetraitRequests.Remettre r = new RetraitRequests.Remettre();
      r.setMethodePaiement(" WAVE ");
      r.setReferenceTransaction("TX-42");
      return r;
    }

    private RetraitRequests.Refuser refus(String motif) {
      RetraitRequests.Refuser r = new RetraitRequests.Refuser();
      r.setMotif(motif);
      return r;
    }

    @Test
    @DisplayName(
        "demande partielle : DEMANDE, origine MEMBRE, solde inchangé, gestionnaire prévenu (in-app"
            + " + WhatsApp)")
    void demandePartielle() throws Exception {
      RetraitEpargneResponse r =
          service.demander(
              awa.getKeycloakId(), tontine.getId(), demande(NatureRetrait.PARTIEL, "10000"));

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.DEMANDE);
      assertThat(r.getOrigine()).isEqualTo(OrigineRetrait.MEMBRE);
      assertThat(r.getMontant()).isEqualByComparingTo("10000");
      assertThat(r.getMotif()).isEqualTo("Frais médicaux");
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("46000");
      assertThat(r.getDateRemise()).isNull();
      verify(notificationService)
          .notify(
              eq(gestionnaire.getId()),
              eq(NotificationType.RETRAIT_DEMANDE),
              anyString(),
              contains("demande un retrait de 10000 FCFA"),
              eq(tontine.getId()),
              any());
      verify(whatsappService)
          .sendRetraitDemande(gestionnaire.getPhone(), "Awa Test", tontine.getNom(), "10000");
      verify(repriseService, never()).figerSiOuverte(any(), any());
    }

    @Test
    @DisplayName(
        "demande totale : tout le solde (montant ignoré) ; gestionnaire sans téléphone : pas de"
            + " WhatsApp")
    void demandeTotale() throws Exception {
      gestionnaire.setPhone(null);

      RetraitEpargneResponse r =
          service.demander(awa.getKeycloakId(), tontine.getId(), demande(NatureRetrait.TOTAL, "1"));

      assertThat(r.getMontant()).isEqualByComparingTo("46000");
      verify(whatsappService, never()).sendRetraitDemande(any(), any(), any(), any());
    }

    @Test
    @DisplayName(
        "refus de la demande : déjà une en cours (RETRAIT_DEJA_EN_COURS), tiers (403), membre"
            + " soldé, montant au-delà du solde, règle INTERDIT")
    void demandeRefusee() {
      when(retraitRepository.existsByMembre_IdAndStatutInAndDeletedAtIsNull(
              eq(membreAwa.getId()), any()))
          .thenReturn(true);
      code(
          () ->
              service.demander(
                  awa.getKeycloakId(), tontine.getId(), demande(NatureRetrait.PARTIEL, "1000")),
          ErrorCode.RETRAIT_DEJA_EN_COURS);

      lenient()
          .when(
              membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                  tontine.getId(), etranger.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.demander(
                      etranger.getKeycloakId(),
                      tontine.getId(),
                      demande(NatureRetrait.PARTIEL, "1000")))
          .isInstanceOf(ForbiddenException.class);

      code(
          () ->
              service.demander(
                  binta.getKeycloakId(), tontine.getId(), demande(NatureRetrait.PARTIEL, "50000")),
          ErrorCode.RETRAIT_SOLDE_INSUFFISANT);
      membreBinta.setSoldeLe(MAINTENANT);
      code(
          () ->
              service.demander(
                  binta.getKeycloakId(), tontine.getId(), demande(NatureRetrait.PARTIEL, "1000")),
          ErrorCode.MEMBRE_SOLDE);
      tontine.setRegleRetrait(RegleRetrait.INTERDIT);
      code(
          () ->
              service.demander(
                  binta.getKeycloakId(), tontine.getId(), demande(NatureRetrait.PARTIEL, "1000")),
          ErrorCode.RETRAIT_INTERDIT);
      verify(retraitRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "accepter : DEMANDE -> ACCEPTE, membre prévenu (in-app + WhatsApp) ; une seconde fois ->"
            + " RETRAIT_NOT_PENDING ; membre -> 403")
    void accepter() throws Exception {
      RetraitEpargne d = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.DEMANDE);

      RetraitEpargneResponse r = service.accepter(kc, tontine.getId(), d.getId());

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.ACCEPTE);
      assertThat(r.getDateAcceptation()).isEqualTo(MAINTENANT);
      assertThat(d.getAcceptePar()).isEqualTo(gestionnaire);
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.RETRAIT_ACCEPTE),
              anyString(),
              anyString(),
              eq(tontine.getId()),
              any());
      verify(whatsappService).sendRetraitAccepte(awa.getPhone(), tontine.getNom(), "10000");
      code(() -> service.accepter(kc, tontine.getId(), d.getId()), ErrorCode.RETRAIT_NOT_PENDING);
      assertThatThrownBy(() -> service.accepter(awa.getKeycloakId(), tontine.getId(), d.getId()))
          .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName(
        "remettre une demande acceptée : REMIS, moyen et référence, reprise figée, membre prévenu")
    void remettrePartiel() throws Exception {
      RetraitEpargne d = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.ACCEPTE);
      d.setDateRemise(null);

      RetraitEpargneResponse r = service.remettreDemande(kc, tontine.getId(), d.getId(), remise());

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.REMIS);
      assertThat(r.getMethodePaiement()).isEqualTo("WAVE");
      assertThat(r.getReferenceTransaction()).isEqualTo("TX-42");
      assertThat(r.getDateRemise()).isEqualTo(MAINTENANT);
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("36000");
      assertThat(d.getTraitePar()).isEqualTo(gestionnaire);
      verify(repriseService).figerSiOuverte(gestionnaire, tontine);
      verify(auditService)
          .log(gestionnaire, "retraits_epargne", d.getId(), "statut", "ACCEPTE", "REMIS");
      verify(whatsappService).sendRetraitRemis(awa.getPhone(), tontine.getNom(), "10000", "36000");
      code(
          () -> service.remettreDemande(kc, tontine.getId(), d.getId(), remise()),
          ErrorCode.RETRAIT_NOT_PENDING);
    }

    @Test
    @DisplayName(
        "remettre une demande totale directement : tout le solde à la remise, membre soldé")
    void remettreTotal() throws Exception {
      RetraitEpargne d = retrait(membreBinta, NatureRetrait.TOTAL, StatutRetrait.DEMANDE);

      RetraitEpargneResponse r = service.remettreDemande(kc, tontine.getId(), d.getId(), remise());

      assertThat(r.getMontant()).isEqualByComparingTo("42000");
      assertThat(r.getSoldeRestant()).isEqualByComparingTo("0");
      assertThat(membreBinta.getSoldeLe()).isEqualTo(MAINTENANT);
    }

    @Test
    @DisplayName(
        "refuser : REFUSE avec motif transmis (in-app + WhatsApp, échec WhatsApp sans effet) ;"
            + " retrait remis -> RETRAIT_NOT_PENDING")
    void refuser() throws Exception {
      RetraitEpargne d = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.DEMANDE);
      doThrow(new IllegalStateException("WA indisponible"))
          .when(whatsappService)
          .sendRetraitRefuse(any(), any(), any(), any());

      RetraitEpargneResponse r =
          service.refuser(kc, tontine.getId(), d.getId(), refus(" Caisse vide "));

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.REFUSE);
      assertThat(r.getMotifRefus()).isEqualTo("Caisse vide");
      assertThat(r.getDateRefus()).isEqualTo(MAINTENANT);
      assertThat(d.getRefusePar()).isEqualTo(gestionnaire);
      verify(notificationService)
          .notify(
              eq(awa.getId()),
              eq(NotificationType.RETRAIT_REFUSE),
              anyString(),
              contains("Motif : Caisse vide"),
              eq(tontine.getId()),
              any());

      RetraitEpargne remis = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      code(
          () -> service.refuser(kc, tontine.getId(), remis.getId(), refus("non")),
          ErrorCode.RETRAIT_NOT_PENDING);
    }

    @Test
    @DisplayName(
        "annuler : le membre retire sa demande sans motif ; pas celle d'un autre ni un retrait"
            + " remis (403) ; gestionnaire sur un retrait remis sans motif -> VALIDATION_ERROR")
    void annuler() throws Exception {
      RetraitEpargne d = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.DEMANDE);

      RetraitEpargneResponse r =
          service.annuler(
              awa.getKeycloakId(), tontine.getId(), d.getId(), new RetraitRequests.Annuler());

      assertThat(r.getStatut()).isEqualTo(StatutRetrait.ANNULE);
      assertThat(r.getMotifAnnulation()).isNull();
      assertThat(d.getAnnulePar()).isEqualTo(awa);

      RetraitEpargne autre = retrait(membreBinta, NatureRetrait.PARTIEL, StatutRetrait.DEMANDE);
      assertThatThrownBy(
              () ->
                  service.annuler(
                      awa.getKeycloakId(),
                      tontine.getId(),
                      autre.getId(),
                      new RetraitRequests.Annuler()))
          .isInstanceOf(ForbiddenException.class);
      RetraitEpargne remis = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.REMIS);
      assertThatThrownBy(
              () ->
                  service.annuler(
                      awa.getKeycloakId(),
                      tontine.getId(),
                      remis.getId(),
                      new RetraitRequests.Annuler()))
          .isInstanceOf(ForbiddenException.class);
      code(
          () -> service.annuler(kc, tontine.getId(), remis.getId(), new RetraitRequests.Annuler()),
          ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("distribution finale : demandes en cours annulées d'office, auditées")
    void annulationALaDistribution() {
      RetraitEpargne d = retrait(membreAwa, NatureRetrait.PARTIEL, StatutRetrait.DEMANDE);
      RetraitEpargne a = retrait(membreBinta, NatureRetrait.TOTAL, StatutRetrait.ACCEPTE);
      when(retraitRepository.findByTontine_IdAndStatutInAndDeletedAtIsNull(
              eq(tontine.getId()), any()))
          .thenReturn(List.of(d, a));

      service.annulerDemandesEnCours(gestionnaire, tontine);

      assertThat(d.getStatut()).isEqualTo(StatutRetrait.ANNULE);
      assertThat(a.getStatut()).isEqualTo(StatutRetrait.ANNULE);
      assertThat(a.getMotifAnnulation()).isEqualTo("Distribution finale faite");
      verify(auditService)
          .log(gestionnaire, "retraits_epargne", a.getId(), "statut", "ACCEPTE", "ANNULE");
    }
  }
}
