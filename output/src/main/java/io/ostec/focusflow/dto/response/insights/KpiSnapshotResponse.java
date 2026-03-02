package io.ostec.focusflow.dto.response.insights;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record KpiSnapshotResponse(
    String snapshotId,
    String kpiKey,
    Instant windowStart,
    Instant windowEnd,
    Double value,
    Map<String, String> dimensions,
    Instant computedAt
) {
    public KpiSnapshotResponse {
        dimensions = dimensions != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(dimensions))
            : Map.of();
    }
}
