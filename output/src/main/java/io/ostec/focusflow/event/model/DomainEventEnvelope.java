package io.ostec.focusflow.event.model;

import java.time.Instant;

public record DomainEventEnvelope(
    String eventId,
    String eventType,
    String eventVersion,
    Instant occurredAt,
    String producer,
    String correlationId,
    String causationId,
    String tenantId,
    String orgId,
    String aggregateType,
    String aggregateId,
    String payload
) {
}
