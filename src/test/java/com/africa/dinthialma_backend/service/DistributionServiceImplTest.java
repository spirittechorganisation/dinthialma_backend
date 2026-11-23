package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.member.codeList.MembreStatut;
import com.africa.dinthialma_backend.member.entity.TontineMembre;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.support.Fixtures;
import com.africa.dinthialma_backend.tontine.codeList.CycleStatut;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.DistributionFinaleResponse;
import com.africa.dinthialma_backend.tontine.entity.CycleTontine;
import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.mapper.DistributionMapper;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.impl.DistributionServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistributionServiceImpl (B51 : distribution finale en lecture)")
class DistributionServiceImplTest {

  @Mock private AccessControl accessControl;
  @Mock private TontineRepository tontineRepository;
  @Mock private TontineMembreRepository membreRepository;
  @Mock private DistributionEvenementielleRepository distributionRepository;
  @Spy private DistributionMapper distributionMapper = Mappers.getMapper(DistributionMapper.class);
  @InjectMocks private DistributionServiceImpl service;

  private User gestionnaire;
  private User awa;
  private User binta;
  private User etranger;
  private Tontine tontine;
  private TontineMembre membreAwa;
  private TontineMembre membreBinta;
  private CycleTontine dernier;

  @BeforeEach
  void setUp() throws CustomException {
    gestionnaire = Fixtures.user("Gestionnaire");
    awa = Fixtures.user("Awa");
    binta = Fixtures.user("Binta");
    etranger = Fixtures.user("Étranger");
    tontine = Fixtures.tontine(gestionnaire);
    tontine.setTontineType(TontineType.EVENEMENTIELLE);
    membreAwa = Fixtures.membre(tontine, awa, 1);
    membreBinta = Fixtures.membre(tontine, binta, 2);
    membreBinta.setStatut(MembreStatut.SORTI);
    dernier = Fixtures.cycle(tontine, 3, CycleStatut.TERMINE);
    for (User u : List.of(gestionnaire, awa, binta, etranger)) {
      lenient().when(accessControl.utilisateur(u.getKeycloakId())).thenReturn(u);
    }
    lenient()
        .when(tontineRepository.findByIdAndDeletedAtIsNull(tontine.getId()))
        .thenReturn(Optional.of(tontine));
    lenient()
        .when(
            membreRepository.findByTontine_IdAndUser_IdAndDeletedAtIsNull(
                tontine.getId(), awa.getId()))
        .thenReturn(Optional.of(membreAwa));
    lenient()
        .when(distributionRepository.findByTontineAvecMembres(tontine.getId()))
        .thenReturn(
            List.of(
                ligne(membreAwa, "46000", "4600", "41400"),
                ligne(membreBinta, "42000", "4200", "37800")));
  }

  private DistributionEvenementielle ligne(
      TontineMembre membre, String cotise, String commission, String net) {
    return DistributionEvenementielle.builder()
        .id(UUID.randomUUID())
        .tontine(tontine)
        .cycle(dernier)
        .membre(membre)
        .montantCotise(new BigDecimal(cotise))
        .montantCommission(new BigDecimal(commission))
        .montantNet(new BigDecimal(net))
        .createdAt(LocalDateTime.of(2026, 12, 31, 23, 59))
        .build();
  }

  @Test
  @DisplayName("gestionnaire : toutes les lignes, totaux, date et statut des membres")
  void gestionnaire() throws Exception {
    DistributionFinaleResponse r = service.lire(gestionnaire.getKeycloakId(), tontine.getId());

    assertThat(r.getTontineId()).isEqualTo(tontine.getId());
    assertThat(r.getCycleId()).isEqualTo(dernier.getId());
    assertThat(r.getDateDistribution()).isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59));
    assertThat(r.getNombreMembres()).isEqualTo(2);
    assertThat(r.getTotalCotise()).isEqualByComparingTo("88000");
    assertThat(r.getTotalCommission()).isEqualByComparingTo("8800");
    assertThat(r.getTotalNet()).isEqualByComparingTo("79200");
    assertThat(r.getLignes()).hasSize(2);
    DistributionFinaleResponse.Ligne ligneBinta =
        r.getLignes().stream()
            .filter(l -> l.getMembreId().equals(membreBinta.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(ligneBinta.getStatutMembre()).isEqualTo(MembreStatut.SORTI);
    assertThat(ligneBinta.getFirstName()).isEqualTo("Binta");
    assertThat(ligneBinta.getMontantNet()).isEqualByComparingTo("37800");
  }

  @Test
  @DisplayName("SUPER_ADMIN : toutes les lignes")
  void superAdmin() throws Exception {
    when(accessControl.estSuperAdmin(etranger)).thenReturn(true);

    assertThat(service.lire(etranger.getKeycloakId(), tontine.getId()).getLignes()).hasSize(2);
  }

  @Test
  @DisplayName("membre : sa ligne seulement, mais les totaux de toute la tontine")
  void membre() throws Exception {
    DistributionFinaleResponse r = service.lire(awa.getKeycloakId(), tontine.getId());

    assertThat(r.getLignes())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.getMembreId()).isEqualTo(membreAwa.getId());
              assertThat(l.getMontantNet()).isEqualByComparingTo("41400");
            });
    assertThat(r.getNombreMembres()).isEqualTo(2);
    assertThat(r.getTotalCotise()).isEqualByComparingTo("88000");
  }

  @Test
  @DisplayName("non-membre → 403")
  void nonMembre() {
    assertThatThrownBy(() -> service.lire(etranger.getKeycloakId(), tontine.getId()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("pas encore distribuée → 404 DISTRIBUTION_NON_FAITE")
  void pasDistribuee() {
    when(distributionRepository.findByTontineAvecMembres(tontine.getId())).thenReturn(List.of());

    assertThatThrownBy(() -> service.lire(gestionnaire.getKeycloakId(), tontine.getId()))
        .isInstanceOf(NotFoundException.class)
        .satisfies(
            e ->
                assertThat(((CustomException) e).getCode())
                    .isEqualTo(ErrorCode.DISTRIBUTION_NON_FAITE));
  }

  @Test
  @DisplayName("tontine rotative → 400 ; tontine inconnue → 404")
  void refus() {
    tontine.setTontineType(TontineType.ROTATIVE);
    assertThatThrownBy(() -> service.lire(gestionnaire.getKeycloakId(), tontine.getId()))
        .isInstanceOf(BadRequestException.class);

    UUID inconnue = UUID.randomUUID();
    when(tontineRepository.findByIdAndDeletedAtIsNull(inconnue)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.lire(gestionnaire.getKeycloakId(), inconnue))
        .isInstanceOf(NotFoundException.class);
  }
}
