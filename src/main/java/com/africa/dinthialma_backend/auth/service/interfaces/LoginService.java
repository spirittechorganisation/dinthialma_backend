package com.africa.dinthialma_backend.auth.service.interfaces;

import com.africa.dinthialma_backend.auth.codeList.ClientType;
import com.africa.dinthialma_backend.auth.dto.LoginRequest;
import com.africa.dinthialma_backend.auth.dto.LoginResponse;
import com.africa.dinthialma_backend.common.exception.CustomException;

/** Service de connexion – résout l'identifiant, authentifie et persiste la session PIN. */
public interface LoginService {

  /**
   * Authentifie un utilisateur et crée/met à jour sa session PIN en base.
   *
   * <p>Flux :
   *
   * <ol>
   *   <li>Normalisation : suppression du {@code +} initial ({@code PhoneUtils.normalize}).
   *   <li>Recherche en base par téléphone normalisé, puis par email.
   *   <li>Vérification que le compte est actif et non supprimé.
   *   <li>Appel Keycloak ROPC → tokens JWT.
   *   <li>Persistance de la session PIN (refresh token chiffré AES-256-GCM).
   * </ol>
   *
   * @param request {@code username} (phone ou email) + mot de passe + {@code clientType}
   *     (WEB/MOBILE)
   * @return tokens JWT retournés par Keycloak
   * @throws CustomException 401 si identifiants incorrects, 403 si compte désactivé
   */
  LoginResponse login(LoginRequest request) throws CustomException;

  /**
   * Révoque la session Keycloak ET supprime la session PIN en base pour cet appareil.
   *
   * @param refreshToken token Keycloak à invalider (contient le keycloakId dans son payload JWT)
   * @param clientType type d'appareil à déconnecter ; null = révoquer toutes les sessions
   */
  void logout(String refreshToken, ClientType clientType);

  /**
   * Déconnexion complète d'un appareil (B53) : la session de cet {@code appareilId} est supprimée
   * et son jeton hors ligne révoqué chez Keycloak ; sans {@code appareilId}, comportement de {@link
   * #logout(String, ClientType)}.
   */
  void logout(String refreshToken, ClientType clientType, String appareilId);

  /**
   * Échange un refresh token (normal ou hors ligne, B53) contre de nouveaux jetons, puis met à jour
   * la session de l'appareil qui le portait (rotation).
   *
   * @throws CustomException 401 {@code SESSION_EXPIRED} si Keycloak le refuse
   */
  LoginResponse rafraichir(String refreshToken) throws CustomException;
}
