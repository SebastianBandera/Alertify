package app.alertify.api.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApiRequestLoggingFilter;
import app.alertify.logging.ApiResponseLogLevelResolver;
import app.alertify.logging.ApplicationEventLogger;

/**
 * Converts domain, validation and persistence exceptions into the stable API
 * error format, exposes localization parameters and records the displayed
 * error code for request-level logging.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private final ApplicationEventLogger eventLogger;

    public ApiExceptionHandler(ApplicationEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(ConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, exception.getCode(), exception.getMessage(), Map.of(), exception.getParameters(), exception, request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "The operation conflicts with existing data", Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidFilterException.class)
    ResponseEntity<ApiError> handleInvalidFilter(InvalidFilterException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_FILTER", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidConfigurationImportException.class)
    ResponseEntity<ApiError> handleInvalidImport(InvalidConfigurationImportException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_CONFIGURATION_IMPORT", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidAlertImportException.class)
    ResponseEntity<ApiError> handleInvalidAlertImport(InvalidAlertImportException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ALERT_IMPORT", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE", "The uploaded file is too large", Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidConfigurationValueException.class)
    ResponseEntity<ApiError> handleInvalidValue(InvalidConfigurationValueException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_CONFIGURATION_VALUE", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidConfigurationExpressionException.class)
    ResponseEntity<ApiError> handleInvalidExpression(InvalidConfigurationExpressionException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_CONFIGURATION_EXPRESSION", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidSecretValueException.class)
    ResponseEntity<ApiError> handleInvalidSecretValue(InvalidSecretValueException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_SECRET_VALUE", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(InvalidAlertRequestException.class)
    ResponseEntity<ApiError> handleInvalidAlertRequest(InvalidAlertRequestException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ALERT_REQUEST", exception.getMessage(), Map.of(), exception, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        exception.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fieldErrors, exception, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message, Map<String, String> fieldErrors, Exception exception, HttpServletRequest request) {
        return response(status, code, message, fieldErrors, Map.of(), exception, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message, Map<String, String> fieldErrors, Map<String, String> parameters, Exception exception, HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.value());
        data.put("errorCode", code);
        data.put("errorType", exception.getClass().getSimpleName());

        if (!fieldErrors.isEmpty())
            data.put("fields", fieldErrors.keySet());

        request.setAttribute(ApiRequestLoggingFilter.ERROR_CODE_REQUEST_ATTRIBUTE, code);
        eventLogger.failure("API_ERROR_SHOWN", ApiResponseLogLevelResolver.resolve(status.value(), code), data);
        ApiError error = new ApiError(Instant.now(), status.value(), code, message, fieldErrors, parameters);
        return ResponseEntity.status(status).body(error);
    }
}
