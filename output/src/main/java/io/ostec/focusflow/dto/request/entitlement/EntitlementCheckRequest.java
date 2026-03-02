package io.ostec.focusflow.dto.request.entitlement;

import jakarta.validation.constraints.NotBlank;

public record EntitlementCheckRequest(
    @NotBlank
    String featureKey,
    @NotBlank
    String action,
    String resourceId
) {
}
