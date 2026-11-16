package com.africa.dinthialma_backend.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ConflictException extends CustomException {

  public ConflictException(String message) {
    super(HttpStatus.CONFLICT, message);
  }

  public ConflictException(String message, ErrorCode code) {
    super(HttpStatus.CONFLICT, message, code);
  }

  /** Variante avec le détail du conflit renvoyé dans {@code data}. */
  public ConflictException(String message, ErrorCode code, Map<String, Object> details) {
    super(HttpStatus.CONFLICT, message, code, details);
  }
}
