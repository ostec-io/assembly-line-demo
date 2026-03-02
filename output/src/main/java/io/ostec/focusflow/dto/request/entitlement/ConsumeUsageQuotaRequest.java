package io.ostec.focusflow.dto.request.entitlement;

import jakarta.validation.constraints.NotBlank;

public record ConsumeUsageQuotaRequest(
    @NotBlank
    String featureKey,
    @NotBlank
    String idempotencyKey
) {
}
