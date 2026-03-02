package io.ostec.focusflow.dto.request.insights;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record IngestAnalyticsEventRequest(
    @NotBlank
    String eventId,
    @NotBlank
    String eventType,
    @NotNull
    Instant occurredAt,
    @NotNull
    Map<String, Object> payload
) {
    public IngestAnalyticsEventRequest {
        if (eventId != null) {
            eventId = eventId.trim();
        }
        if (eventType != null) {
            eventType = eventType.trim();
        }
        if (payload != null) {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
