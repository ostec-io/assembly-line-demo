package io.ostec.focusflow.dto.request.ai;

import jakarta.validation.constraints.NotBlank;

public record UpdatePlanLearningMemoryRequest(
    @NotBlank
    String feedback,
    @NotBlank
    String outcome
) {
}
