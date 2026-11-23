package com.africa.dinthialma_backend.auth.service.impl;

import com.africa.dinthialma_backend.auth.entity.EnvoiOtp;
import com.africa.dinthialma_backend.auth.entity.OtpVerification;
import com.africa.dinthialma_backend.auth.entity.PhoneChangeRequestEntity;
import com.africa.dinthialma_backend.auth.repository.EnvoiOtpRepository;
import com.africa.dinthialma_backend.auth.repository.OtpVerificationRepository;
import com.africa.dinthialma_backend.auth.repository.PhoneChangeRequestRepository;
import com.africa.dinthialma_backend.auth.service.interfaces.OtpGardeService;
import com.africa.dinthialma_backend.common.constants.Constants;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtpGardeServiceImpl implements OtpGardeService {

  private final EnvoiOtpRepository envoiRepository;
  private final OtpVerificationRepository otpRepository;
  private final PhoneChangeRequestRepository phoneChangeRepository;
  private final Clock horloge;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void autoriserEnvoi(String phone, String purpose) throws CustomException {
    // Deux demandes simultanées pour ce numéro sont comptées l'une après l'autre
    envoiRepository.verrouillerNumero(phone);
    LocalDateTime maintenant = LocalDateTime.now(horloge);
    LocalDateTime debutFenetre = maintenant.minusHours(1);
    long envois = envoiRepository.countByPhoneAndCreatedAtAfter(phone, debutFenetre);
    if (envois >= Constants.Otp.MAX_ENVOIS_PAR_HEURE) {
      long attente =
          envoiRepository
              .findTopByPhoneAndCreatedAtAfterOrderByCreatedAtAsc(phone, debutFenetre)
              .map(e -> Duration.between(maintenant, e.getCreatedAt().plusHours(1)).getSeconds())
              .orElse(3600L);
      log.warn("Envoi d'OTP refusé : limite horaire atteinte – motif={}", purpose);
      throw new TooManyRequestsException(
          ResponseMessageConstants.AUTH_OTP_RATE_LIMITED,
          ErrorCode.OTP_RATE_LIMITED,
          Math.max(1, attente));
    }
    envoiRepository.save(EnvoiOtp.builder().phone(phone).purpose(purpose).build());
  }

  @Override
  public boolean enregistrerEchec(OtpVerification otp) {
    otp.setTentatives(otp.getTentatives() + 1);
    boolean epuise = otp.getTentatives() >= Constants.Otp.MAX_ESSAIS_PAR_CODE;
    if (epuise) {
      otp.setUsed(true);
    }
    otpRepository.save(otp);
    return epuise;
  }

  @Override
  public boolean enregistrerEchec(PhoneChangeRequestEntity demande) {
    demande.setTentatives(demande.getTentatives() + 1);
    boolean epuise = demande.getTentatives() >= Constants.Otp.MAX_ESSAIS_PAR_CODE;
    if (epuise) {
      demande.setExpiresAt(LocalDateTime.now(horloge).minusSeconds(1));
    }
    phoneChangeRepository.save(demande);
    return epuise;
  }
}
