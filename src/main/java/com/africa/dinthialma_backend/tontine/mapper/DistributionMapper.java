package com.africa.dinthialma_backend.tontine.mapper;

import com.africa.dinthialma_backend.tontine.dto.DistributionFinaleResponse;
import com.africa.dinthialma_backend.tontine.entity.DistributionEvenementielle;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Lignes de la répartition finale d'une tontine événementielle (B51). */
@Mapper
public interface DistributionMapper {

  @Mapping(target = "membreId", source = "membre.id")
  @Mapping(target = "userId", source = "membre.user.id")
  @Mapping(target = "firstName", source = "membre.user.firstName")
  @Mapping(target = "lastName", source = "membre.user.lastName")
  @Mapping(target = "phone", source = "membre.user.phone")
  @Mapping(target = "statutMembre", source = "membre.statut")
  DistributionFinaleResponse.Ligne toLigne(DistributionEvenementielle distribution);
}
