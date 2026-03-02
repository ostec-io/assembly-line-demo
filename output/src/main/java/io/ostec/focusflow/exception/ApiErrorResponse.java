package io.ostec.focusflow.exception;

import java.time.Instant;

public record ApiErrorResponse(
    String code,
    String message,
    int status,
    String correlationId,
    String tenantId,
    Instant timestamp
) {
}
