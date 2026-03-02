package io.ostec.focusflow.dto.request.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GenerateSessionPlanRequest(
    @NotBlank
    String sessionId,
    @NotBlank
    @Size(max = 1000)
    String goal,
    @NotNull
    @Min(15)
    @Max(180)
    Integer durationMinutes
) {
}
