package io.ostec.focusflow.controller.insights;

import io.ostec.focusflow.document.insights.KpiSnapshotDocument;
import io.ostec.focusflow.dto.response.insights.ConversionDashboardResponse;
import io.ostec.focusflow.dto.response.insights.KpiSnapshotResponse;
import io.ostec.focusflow.mapper.insights.InsightsMapper;
import io.ostec.focusflow.service.insights.InsightsQueryService;
import io.ostec.focusflow.service.insights.InsightsQueryService.InsightsOverview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/insights")
@Tag(name = "Insights Queries", description = "Read endpoints for KPI, conversion, and drop-off projections")
public class InsightsQueryController {

    private static final String CONVERSION_RATE_KPI = "sessions.completion.rate";
    private static final String DROPOFF_RATE_KPI = "sessions.dropoff.rate";

    private final InsightsQueryService insightsQueryService;
    private final InsightsMapper insightsMapper;

    public InsightsQueryController(
        InsightsQueryService insightsQueryService,
        InsightsMapper insightsMapper
    ) {
        this.insightsQueryService = insightsQueryService;
        this.insightsMapper = insightsMapper;
    }

    @GetMapping("/kpis")
    @PreAuthorize("hasAuthority('SCOPE_insights.read')")
    @Operation(summary = "Get latest KPI snapshots")
    public List<KpiSnapshotResponse> getKpis(
        @RequestParam(required = false) String kpiKey,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(500)
        int limit
    ) {
        return insightsMapper.toKpiSnapshotResponses(insightsQueryService.findLatestKpiSnapshots(kpiKey, limit));
    }

    @GetMapping("/conversion")
    @PreAuthorize("hasAuthority('SCOPE_insights.read')")
    @Operation(summary = "Get conversion dashboard projection")
    public ConversionDashboardResponse getConversionDashboard(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant fromOccurredAt,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant toOccurredAt,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(100)
        int limit
    ) {
        InsightsOverview overview = insightsQueryService.getOverview(fromOccurredAt, toOccurredAt);
        List<KpiSnapshotDocument> conversionSnapshots = insightsQueryService.findLatestKpiSnapshots(CONVERSION_RATE_KPI, limit);
        List<KpiSnapshotDocument> dropoffSnapshots = insightsQueryService.findLatestKpiSnapshots(DROPOFF_RATE_KPI, limit);
        return insightsMapper.toConversionDashboardResponse(overview, conversionSnapshots, dropoffSnapshots);
    }

    @GetMapping("/dropoff")
    @PreAuthorize("hasAuthority('SCOPE_insights.read')")
    @Operation(summary = "Get drop-off dashboard projection")
    public ConversionDashboardResponse getDropoffDashboard(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant fromOccurredAt,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        Instant toOccurredAt,
        @RequestParam(defaultValue = "20")
        @Min(1)
        @Max(100)
        int limit
    ) {
        InsightsOverview overview = insightsQueryService.getOverview(fromOccurredAt, toOccurredAt);
        List<KpiSnapshotDocument> dropoffSnapshots = insightsQueryService.findLatestKpiSnapshots(DROPOFF_RATE_KPI, limit);
        List<KpiSnapshotDocument> conversionSnapshots = insightsQueryService.findLatestKpiSnapshots(CONVERSION_RATE_KPI, limit);
        return insightsMapper.toDropoffDashboardResponse(overview, dropoffSnapshots, conversionSnapshots);
    }
}
