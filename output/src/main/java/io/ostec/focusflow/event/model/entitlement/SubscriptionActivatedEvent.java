package io.ostec.focusflow.event.model.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionPlan;

import java.time.Instant;

public record SubscriptionActivatedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    SubscriptionPlan plan,
    Instant cycleStart,
    Instant cycleEnd,
    String sourceEventId
) {
}
