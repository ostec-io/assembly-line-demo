package io.ostec.focusflow.dto.response.insights;

import java.time.Instant;
import java.util.List;

public record ConversionDashboardResponse(
    String dashboard,
    Instant fromOccurredAt,
    Instant toOccurredAt,
    Integer ingestedEventCount,
    Integer uniqueEventTypeCount,
    Integer uniqueUserCount,
    KpiSeriesResponse primarySeries,
    KpiSeriesResponse comparisonSeries,
    List<ProjectionLagResponse> projectionLag
) {
    public ConversionDashboardResponse {
        projectionLag = projectionLag != null ? List.copyOf(projectionLag) : List.of();
    }

    public record KpiSeriesResponse(
        String kpiKey,
        Double latestValue,
        List<KpiSnapshotResponse> snapshots
    ) {
        public KpiSeriesResponse {
            snapshots = snapshots != null ? List.copyOf(snapshots) : List.of();
        }
    }

    public record ProjectionLagResponse(
        String stream,
        Instant lastEventAt,
        Long lagSeconds
    ) {
    }
}
