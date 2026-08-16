package app.alertify.api.error;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import app.alertify.jpa.specification.InvalidFilterException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler({ ConflictException.class, ObjectOptimisticLockingFailureException.class })
    ResponseEntity<ApiError> handleConflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception) {
        return response(
            HttpStatus.CONFLICT,
            "DATA_INTEGRITY_CONFLICT",
            "The operation conflicts with existing data",
            Map.of()
        );
    }

    @ExceptionHandler(InvalidFilterException.class)
    ResponseEntity<ApiError> handleInvalidFilter(InvalidFilterException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_FILTER", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidConfigurationValueException.class)
    ResponseEntity<ApiError> handleInvalidValue(InvalidConfigurationValueException exception) {
        return response(
            HttpStatus.BAD_REQUEST,
            "INVALID_CONFIGURATION_VALUE",
            exception.getMessage(),
            Map.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );

        return response(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "Request validation failed",
            fieldErrors
        );
    }

    private static ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fieldErrors) {
        ApiError error = new ApiError(Instant.now(), status.value(), code, message, fieldErrors);
        return ResponseEntity.status(status).body(error);
    }
}
