package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateFocusSessionRequest(
    @NotBlank
    @Size(max = 500)
    String goal,
    @NotNull
    @Min(15)
    @Max(180)
    Integer durationMinutes,
    String correlationId
) {
}
