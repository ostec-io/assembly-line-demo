package io.ostec.focusflow.dto.request.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AttachGeneratedPlanRequest(
    @NotBlank
    String sessionId,
    @NotNull
    Long revision
) {
}
