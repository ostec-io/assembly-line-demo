package io.ostec.focusflow.mapper.insights;

import io.ostec.focusflow.document.insights.KpiSnapshotDocument;
import io.ostec.focusflow.document.insights.ProjectionLagDocument;
import io.ostec.focusflow.dto.response.insights.ConversionDashboardResponse;
import io.ostec.focusflow.dto.response.insights.ConversionDashboardResponse.KpiSeriesResponse;
import io.ostec.focusflow.dto.response.insights.ConversionDashboardResponse.ProjectionLagResponse;
import io.ostec.focusflow.dto.response.insights.KpiSnapshotResponse;
import io.ostec.focusflow.service.insights.InsightsQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InsightsMapper {

    public List<KpiSnapshotResponse> toKpiSnapshotResponses(List<KpiSnapshotDocument> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }

        return snapshots.stream()
            .map(this::toKpiSnapshotResponse)
            .toList();
    }

    public ConversionDashboardResponse toConversionDashboardResponse(
        InsightsQueryService.InsightsOverview overview,
        List<KpiSnapshotDocument> conversionSnapshots,
        List<KpiSnapshotDocument> dropoffSnapshots
    ) {
        return toDashboardResponse(
            "conversion",
            overview,
            "sessions.completion.rate",
            conversionSnapshots,
            "sessions.dropoff.rate",
            dropoffSnapshots
        );
    }

    public ConversionDashboardResponse toDropoffDashboardResponse(
        InsightsQueryService.InsightsOverview overview,
        List<KpiSnapshotDocument> dropoffSnapshots,
        List<KpiSnapshotDocument> conversionSnapshots
    ) {
        return toDashboardResponse(
            "dropoff",
            overview,
            "sessions.dropoff.rate",
            dropoffSnapshots,
            "sessions.completion.rate",
            conversionSnapshots
        );
    }

    public KpiSnapshotResponse toKpiSnapshotResponse(KpiSnapshotDocument snapshot) {
        return new KpiSnapshotResponse(
            snapshot != null ? snapshot.getId() : null,
            snapshot != null ? snapshot.getKpiKey() : null,
            snapshot != null ? snapshot.getWindowStart() : null,
            snapshot != null ? snapshot.getWindowEnd() : null,
            snapshot != null ? snapshot.getValue() : null,
            snapshot != null ? snapshot.getDimensions() : null,
            snapshot != null ? snapshot.getComputedAt() : null
        );
    }

    public List<ProjectionLagResponse> toProjectionLagResponses(List<ProjectionLagDocument> projectionLag) {
        if (projectionLag == null || projectionLag.isEmpty()) {
            return List.of();
        }

        return projectionLag.stream()
            .map(lag -> new ProjectionLagResponse(
                lag.getStream(),
                lag.getLastEventAt(),
                lag.getLagSeconds()
            ))
            .toList();
    }

    private ConversionDashboardResponse toDashboardResponse(
        String dashboard,
        InsightsQueryService.InsightsOverview overview,
        String primaryKpiKey,
        List<KpiSnapshotDocument> primarySnapshots,
        String comparisonKpiKey,
        List<KpiSnapshotDocument> comparisonSnapshots
    ) {
        List<KpiSnapshotResponse> primary = toKpiSnapshotResponses(primarySnapshots);
        List<KpiSnapshotResponse> comparison = toKpiSnapshotResponses(comparisonSnapshots);

        return new ConversionDashboardResponse(
            dashboard,
            overview != null ? overview.fromOccurredAt() : null,
            overview != null ? overview.toOccurredAt() : null,
            overview != null ? overview.ingestedEventCount() : 0,
            overview != null ? overview.uniqueEventTypeCount() : 0,
            overview != null ? overview.uniqueUserCount() : 0,
            toKpiSeriesResponse(primaryKpiKey, primary),
            toKpiSeriesResponse(comparisonKpiKey, comparison),
            toProjectionLagResponses(overview != null ? overview.projectionLag() : List.of())
        );
    }

    private KpiSeriesResponse toKpiSeriesResponse(String kpiKey, List<KpiSnapshotResponse> snapshots) {
        Double latestValue = snapshots != null && !snapshots.isEmpty()
            ? snapshots.get(0).value()
            : null;

        return new KpiSeriesResponse(kpiKey, latestValue, snapshots);
    }
}
