package com.africa.dinthialma_backend.tontine.service.impl;

import com.africa.dinthialma_backend.auth.entity.User;
import com.africa.dinthialma_backend.common.constants.ResponseMessageConstants;
import com.africa.dinthialma_backend.common.exception.BadRequestException;
import com.africa.dinthialma_backend.common.exception.CustomException;
import com.africa.dinthialma_backend.common.exception.ErrorCode;
import com.africa.dinthialma_backend.common.exception.ForbiddenException;
import com.africa.dinthialma_backend.common.exception.NotFoundException;
import com.africa.dinthialma_backend.common.security.AccessControl;
import com.africa.dinthialma_backend.member.repository.TontineMembreRepository;
import com.africa.dinthialma_backend.tontine.codeList.TontineType;
import com.africa.dinthialma_backend.tontine.dto.DistributionFinaleResponse;
import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import com.africa.dinthialma_backend.tontine.entity.Tontine;
import com.africa.dinthialma_backend.tontine.mapper.DistributionMapper;
import com.africa.dinthialma_backend.tontine.repository.DistributionEvenementielleRepository;
import com.africa.dinthialma_backend.tontine.repository.TontineRepository;
import com.africa.dinthialma_backend.tontine.service.interfaces.DistributionService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DistributionServiceImpl implements DistributionService {

  private final AccessControl accessControl;
  private final TontineRepository tontineRepository;
  private final TontineMembreRepository membreRepository;
  private final DistributionEvenementielleRepository distributionRepository;
  private final DistributionMapper distributionMapper;

  @Override
  public DistributionFinaleResponse lire(String keycloakId, UUID tontineId) throws CustomException {
    User caller = accessControl.utilisateur(keycloakId);
    Tontine tontine =
        tontineRepository
            .findByIdAndDeletedAtIsNull(tontineId)
            .orElseThrow(() -> new NotFoundException(ResponseMessageConstants.TONTINE_NOT_FOUND));
    if (tontine.getTontineType() != TontineType.EVENEMENTIELLE) {
      throw new BadRequestException(
          "La distribution finale ne concerne que les tontines événementielles");
    }
    boolean gestionnaire =
        accessControl.estSuperAdmin(caller) || tontine.getCreePar().getId().equals(caller.getId());
    if (!gestionnaire
        && membreRepository
            .findByTontine_IdAndUser_IdAndDeletedAtIsNull(tontineId, caller.getId())
            .isEmpty()) {
      throw new ForbiddenException(ResponseMessageConstants.TONTINE_ACCESS_DENIED);
    }

    List<DistributionEvenementielle> parts =
        distributionRepository.findByTontineAvecMembres(tontineId);
    if (parts.isEmpty()) {
      throw new NotFoundException(
          ResponseMessageConstants.DISTRIBUTION_NON_FAITE, ErrorCode.DISTRIBUTION_NON_FAITE);
    }
    // Un membre ne voit que sa propre ligne ; les totaux portent toujours sur toute la tontine
    List<DistributionEvenementielle> visibles =
        gestionnaire
            ? parts
            : parts.stream()
                .filter(d -> d.getMembre().getUser().getId().equals(caller.getId()))
                .toList();

    return DistributionFinaleResponse.builder()
        .tontineId(tontineId)
        .cycleId(parts.getFirst().getCycle().getId())
        .dateDistribution(
            parts.stream()
                .map(DistributionEvenementielle::getCreatedAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null))
        .nombreMembres(parts.size())
        .totalCotise(somme(parts, DistributionEvenementielle::getMontantCotise))
        .totalCommission(somme(parts, DistributionEvenementielle::getMontantCommission))
        .totalNet(somme(parts, DistributionEvenementielle::getMontantNet))
        .lignes(visibles.stream().map(distributionMapper::toLigne).toList())
        .build();
  }

  private static BigDecimal somme(
      List<DistributionEvenementielle> parts,
      Function<DistributionEvenementielle, BigDecimal> montant) {
    return parts.stream()
        .map(montant)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
