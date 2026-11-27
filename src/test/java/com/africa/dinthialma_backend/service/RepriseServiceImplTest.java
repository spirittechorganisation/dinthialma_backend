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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.audit.AuditService;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.contribution.codeList.CotisationStatut;
import com.africa.dinthialma_backend.contribution.codeList.OrigineCotisation;
import com.africa.dinthialma_backend.contribution.codeList.Ponctualite;
import com.africa.dinthialma_backend.contribution.codeList.StatutReprise;
import com.africa.dinthialma_backend.contribution.dto.RepriseRequest;
import com.africa.dinthialma_backend.contribution.dto.RepriseResponse;
import com.africa.dinthialma_backend.contribution.entity.Cotisation;
import com.africa.dinthialma_backend.contribution.mapper.CotisationMapper;
import com.africa.dinthialma_backend.contribution.repository.CotisationRepository;
import com.africa.dinthialma_backend.contribution.service.impl.RepriseServiceImpl;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.notification.codeList.NotificationType;
import com.africa.dinthialma_backend.notification.service.WhatsappService;
import com.africa.dinthialma_backend.notification.service.interfaces.NotificationService;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.repository.CycleTontineRepository;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RepriseServiceImpl : soldes de départ d'une tontine déjà commencée (B41)")
class RepriseServiceImplTest {

  private static final LocalDateTime MAINTENANT = LocalDateTime.of(2026, 11, 30, 10, 0);
  private static final LocalDate DEBUT = LocalDate.of(2026, 11, 25);
  private static final LocalDate CAHIER = LocalDate.of(2026, 11, 1);

  @Mock private TontineRepository tontineRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private CycleTontineRepository cycleRepository;
  @Mock private CotisationRepository cotisationRepository;
  @Mock private DistributionEvenementielleRepository distributionRepository;
  @Spy private CotisationMapper cotisationMapper = Mappers.getMapper(CotisationMapper.class);
  @Mock private AccessControl accessControl;
  @Mock private AuditService auditService;
  @Mock private NotificationService notificationService;
  @Mock private WhatsappService whatsappService;

  @Spy private Clock horloge = Clock.fixed(MAINTENANT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  @InjectMocks private RepriseServiceImpl service;

  private User gestionnaire;
  private User awa;
  private User etranger;
  private Tontine tontine;
  private TontineMembre membreAwa;
  private TontineMembre membreBinta;
  private String kc;

  /** Cotisations enregistrées (base simulée). */
  private final List<Cotisation> base = new ArrayList<>();

  private CycleTontine tourZero;

  @BeforeEach
  void setUp() throws CustomException {
    gestionnaire = Fixtures.user("Gestionnaire");
    awa = Fixtures.user("Awa");
    etranger = Fixtures.user("Étranger");
    kc = gestionnaire.getKeycloakId();
    tontine = Fixtures.tontine(gestionnaire);
    tontine.setTontineType(TontineType.EVENEMENTIELLE);
    tontine.setDateDebut(DEBUT);
    membreAwa = Fixtures.membre(tontine, awa, 1);
    membreBinta = Fixtures.membre(tontine, Fixtures.user("Binta"), 2);
    for (User u : List.of(gestionnaire, awa, etranger)) {
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
    }
    lenient()
        .when(
            membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                tontine.getId(), awa.getId()))
        .thenReturn(Optional.of(membreAwa));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                tontine.getId(), etranger.getId()))
        .thenReturn(Optional.empty());
    lenient()
        .when(cotisationRepository.save(any()))
        .thenAnswer(
            i -> {
              Cotisation c = i.getArgument(0);
              if (c.getId() == null) {
                c.setId(UUID.randomUUID());
                base.add(c);
              }
              return c;
            });
    lenient()
        .when(
            cotisationRepository.findByTontine_IdAndOrigineAndDeletedAtIsNull(
                tontine.getId(), OrigineCotisation.REPRISE))
        .thenAnswer(
            i ->
                base.stream()
                    .filter(
                        c ->
                            c.getOrigine() == OrigineCotisation.REPRISE && c.getDeletedAt() == null)
                    .toList());
    lenient()
        .when(cycleRepository.save(any()))
        .thenAnswer(
            i -> {
              CycleTontine c = i.getArgument(0);
              if (c.getId() == null) {
                c.setId(UUID.randomUUID());
                tourZero = c;
              }
              return c;
            });
    lenient()
        .when(cycleRepository.findByTontine_IdAndNumeroCycleAndDeletedAtIsNull(tontine.getId(), 0))
        .thenAnswer(i -> Optional.ofNullable(tourZero));
  }

  private static RepriseRequest.Solde solde(TontineMembre m, String montant, String note) {
    RepriseRequest.Solde s = new RepriseRequest.Solde();
    s.setMembreId(m.getId());
    s.setMontant(new BigDecimal(montant));
    s.setNote(note);
    return s;
  }

  private static RepriseRequest requete(LocalDate cahier, RepriseRequest.Solde... soldes) {
    RepriseRequest r = new RepriseRequest();
    r.setDateDebutReelle(cahier);
    r.setSoldes(new ArrayList<>(List.of(soldes)));
    return r;
  }

  private static ErrorCode code(Throwable e) {
    return ((CustomException) e).getCode();
  }

  @Nested
  @DisplayName("saisie des soldes de départ")
  class Saisie {

    @Test
    @DisplayName(
        "première saisie : tour de reprise n° 0 TERMINE sur la période du cahier, cotisations"
            + " VALIDE origine REPRISE, auditées, aucune notification")
    void premiereSaisie() throws Exception {
      RepriseResponse r =
          service.enregistrer(
              kc,
              tontine.getId(),
              requete(
                  CAHIER,
                  solde(membreAwa, "42000", " Cahier p. 3 "),
                  solde(membreBinta, "36000", " ")));

      assertThat(r.getStatut()).isEqualTo(StatutReprise.OUVERTE);
      assertThat(r.getDateDebutReelle()).isEqualTo(CAHIER);
      assertThat(r.getTotal()).isEqualByComparingTo("78000");
      assertThat(r.getNombreMembres()).isEqualTo(2);
      assertThat(r.getCycleId()).isEqualTo(tourZero.getId());
      assertThat(r.getSoldes())
          .extracting(RepriseResponse.SoldeDepart::getNote)
          .containsExactly("Cahier p. 3", null);
      assertThat(tourZero.getNumeroCycle()).isZero();
      assertThat(tourZero.getStatut()).isEqualTo(CycleStatut.TERMINE);
      assertThat(tourZero.getDateDebut()).isEqualTo(CAHIER);
      assertThat(tourZero.getDateFin()).isEqualTo(DEBUT.minusDays(1));
      assertThat(tourZero.getMontantNet()).isEqualByComparingTo("78000");
      assertThat(tontine.getDateDebutReelle()).isEqualTo(CAHIER);

      Cotisation c = base.get(0);
      assertThat(c.getStatut()).isEqualTo(CotisationStatut.VALIDE);
      assertThat(c.getOrigine()).isEqualTo(OrigineCotisation.REPRISE);
      assertThat(c.getEcheance()).isNull();
      assertThat(c.getValidePar()).isEqualTo(gestionnaire);
      assertThat(Ponctualite.de(c)).isEqualTo(Ponctualite.REPRISE);
      verify(auditService, times(2)).logCreate(eq(gestionnaire), eq("cotisations"), any());
      verify(auditService)
          .log(gestionnaire, "tontines", tontine.getId(), "date_debut_reelle", null, "2026-11-01");
      verify(notificationService, never())
          .notify(any(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName(
        "nouvelle saisie : soldes modifiés (audités), retirés si absents, inchangés sans trace ;"
            + " même date = pas de nouvel audit")
    void remplacement() throws Exception {
      service.enregistrer(
          kc,
          tontine.getId(),
          requete(CAHIER, solde(membreAwa, "42000", "p. 3"), solde(membreBinta, "36000", null)));
      Cotisation awaAvant = base.get(0);
      Cotisation bintaAvant = base.get(1);

      RepriseResponse r =
          service.enregistrer(
              kc, tontine.getId(), requete(CAHIER, solde(membreAwa, "44000", "p. 4")));

      assertThat(r.getTotal()).isEqualByComparingTo("44000");
      assertThat(r.getNombreMembres()).isEqualTo(1);
      assertThat(awaAvant.getMontant()).isEqualByComparingTo("44000");
      assertThat(bintaAvant.getDeletedAt()).isEqualTo(MAINTENANT);
      verify(auditService)
          .log(gestionnaire, "cotisations", awaAvant.getId(), "montant", "42000", "44000");
      verify(auditService)
          .log(gestionnaire, "cotisations", awaAvant.getId(), "note", "p. 3", "p. 4");
      verify(auditService).logDelete(gestionnaire, "cotisations", bintaAvant.getId());
      verify(auditService, times(1))
          .log(eq(gestionnaire), eq("tontines"), any(), eq("date_debut_reelle"), any(), any());

      // Rien ne change : aucune écriture de cotisation supplémentaire
      service.enregistrer(kc, tontine.getId(), requete(CAHIER, solde(membreAwa, "44000", "p. 4")));
      verify(auditService, times(1))
          .log(
              eq(gestionnaire),
              eq("cotisations"),
              eq(awaAvant.getId()),
              eq("montant"),
              any(),
              any());

      // Nouvelle date du cahier : tour de reprise suivi, audit de l'ancienne valeur
      service.enregistrer(
          kc, tontine.getId(), requete(CAHIER.minusDays(10), solde(membreAwa, "44000", "p. 4")));
      assertThat(tourZero.getDateDebut()).isEqualTo(CAHIER.minusDays(10));
      verify(auditService)
          .log(
              gestionnaire,
              "tontines",
              tontine.getId(),
              "date_debut_reelle",
              "2026-11-01",
              "2026-10-22");
    }

    @Test
    @DisplayName(
        "refus : rotative ou terminée (REPRISE_NON_AUTORISEE), figée ou distribuée"
            + " (REPRISE_FIGEE), date du cahier pas antérieure (REPRISE_DATE_INVALIDE)")
    void refusRegles() {
      RepriseRequest ok = requete(CAHIER, solde(membreAwa, "42000", null));

      tontine.setTontineType(TontineType.ROTATIVE);
      assertThatThrownBy(() -> service.enregistrer(kc, tontine.getId(), ok))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_NON_AUTORISEE));
      tontine.setTontineType(TontineType.EVENEMENTIELLE);
      tontine.setStatut(TontineStatut.TERMINEE);
      assertThatThrownBy(() -> service.enregistrer(kc, tontine.getId(), ok))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_NON_AUTORISEE));
      tontine.setStatut(TontineStatut.ACTIVE);

      tontine.setRepriseFigeeLe(MAINTENANT);
      assertThatThrownBy(() -> service.enregistrer(kc, tontine.getId(), ok))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_FIGEE));
      tontine.setRepriseFigeeLe(null);
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(true, false, false);
      assertThatThrownBy(() -> service.enregistrer(kc, tontine.getId(), ok))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_FIGEE));

      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), requete(DEBUT, solde(membreAwa, "42000", null))))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_DATE_INVALIDE));
      assertThatThrownBy(() -> service.enregistrer(etranger.getKeycloakId(), tontine.getId(), ok))
          .isInstanceOf(ForbiddenException.class);
      verify(cotisationRepository, never()).save(any());
    }

    @Test
    @DisplayName("refus : membre en double, montant non entier, membre inconnu")
    void refusSoldes() {
      when(distributionRepository.existsByTontine_IdAndDeletedAtIsNull(tontine.getId()))
          .thenReturn(false);
      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc,
                      tontine.getId(),
                      requete(
                          CAHIER, solde(membreAwa, "1000", null), solde(membreAwa, "2000", null))))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_PARAMETER));
      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), requete(CAHIER, solde(membreAwa, "1000.50", null))))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.INVALID_PARAMETER));
      TontineMembre inconnu = Fixtures.membre(tontine, Fixtures.user("Inconnu"), 3);
      when(membreRepository.findByIdAndTontine_IdAndDeletedAtIsNull(
              inconnu.getId(), tontine.getId()))
          .thenReturn(Optional.empty());
      assertThatThrownBy(
              () ->
                  service.enregistrer(
                      kc, tontine.getId(), requete(CAHIER, solde(inconnu, "1000", null))))
          .isInstanceOf(NotFoundException.class);
      verify(cotisationRepository, never()).save(any());
    }
  }

  @Nested
  @DisplayName("gel et lecture")
  class GelEtLecture {

    @Test
    @DisplayName(
        "figer : reprise figée et auditée, chaque membre prévenu (in-app + WhatsApp) une seule"
            + " fois ; un WhatsApp en échec ne bloque pas")
    void figer() throws Exception {
      service.enregistrer(
          kc,
          tontine.getId(),
          requete(CAHIER, solde(membreAwa, "42000", null), solde(membreBinta, "36000", null)));
      membreBinta.getUser().setPhone(null);
      doThrow(new IllegalStateException("WA indisponible"))
          .when(whatsappService)
          .sendSoldeDepart(anyString(), anyString(), anyString(), anyString());

      RepriseResponse r = service.figer(kc, tontine.getId());

      assertThat(r.getStatut()).isEqualTo(StatutReprise.FIGEE);
      assertThat(r.getFigeeLe()).isEqualTo(MAINTENANT);
      verify(notificationService, times(2))
          .notify(
              any(),
              eq(NotificationType.SOLDE_DEPART),
              anyString(),
              contains("depuis le 01/11/2026"),
              eq(tontine.getId()),
              any());
      verify(whatsappService)
          .sendSoldeDepart(awa.getPhone(), tontine.getNom(), "42000", "01/11/2026");
      verify(auditService)
          .log(
              gestionnaire,
              "tontines",
              tontine.getId(),
              "reprise_figee_le",
              null,
              MAINTENANT.toString());

      // Déjà figée : sans effet, pas de nouveau message
      service.figer(kc, tontine.getId());
      service.figerSiOuverte(gestionnaire, tontine);
      verify(notificationService, times(2))
          .notify(any(), eq(NotificationType.SOLDE_DEPART), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("figer sans reprise : 400 ; gel automatique sans reprise : sans effet")
    void sansReprise() {
      assertThatThrownBy(() -> service.figer(kc, tontine.getId()))
          .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.REPRISE_NON_AUTORISEE));
      assertThatThrownBy(() -> service.figer(etranger.getKeycloakId(), tontine.getId()))
          .isInstanceOf(ForbiddenException.class);
      service.figerSiOuverte(gestionnaire, tontine);
      assertThat(tontine.getRepriseFigeeLe()).isNull();
      verify(tontineRepository, never()).save(any());
    }

    @Test
    @DisplayName(
        "lecture : gestionnaire = tous les soldes ; membre = le sien et le total ; tiers 403 ;"
            + " sans reprise = AUCUNE")
    void lecture() throws Exception {
      RepriseResponse vide = service.lire(kc, tontine.getId());
      assertThat(vide.getStatut()).isEqualTo(StatutReprise.AUCUNE);
      assertThat(vide.getCycleId()).isNull();
      assertThat(vide.getTotal()).isEqualByComparingTo("0");

      service.enregistrer(
          kc,
          tontine.getId(),
          requete(CAHIER, solde(membreAwa, "42000", null), solde(membreBinta, "36000", null)));

      assertThat(service.lire(kc, tontine.getId()).getSoldes()).hasSize(2);
      RepriseResponse membre = service.lire(awa.getKeycloakId(), tontine.getId());
      assertThat(membre.getSoldes())
          .singleElement()
          .satisfies(
              s -> {
                assertThat(s.getMembreId()).isEqualTo(membreAwa.getId());
                assertThat(s.getMontant()).isEqualByComparingTo("42000");
                assertThat(s.getPhone()).isEqualTo(awa.getPhone());
              });
      assertThat(membre.getTotal()).isEqualByComparingTo("78000");
      assertThatThrownBy(() -> service.lire(etranger.getKeycloakId(), tontine.getId()))
          .isInstanceOf(ForbiddenException.class);
    }
  }
}
