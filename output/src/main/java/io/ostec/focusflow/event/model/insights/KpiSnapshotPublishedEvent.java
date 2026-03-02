package io.ostec.focusflow.event.model.insights;

import java.time.Instant;
import java.util.Map;

public record KpiSnapshotPublishedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String snapshotId,
    String kpiKey,
    Instant windowStart,
    Instant windowEnd,
    Double value,
    Map<String, String> dimensions
) {
}
