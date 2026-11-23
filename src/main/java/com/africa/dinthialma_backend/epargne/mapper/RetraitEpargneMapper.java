package com.africa.dinthialma_backend.epargne.mapper;

import com.africa.dinthialma_backend.epargne.dto.RetraitEpargneResponse;
import com.africa.dinthialma_backend.epargne.entity.RetraitEpargne;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Conversions des retraits anticipés d'épargne (B42). */
@Mapper
public interface RetraitEpargneMapper {

  @Mapping(target = "tontineId", source = "tontine.id")
  @Mapping(target = "membreId", source = "membre.id")
  @Mapping(target = "userId", source = "membre.user.id")
  @Mapping(target = "firstName", source = "membre.user.firstName")
  @Mapping(target = "lastName", source = "membre.user.lastName")
  @Mapping(target = "soldeRestant", ignore = true)
  RetraitEpargneResponse toResponse(RetraitEpargne retrait);
}
