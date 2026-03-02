package io.ostec.focusflow.dto.request.entitlement;

import jakarta.validation.constraints.NotBlank;

public record StripeWebhookRequest(
    @NotBlank
    String eventId,
    @NotBlank
    String type,
    @NotBlank
    String payload
) {
}
