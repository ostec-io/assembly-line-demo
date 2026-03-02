package io.ostec.focusflow.event.model.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionPlan;
import io.ostec.focusflow.document.entitlement.SubscriptionState;

import java.time.Instant;

public record SubscriptionCanceledEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    SubscriptionPlan plan,
    SubscriptionState state,
    String reason,
    String sourceEventId
) {
}
