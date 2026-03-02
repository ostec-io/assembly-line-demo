package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RecordSessionInterruptionRequest(
    @NotBlank
    String interruptionType,
    @NotNull
    Instant detectedAt
) {
}
