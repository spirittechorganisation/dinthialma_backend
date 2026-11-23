package com.africa.dinthialma_backend.epargne.controller;

import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.response.CustomResponse;
import com.africa.dinthialma_backend.common.util.CsvExport;
import com.africa.dinthialma_backend.common.util.RequestHeaderParser;
import com.africa.dinthialma_backend.epargne.codeList.StatutRetrait;
import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.dto.RetraitRequests;
import com.africa.dinthialma_backend.epargne.dto.SoldeEpargneResponse;
import com.africa.dinthialma_backend.epargne.service.interfaces.RetraitEpargneService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Retraits anticipés d'une épargne événementielle (B42, lot 1 : saisie du gestionnaire). */
@RestController
@RequestMapping("/v1/tontines/{tontineId}")
@RequiredArgsConstructor
@Tag(
    name = "Retraits d'épargne",
    description =
        "Un membre reprend tout ou partie de son épargne événementielle avant la fin ; le"
            + " gestionnaire enregistre l'argent remis (B42)")
@SecurityRequirement(name = "bearerAuth")
public class RetraitEpargneController {

  private static final String SUCCESS = "success";
  private static final int OK = 200;

  private final RetraitEpargneService retraitService;
  private final RequestHeaderParser headerParser;

  @PostMapping("/retraits/admin")
  @Operation(
      summary = "Enregistrer un retrait remis à un membre",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN. Événementielle ACTIVE ou SUSPENDUE,"
              + " avant la distribution finale, règle de retrait `LIBRE`. Le retrait est créé"
              + " **REMIS** (argent déjà remis).\n\n"
              + "`PARTIEL` : `montant` obligatoire, ≤ solde. `TOTAL` : tout le solde, le membre"
              + " devient soldé (`soldeLe`) : plus de cotisation ni de rappel. Refusé tant que le"
              + " membre a des cotisations EN_ATTENTE ou EN_RETARD : les valider ou les refuser"
              + " d'abord.\n\n"
              + "`dateRemise` (facultative, défaut maintenant) : date réelle, pour reporter les"
              + " retraits notés dans le cahier ; jamais future, pas avant le début de la tontine."
              + " Membre notifié (`RETRAIT_REMIS`, in-app + WhatsApp), sauf si `dateRemise` date"
              + " de plus de 24 h.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Retrait enregistré (avec `soldeRestant`)",
        content = @Content(schema = @Schema(implementation = RetraitEpargneResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description =
            "`RETRAIT_NON_DISPONIBLE` (rotative, pas active, terminée ou distribuée),"
                + " `RETRAIT_INTERDIT`, `MEMBRE_SOLDE`, `RETRAIT_SOLDE_INSUFFISANT` (`data.solde`),"
                + " `RETRAIT_COTISATIONS_EN_ATTENTE` (retrait total avec des cotisations"
                + " EN_ATTENTE ou EN_RETARD à traiter d'abord, `data.cotisationsEnAttente`),"
                + " `INVALID_PARAMETER` (montant ou date), `VALIDATION_ERROR`"),
    @ApiResponse(responseCode = "403", description = "Accès interdit"),
    @ApiResponse(responseCode = "404", description = "Tontine ou membre introuvable"),
  })
  public ResponseEntity<CustomResponse> enregistrer(
      @PathVariable UUID tontineId,
      @RequestBody @Valid RetraitRequests.Saisir request,
      HttpServletRequest httpRequest)
      throws CustomException {
    RetraitEpargneResponse r =
        retraitService.enregistrer(headerParser.extractKeycloakId(httpRequest), tontineId, request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new CustomResponse(SUCCESS, 201, ResponseMessageConstants.RETRAIT_CREATED, r));
  }

  @GetMapping("/retraits")
  @Operation(
      summary = "Lister les retraits",
      description =
          "🔒 Créateur de la tontine et SUPER_ADMIN : tous les retraits (filtre `membreId`) ;"
              + " membre : les siens seulement (`membreId` ignoré). Filtre `statut` répétable."
              + " Tri par défaut : `dateRemise` décroissante.")
  public ResponseEntity<CustomResponse> lister(
      @PathVariable UUID tontineId,
      @RequestParam(required = false) UUID membreId,
      @Parameter(description = "Statuts (répétable : `?statut=REMIS&statut=ANNULE`)")
          @RequestParam(required = false)
          List<StatutRetrait> statut,
      @PageableDefault(size = 20, sort = "dateRemise", direction = Sort.Direction.DESC)
          Pageable pageable,
      HttpServletRequest httpRequest)
      throws CustomException {
    Page<RetraitEpargneResponse> page =
        retraitService.lister(
            headerParser.extractKeycloakId(httpRequest), tontineId, membreId, statut, pageable);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.RETRAIT_LIST_SUCCESS, page));
  }

  @GetMapping("/retraits/export")
  @Operation(
      summary = "Exporter les retraits (CSV)",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN. Tous les retraits de la tontine, annulés"
              + " compris ; séparateur `;`, BOM UTF-8.")
  public ResponseEntity<byte[]> exporter(
      @PathVariable UUID tontineId,
      @Parameter(description = "Format d'export (csv)") @RequestParam(defaultValue = "csv")
          String format,
      HttpServletRequest httpRequest)
      throws CustomException {
    return CsvExport.reponse(
        retraitService.exporter(headerParser.extractKeycloakId(httpRequest), tontineId, format));
  }

  @DeleteMapping("/retraits/{retraitId}")
  @Operation(
      summary = "Annuler un retrait saisi par erreur",
      description =
          "🔒 Rôles : créateur de la tontine, SUPER_ADMIN. Corps `{motif}` obligatoire. Le retrait"
              + " passe **ANNULE** ; un retrait total annulé rend le membre non soldé. Impossible"
              + " après la distribution finale, ou sur un retrait partiel tant que le membre est"
              + " soldé (`RETRAIT_NON_ANNULABLE`).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Retrait annulé (avec `soldeRestant`)",
        content = @Content(schema = @Schema(implementation = RetraitEpargneResponse.class))),
    @ApiResponse(responseCode = "400", description = "`RETRAIT_NON_ANNULABLE`, `VALIDATION_ERROR`"),
    @ApiResponse(responseCode = "403", description = "Accès interdit"),
    @ApiResponse(responseCode = "404", description = "Retrait introuvable"),
  })
  public ResponseEntity<CustomResponse> annuler(
      @PathVariable UUID tontineId,
      @PathVariable UUID retraitId,
      @RequestBody @Valid RetraitRequests.Annuler request,
      HttpServletRequest httpRequest)
      throws CustomException {
    RetraitEpargneResponse r =
        retraitService.annuler(
            headerParser.extractKeycloakId(httpRequest), tontineId, retraitId, request);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.RETRAIT_CANCELLED, r));
  }

  @GetMapping("/membres/{membreId}/solde")
  @Operation(
      summary = "Solde d'épargne d'un membre",
      description =
          "🔒 Créateur de la tontine, SUPER_ADMIN, ou le membre concerné. `solde` = cotisations"
              + " VALIDÉES − retraits REMIS ; `soldeLe` renseigné si le membre a tout retiré.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Solde",
        content = @Content(schema = @Schema(implementation = SoldeEpargneResponse.class))),
    @ApiResponse(responseCode = "403", description = "Accès interdit"),
    @ApiResponse(responseCode = "404", description = "Tontine ou membre introuvable"),
  })
  public ResponseEntity<CustomResponse> solde(
      @PathVariable UUID tontineId, @PathVariable UUID membreId, HttpServletRequest httpRequest)
      throws CustomException {
    SoldeEpargneResponse s =
        retraitService.solde(headerParser.extractKeycloakId(httpRequest), tontineId, membreId);
    return ResponseEntity.ok(
        new CustomResponse(SUCCESS, OK, ResponseMessageConstants.SOLDE_SUCCESS, s));
  }
}
