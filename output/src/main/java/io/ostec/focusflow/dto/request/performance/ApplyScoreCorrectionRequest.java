package io.ostec.focusflow.dto.request.performance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ApplyScoreCorrectionRequest(
    @NotBlank
    String userId,
    @NotNull
    LocalDate date,
    @NotBlank
    String reason,
    @NotNull
    BigDecimal correctedScore
) {
}
