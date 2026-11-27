package com.africa.dinthialma_backend.contribution.controller;

import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.response.CustomResponse;
import com.africa.dinthialma_backend.common.util.RequestHeaderParser;
import com.africa.dinthialma_backend.contribution.dto.RepriseRequest;
import com.africa.dinthialma_backend.contribution.dto.RepriseResponse;
import com.africa.dinthialma_backend.contribution.service.interfaces.RepriseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reprise d'une tontine déjà commencée sur un cahier (B41, lot 1 : soldes de départ). */
@RestController
@RequestMapping("/v1/tontines/{tontineId}/reprise")
@RequiredArgsConstructor
@Tag(
    name = "Reprise d'une tontine commencée",
    description =
        "Le gestionnaire reporte le solde de départ de chaque membre, recopié du cahier (B41)")
@SecurityRequirement(name = "bearerAuth")
public class RepriseController {

  private static final String SUCCESS = "success";
  private static final int OK = 200;

  private final RepriseService repriseService;
  private final RequestHeaderParser headerParser;

  @GetMapping
  @Operation(
      summary = "Consulter la reprise",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN (tous les soldes) ; membre de la"
              + " tontine (son solde et le total). `statut` : AUCUNE, OUVERTE ou FIGEE.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Reprise",
        content = @Content(schema = @Schema(implementation = RepriseResponse.class))),
    @ApiResponse(responseCode = "403", description = "Ni gestionnaire ni membre"),
    @ApiResponse(responseCode = "404", description = "Tontine introuvable")
  })
  public ResponseEntity<CustomResponse> lire(
      @PathVariable UUID tontineId, HttpServletRequest httpRequest) throws CustomException {
    String keycloakId = headerParser.extractKeycloakId(httpRequest);
    RepriseResponse response = repriseService.lire(keycloakId, tontineId);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.REPRISE_SUCCESS, response));
  }

  @PutMapping
  @Operation(
      summary = "Enregistrer les soldes de départ",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN. Événementielle non terminée"
              + " (`REPRISE_NON_AUTORISEE` sinon), reprise pas encore figée (`REPRISE_FIGEE`).\n\n"
              + "`dateDebutReelle` : début du cahier, avant la date de début de la tontine, sans"
              + " limite (`REPRISE_DATE_INVALIDE`). `soldes` : un par membre, en francs entiers ;"
              + " **remplace l'ensemble** (un membre absent perd son solde de départ). Chaque"
              + " changement est audité ; aucune notification à la saisie.\n\n"
              + "Les soldes sont des cotisations VALIDE d'origine REPRISE sur le tour de reprise"
              + " (n° 0, TERMINE, sans échéance) : ils comptent dans le solde, les récapitulatifs"
              + " et la distribution finale, sans rappel, ponctualité (`REPRISE`) ni retard.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Soldes enregistrés",
        content = @Content(schema = @Schema(implementation = RepriseResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description =
            "`REPRISE_NON_AUTORISEE`, `REPRISE_FIGEE`, `REPRISE_DATE_INVALIDE`, membre en double"
                + " ou montant non entier"),
    @ApiResponse(responseCode = "403", description = "Pas le gestionnaire"),
    @ApiResponse(responseCode = "404", description = "Tontine ou membre introuvable")
  })
  public ResponseEntity<CustomResponse> enregistrer(
      @PathVariable UUID tontineId,
      @RequestBody @Valid RepriseRequest request,
      HttpServletRequest httpRequest)
      throws CustomException {
    String keycloakId = headerParser.extractKeycloakId(httpRequest);
    RepriseResponse response = repriseService.enregistrer(keycloakId, tontineId, request);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.REPRISE_UPDATED, response));
  }

  @PostMapping("/figer")
  @Operation(
      summary = "Figer la reprise",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN. Les soldes de départ ne sont plus"
              + " modifiables ; chaque membre est prévenu de son solde (`SOLDE_DEPART`, in-app +"
              + " WhatsApp) pour pouvoir le contester. Sans effet si déjà figée. La reprise est"
              + " aussi figée automatiquement au premier retrait et à la distribution finale.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Reprise figée",
        content = @Content(schema = @Schema(implementation = RepriseResponse.class))),
    @ApiResponse(responseCode = "400", description = "Aucune reprise (`REPRISE_NON_AUTORISEE`)"),
    @ApiResponse(responseCode = "403", description = "Pas le gestionnaire"),
    @ApiResponse(responseCode = "404", description = "Tontine introuvable")
  })
  public ResponseEntity<CustomResponse> figer(
      @PathVariable UUID tontineId, HttpServletRequest httpRequest) throws CustomException {
    String keycloakId = headerParser.extractKeycloakId(httpRequest);
    RepriseResponse response = repriseService.figer(keycloakId, tontineId);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.REPRISE_FIGEE, response));
  }
}
