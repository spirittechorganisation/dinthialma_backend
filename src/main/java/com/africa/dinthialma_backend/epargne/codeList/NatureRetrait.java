package com.africa.dinthialma_backend.epargne.codeList;

import io.swagger.v3.oas.annotations.media.Schema;

/** Nature d'un retrait anticipé d'épargne (B42). */
@Schema(
    description =
        "PARTIEL = une partie du solde, le membre continue de cotiser ; TOTAL = tout le solde, le"
            + " membre devient « soldé » (plus de cotisation ni de rappel)")
public enum NatureRetrait {
  PARTIEL,
  TOTAL
}
