package io.ostec.focusflow.service.performance;

import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.service.performance.PerformanceQueryService.DailyPerformanceView;
import io.ostec.focusflow.service.performance.PerformanceQueryService.WeeklyAnalytics;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PerformanceProjectionService {

    private final PerformanceQueryService performanceQueryService;
    private final Map<String, DashboardProjection> projectionCache = new ConcurrentHashMap<>();

    public PerformanceProjectionService(PerformanceQueryService performanceQueryService) {
        this.performanceQueryService = performanceQueryService;
    }

    public DashboardProjection updateDashboardProjection(LocalDate referenceDate, String sourceEventId) {
        DailyPerformanceView dailyPerformance = performanceQueryService.getDailyPerformance(referenceDate);
        WeeklyAnalytics weeklyAnalytics = performanceQueryService.getWeeklyAnalytics(referenceDate);

        DashboardProjection projection = new DashboardProjection(
            dailyPerformance.tenantId(),
            dailyPerformance.orgId(),
            dailyPerformance.userId(),
            dailyPerformance.date(),
            dailyPerformance.focusScore(),
            dailyPerformance.deepWorkMinutes(),
            dailyPerformance.completionRate(),
            dailyPerformance.distractionRate(),
            dailyPerformance.currentStreak(),
            dailyPerformance.longestStreak(),
            weeklyAnalytics.averageFocusScore(),
            weeklyAnalytics.totalDeepWorkMinutes(),
            weeklyAnalytics.averageCompletionRate(),
            weeklyAnalytics.averageDistractionRate(),
            sourceEventId,
            Instant.now()
        );

        projectionCache.put(cacheKey(projection.tenantId(), projection.orgId(), projection.userId()), projection);
        return projection;
    }

    public Optional<DashboardProjection> getCurrentDashboardProjection() {
        TenantScope scope = resolveScope();
        return Optional.ofNullable(projectionCache.get(cacheKey(scope.tenantId(), scope.orgId(), scope.userId())));
    }

    private String cacheKey(String tenantId, String orgId, String userId) {
        return tenantId + ":" + orgId + ":" + userId;
    }

    private TenantScope resolveScope() {
        try {
            return new TenantScope(
                TenantContextHolder.requireTenantId(),
                TenantContextHolder.requireOrgId(),
                TenantContextHolder.requireUserId()
            );
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    public record DashboardProjection(
        String tenantId,
        String orgId,
        String userId,
        LocalDate date,
        Integer focusScore,
        Integer deepWorkMinutes,
        Double completionRate,
        Double distractionRate,
        Integer currentStreak,
        Integer longestStreak,
        double weeklyAverageFocusScore,
        int weeklyDeepWorkMinutes,
        double weeklyAverageCompletionRate,
        double weeklyAverageDistractionRate,
        String sourceEventId,
        Instant projectedAt
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
