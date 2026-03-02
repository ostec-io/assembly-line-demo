package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AttachSessionPlanRequest(
    @NotBlank
    String planId,
    @NotNull
    Integer taskCount
) {
}
