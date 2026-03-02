package io.ostec.focusflow.controller.performance;

import io.ostec.focusflow.dto.response.performance.DailyPerformanceResponse;
import io.ostec.focusflow.dto.response.performance.WeeklyAnalyticsResponse;
import io.ostec.focusflow.mapper.performance.PerformanceMapper;
import io.ostec.focusflow.service.performance.PerformanceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@Validated
@RequestMapping("/api/v1/performance")
@Tag(name = "Performance Queries", description = "Read endpoints for personal daily and weekly analytics")
public class PerformanceQueryController {

    private final PerformanceQueryService performanceQueryService;
    private final PerformanceMapper performanceMapper;

    public PerformanceQueryController(
        PerformanceQueryService performanceQueryService,
        PerformanceMapper performanceMapper
    ) {
        this.performanceQueryService = performanceQueryService;
        this.performanceMapper = performanceMapper;
    }

    @GetMapping("/me/daily")
    @PreAuthorize("hasAuthority('SCOPE_performance.read')")
    @Operation(summary = "Get current user's daily performance view")
    public DailyPerformanceResponse getMyDailyPerformance(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date
    ) {
        return performanceMapper.toDailyPerformanceResponse(performanceQueryService.getDailyPerformance(date));
    }

    @GetMapping("/me/weekly")
    @PreAuthorize("hasAuthority('SCOPE_performance.read')")
    @Operation(summary = "Get current user's weekly performance analytics")
    public WeeklyAnalyticsResponse getMyWeeklyAnalytics(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate anchorDate
    ) {
        PerformanceQueryService.WeeklyAnalytics analytics = performanceQueryService.getWeeklyAnalytics(anchorDate);
        PerformanceQueryService.DailyPerformanceView dailyPerformance = performanceQueryService.getDailyPerformance(anchorDate);
        return performanceMapper.toWeeklyAnalyticsResponse(analytics, dailyPerformance.lastQualifiedDate());
    }
}
