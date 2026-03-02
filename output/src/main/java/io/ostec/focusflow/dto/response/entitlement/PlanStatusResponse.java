package io.ostec.focusflow.dto.response.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionPlan;
import io.ostec.focusflow.document.entitlement.SubscriptionState;

import java.time.Instant;
import java.util.List;

public record PlanStatusResponse(
    String userId,
    SubscriptionPlan plan,
    SubscriptionState state,
    Instant cycleStart,
    Instant cycleEnd,
    boolean active,
    List<String> entitlements,
    String lastWebhookEventId
) {
}
