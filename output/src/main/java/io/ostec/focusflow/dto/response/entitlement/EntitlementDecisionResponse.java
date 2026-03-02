package io.ostec.focusflow.dto.response.entitlement;

public record EntitlementDecisionResponse(
    boolean allowed,
    String reasonCode,
    Integer remaining
) {
}
