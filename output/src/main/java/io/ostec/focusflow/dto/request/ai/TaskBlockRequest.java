package io.ostec.focusflow.dto.request.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskBlockRequest(
    String blockId,
    @NotBlank
    @Size(max = 200)
    String title,
    @Size(max = 1000)
    String details,
    @NotNull
    @Min(1)
    @Max(360)
    Integer estimateMinutes,
    @Min(1)
    Integer sequence
) {
}
