package com.africa.dinthialma_backend.auth.service.interfaces;

import com.africa.dinthialma_backend.auth.entity.OtpVerification;
import com.africa.dinthialma_backend.auth.entity.PhoneChangeRequestEntity;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;

/**
 * Garde-fous des codes OTP (SE07), compteurs en base : 3 envois par numéro et par heure (tous
 * motifs confondus), 5 essais par code.
 */
public interface OtpGardeService {

  /**
   * Enregistre une demande d'envoi pour ce numéro, ou la refuse (429 {@code OTP_RATE_LIMITED},
   * {@code data.retryAfterSeconds}) si 3 envois ont déjà eu lieu dans l'heure. À appeler dans la
   * transaction de l'envoi, avant de générer le code.
   */
  void autoriserEnvoi(String phone, String purpose) throws CustomException;

  /**
   * Compte un essai faux sur un code. Au 5ᵉ, le code est invalidé ({@code used = true}).
   *
   * @return true si le code vient d'être invalidé : l'appelant lève {@code OTP_TOO_MANY_ATTEMPTS},
   *     sinon {@code OTP_INVALID}
   */
  boolean enregistrerEchec(OtpVerification otp);

  /** Même règle pour le code d'un changement de numéro (la demande expire au 5ᵉ essai faux). */
  boolean enregistrerEchec(PhoneChangeRequestEntity demande);

  /** Erreur à lever après un code faux, selon que le code est épuisé ou non. */
  static CustomException erreurCodeFaux(boolean epuise) {
    if (epuise) {
      return new BadRequestException(
          ResponseMessageConstants.AUTH_OTP_TOO_MANY_ATTEMPTS, ErrorCode.OTP_TOO_MANY_ATTEMPTS);
    }
    return new BadRequestException(
        ResponseMessageConstants.AUTH_OTP_INVALID, ErrorCode.OTP_INVALID);
  }
}
