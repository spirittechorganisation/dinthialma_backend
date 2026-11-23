package com.africa.dinthialma_backend.common.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class BadRequestException extends CustomException {

  public BadRequestException(String message) {
    super(HttpStatus.BAD_REQUEST, message);
  }

  public BadRequestException(String message, ErrorCode code) {
    super(HttpStatus.BAD_REQUEST, message, code);
  }

  public BadRequestException(String message, ErrorCode code, Map<String, Object> details) {
    super(HttpStatus.BAD_REQUEST, message, code, details);
  }
}
