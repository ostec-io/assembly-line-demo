package io.ostec.focusflow.exception;

import com.mongodb.MongoException;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Set<ApiErrorCode> POLICY_VIOLATION_CODES = Set.of(
        ApiErrorCode.ACTIVE_SESSION_EXISTS,
        ApiErrorCode.COMPLETION_CRITERIA_NOT_MET,
        ApiErrorCode.NOT_HOST,
        ApiErrorCode.ROOM_LOCKED,
        ApiErrorCode.ROOM_FULL,
        ApiErrorCode.REVEAL_PRECONDITIONS_NOT_MET,
        ApiErrorCode.DUPLICATE_EVENT,
        ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
        ApiErrorCode.BILLING_TRANSITION_CONFLICT,
        ApiErrorCode.POLICY_VIOLATION
    );
    private static final Set<ApiErrorCode> NOT_FOUND_CODES = Set.of(
        ApiErrorCode.SESSION_NOT_FOUND,
        ApiErrorCode.PLAN_NOT_FOUND,
        ApiErrorCode.PARTICIPANT_NOT_FOUND,
        ApiErrorCode.RESOURCE_NOT_FOUND
    );

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        BindException.class,
        ConstraintViolationException.class,
        IllegalArgumentException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleValidation(
        Exception ex,
        HttpServletRequest request
    ) {
        ErrorDescriptor descriptor = new ErrorDescriptor(
            ApiErrorCode.VALIDATION_ERROR,
            resolveValidationMessage(ex),
            HttpStatus.BAD_REQUEST
        );
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleOptimisticLock(
        OptimisticLockingFailureException ex,
        HttpServletRequest request
    ) {
        ErrorDescriptor descriptor = mapByErrorCode(
            ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT,
            "Concurrent update conflict",
            "Concurrent update conflict"
        );
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(
        DuplicateKeyException ex,
        HttpServletRequest request
    ) {
        ErrorDescriptor descriptor = mapByErrorCode(
            ApiErrorCode.DUPLICATE_EVENT,
            "Duplicate request or event",
            "Duplicate request or event"
        );
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
        NoSuchElementException ex,
        HttpServletRequest request
    ) {
        ErrorDescriptor descriptor = mapByErrorCode(
            ApiErrorCode.RESOURCE_NOT_FOUND,
            ex.getMessage(),
            "Resource not found"
        );
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler({
        TransientDataAccessException.class,
        HttpTimeoutException.class,
        ConnectException.class,
        MongoException.class
    })
    public ResponseEntity<ApiErrorResponse> handleTransientInfra(
        Exception ex,
        HttpServletRequest request
    ) {
        log.warn("Transient infrastructure exception", ex);
        ErrorDescriptor descriptor = mapByErrorCode(
            ApiErrorCode.TRANSIENT_INFRA_FAILURE,
            ex.getMessage(),
            "Transient infrastructure failure"
        );
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalState(
        IllegalStateException ex,
        HttpServletRequest request
    ) {
        ErrorDescriptor descriptor = mapIllegalStateException(ex);
        if (descriptor.status().is5xxServerError()) {
            log.error("Unhandled application state", ex);
        }
        return buildResponse(descriptor, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnhandled(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        ErrorDescriptor descriptor = mapByErrorCode(ApiErrorCode.INTERNAL_ERROR, ex.getMessage(), "Unexpected error");
        return buildResponse(descriptor, request);
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(ErrorDescriptor descriptor, HttpServletRequest request) {
        ApiErrorResponse response = toErrorResponse(descriptor.code(), descriptor.message(), descriptor.status(), request);
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.hasText(response.correlationId())) {
            headers.add(CORRELATION_HEADER, response.correlationId());
        }
        return new ResponseEntity<>(response, headers, descriptor.status());
    }

    private ErrorDescriptor mapIllegalStateException(IllegalStateException ex) {
        if (isTenantContextMissing(ex)) {
            return mapByErrorCode(
                ApiErrorCode.TENANT_CONTEXT_MISSING,
                "Tenant context is required for this operation",
                "Tenant context is required for this operation"
            );
        }

        String rawMessage = normalizeMessage(ex.getMessage());
        ApiErrorCode explicitCode = parseLeadingErrorCode(rawMessage);
        if (explicitCode != null) {
            return mapByErrorCode(explicitCode, stripLeadingErrorCode(rawMessage), explicitCode.name());
        }

        String normalizedMessage = rawMessage.toLowerCase(Locale.ROOT);

        if (containsAny(normalizedMessage, "not found", "no generated plan is available")) {
            return mapByErrorCode(ApiErrorCode.RESOURCE_NOT_FOUND, rawMessage, "Resource not found");
        }

        if (containsAny(normalizedMessage, "entitlement denied", "usage limit reached")) {
            ApiErrorCode code = normalizedMessage.contains("usage limit")
                ? ApiErrorCode.USAGE_LIMIT_REACHED
                : ApiErrorCode.ENTITLEMENT_DENIED;
            return mapByErrorCode(code, rawMessage, code == ApiErrorCode.USAGE_LIMIT_REACHED
                ? "Usage limit reached"
                : "Entitlement denied");
        }

        if (containsAny(normalizedMessage, "provider", "timeout", "temporarily unavailable", "service unavailable")) {
            return mapByErrorCode(ApiErrorCode.PROVIDER_UNAVAILABLE, rawMessage, "Provider unavailable");
        }

        if (containsAny(
            normalizedMessage,
            "duplicate",
            "conflict",
            "already",
            "precondition",
            "cannot",
            "only",
            "room_locked",
            "room_full",
            "reveal_preconditions_not_met",
            "room_id_mismatch"
        )) {
            return mapByErrorCode(ApiErrorCode.POLICY_VIOLATION, rawMessage, "Policy violation");
        }

        if (containsAny(normalizedMessage, "must not", "required", "invalid")) {
            return mapByErrorCode(ApiErrorCode.VALIDATION_ERROR, rawMessage, "Validation failed");
        }

        return mapByErrorCode(ApiErrorCode.INTERNAL_ERROR, rawMessage, "Unexpected application state");
    }

    private ErrorDescriptor mapByErrorCode(ApiErrorCode code, String message, String fallbackMessage) {
        HttpStatus status = mapStatus(code);
        String effectiveMessage = StringUtils.hasText(message) ? message : fallbackMessage;
        return new ErrorDescriptor(code, effectiveMessage, status);
    }

    private HttpStatus mapStatus(ApiErrorCode code) {
        if (code == ApiErrorCode.VALIDATION_ERROR
            || code == ApiErrorCode.INVALID_DURATION
            || code == ApiErrorCode.TENANT_CONTEXT_MISSING) {
            return HttpStatus.BAD_REQUEST;
        }

        if (code == ApiErrorCode.ENTITLEMENT_DENIED || code == ApiErrorCode.USAGE_LIMIT_REACHED) {
            return HttpStatus.FORBIDDEN;
        }

        if (NOT_FOUND_CODES.contains(code)) {
            return HttpStatus.NOT_FOUND;
        }

        if (POLICY_VIOLATION_CODES.contains(code)) {
            return HttpStatus.CONFLICT;
        }

        if (code == ApiErrorCode.PROVIDER_UNAVAILABLE || code == ApiErrorCode.TRANSIENT_INFRA_FAILURE) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveValidationMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException methodArgumentException) {
            return extractFieldValidationMessage(methodArgumentException.getBindingResult().getFieldErrors());
        }

        if (ex instanceof BindException bindException) {
            return extractFieldValidationMessage(bindException.getBindingResult().getFieldErrors());
        }

        if (ex instanceof ConstraintViolationException constraintViolationException
            && !CollectionUtils.isEmpty(constraintViolationException.getConstraintViolations())) {
            return constraintViolationException.getConstraintViolations().iterator().next().getMessage();
        }

        if (ex instanceof MissingServletRequestParameterException missingParameterException) {
            return "Missing required request parameter: " + missingParameterException.getParameterName();
        }

        if (ex instanceof HttpMessageNotReadableException) {
            return "Malformed request payload";
        }

        return normalizeMessage(ex.getMessage());
    }

    private String extractFieldValidationMessage(java.util.List<FieldError> fieldErrors) {
        return fieldErrors.stream()
            .findFirst()
            .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
            .orElse("Validation failed");
    }

    private ApiErrorCode parseLeadingErrorCode(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }

        String candidate = message;
        int colonIndex = candidate.indexOf(':');
        if (colonIndex > -1) {
            candidate = candidate.substring(0, colonIndex);
        }

        int spaceIndex = candidate.indexOf(' ');
        if (spaceIndex > -1) {
            candidate = candidate.substring(0, spaceIndex);
        }

        String normalized = candidate.trim();
        if (!normalized.matches("[A-Z0-9_]+")) {
            return null;
        }

        try {
            return ApiErrorCode.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String stripLeadingErrorCode(String message) {
        if (!StringUtils.hasText(message)) {
            return message;
        }
        int index = message.indexOf(':');
        if (index < 0 || index + 1 >= message.length()) {
            return message;
        }
        return message.substring(index + 1).trim();
    }

    private boolean containsAny(String message, String... patterns) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        for (String pattern : patterns) {
            if (message.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeMessage(String message) {
        return StringUtils.hasText(message) ? message.trim() : "Unexpected error";
    }

    private ApiErrorResponse toErrorResponse(
        ApiErrorCode code,
        String message,
        HttpStatus status,
        HttpServletRequest request
    ) {
        return new ApiErrorResponse(
            code.name(),
            message,
            status.value(),
            resolveCorrelationId(request),
            TenantContextHolder.getTenantId(),
            Instant.now()
        );
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationHeader = request.getHeader(CORRELATION_HEADER);
        if (StringUtils.hasText(correlationHeader)) {
            return correlationHeader;
        }

        String correlationFromContext = TenantContextHolder.getCorrelationId();
        return StringUtils.hasText(correlationFromContext) ? correlationFromContext : null;
    }

    private boolean isTenantContextMissing(IllegalStateException ex) {
        if (ApiErrorCode.TENANT_CONTEXT_MISSING.name().equals(ex.getMessage())) {
            return true;
        }
        String message = ex.getMessage();
        return message != null && (
            message.contains("Tenant ID is required")
                || message.contains("Organization ID is required")
        );
    }

    private record ErrorDescriptor(
        ApiErrorCode code,
        String message,
        HttpStatus status
    ) {
    }
}
