package com.africa.dinthialma_backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.africa.dinthialma_backend.auth.entity.EnvoiOtp;
import com.africa.dinthialma_backend.auth.entity.OtpVerification;
import com.africa.dinthialma_backend.auth.entity.PhoneChangeRequestEntity;
import com.africa.dinthialma_backend.auth.repository.EnvoiOtpRepository;
import com.africa.dinthialma_backend.auth.repository.OtpVerificationRepository;
import com.africa.dinthialma_backend.auth.repository.PhoneChangeRequestRepository;
import com.africa.dinthialma_backend.auth.service.impl.OtpGardeServiceImpl;
import com.africa.dinthialma_backend.auth.service.interfaces.OtpGardeService;
import com.africa.dinthialma_backend.common.constants.Constants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpGardeService : 3 envois par heure et par numéro, 5 essais par code (SE07)")
class OtpGardeServiceImplTest {

  private static final LocalDateTime MAINTENANT = LocalDateTime.of(2026, 11, 24, 10, 0);
  private static final String TEL = "221770000001";

  @Mock private EnvoiOtpRepository envoiRepository;
  @Mock private OtpVerificationRepository otpRepository;
  @Mock private PhoneChangeRequestRepository phoneChangeRepository;

  @Spy private Clock horloge = Clock.fixed(MAINTENANT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

  @InjectMocks private OtpGardeServiceImpl service;

  @Test
  @DisplayName("sous la limite : numéro verrouillé, envoi journalisé (sans le code)")
  void envoiAutorise() throws Exception {
    when(envoiRepository.countByPhoneAndCreatedAtAfter(TEL, MAINTENANT.minusHours(1)))
        .thenReturn(2L);

    service.autoriserEnvoi(TEL, Constants.OtpPurpose.RESET_PIN);

    verify(envoiRepository).verrouillerNumero(TEL);
    ArgumentCaptor<EnvoiOtp> envoi = ArgumentCaptor.forClass(EnvoiOtp.class);
    verify(envoiRepository).save(envoi.capture());
    assertThat(envoi.getValue().getPhone()).isEqualTo(TEL);
    assertThat(envoi.getValue().getPurpose()).isEqualTo(Constants.OtpPurpose.RESET_PIN);
  }

  @Test
  @DisplayName(
      "4ᵉ envoi dans l'heure → 429 OTP_RATE_LIMITED, attente jusqu'à la sortie du plus ancien")
  void limiteAtteinte() {
    when(envoiRepository.countByPhoneAndCreatedAtAfter(TEL, MAINTENANT.minusHours(1)))
        .thenReturn(3L);
    EnvoiOtp ancien = EnvoiOtp.builder().phone(TEL).createdAt(MAINTENANT.minusMinutes(50)).build();
    when(envoiRepository.findTopByPhoneAndCreatedAtAfterOrderByCreatedAtAsc(
            TEL, MAINTENANT.minusHours(1)))
        .thenReturn(Optional.of(ancien));

    assertThatThrownBy(() -> service.autoriserEnvoi(TEL, Constants.OtpPurpose.REGISTRATION))
        .isInstanceOf(TooManyRequestsException.class)
        .satisfies(
            e -> {
              CustomException ce = (CustomException) e;
              assertThat(ce.getCode()).isEqualTo(ErrorCode.OTP_RATE_LIMITED);
              assertThat(ce.getDetails()).containsEntry("retryAfterSeconds", 600L);
            });
    verify(envoiRepository, never()).save(any());
  }

  @Test
  @DisplayName("limite atteinte sans plus ancien retrouvé : attente d'une heure")
  void limiteSansPlusAncien() {
    when(envoiRepository.countByPhoneAndCreatedAtAfter(TEL, MAINTENANT.minusHours(1)))
        .thenReturn(5L);
    when(envoiRepository.findTopByPhoneAndCreatedAtAfterOrderByCreatedAtAsc(
            TEL, MAINTENANT.minusHours(1)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.autoriserEnvoi(TEL, Constants.OtpPurpose.REGISTRATION))
        .satisfies(
            e ->
                assertThat(((CustomException) e).getDetails())
                    .containsEntry("retryAfterSeconds", 3600L));
  }

  @Test
  @DisplayName("code faux : compté ; au 5ᵉ, code invalidé et OTP_TOO_MANY_ATTEMPTS")
  void essaisOtp() {
    OtpVerification otp = OtpVerification.builder().phone(TEL).code("123456").build();
    for (int i = 1; i < Constants.Otp.MAX_ESSAIS_PAR_CODE; i++) {
      assertThat(service.enregistrerEchec(otp)).isFalse();
    }
    assertThat(otp.isUsed()).isFalse();

    assertThat(service.enregistrerEchec(otp)).isTrue();
    assertThat(otp.isUsed()).isTrue();
    assertThat(otp.getTentatives()).isEqualTo(5);
    assertThat(OtpGardeService.erreurCodeFaux(true).getCode())
        .isEqualTo(ErrorCode.OTP_TOO_MANY_ATTEMPTS);
    assertThat(OtpGardeService.erreurCodeFaux(false).getCode()).isEqualTo(ErrorCode.OTP_INVALID);
  }

  @Test
  @DisplayName("changement de numéro : au 5ᵉ code faux, la demande expire")
  void essaisChangementNumero() {
    PhoneChangeRequestEntity demande =
        PhoneChangeRequestEntity.builder()
            .newPhone(TEL)
            .otpCode("123456")
            .tentatives(4)
            .expiresAt(MAINTENANT.plusMinutes(5))
            .build();

    assertThat(service.enregistrerEchec(demande)).isTrue();
    assertThat(demande.getExpiresAt()).isBefore(MAINTENANT);
    verify(phoneChangeRepository).save(demande);

    PhoneChangeRequestEntity neuve =
        PhoneChangeRequestEntity.builder().expiresAt(MAINTENANT.plusMinutes(5)).build();
    assertThat(service.enregistrerEchec(neuve)).isFalse();
    assertThat(neuve.getExpiresAt()).isEqualTo(MAINTENANT.plusMinutes(5));
  }
}
