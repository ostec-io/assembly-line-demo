package io.ostec.focusflow.mapper.performance;

import io.ostec.focusflow.document.performance.DailyPerformanceRecordDocument;
import io.ostec.focusflow.document.performance.DailyRecordState;
import io.ostec.focusflow.document.performance.StreakLedgerDocument;
import io.ostec.focusflow.dto.response.performance.DailyPerformanceResponse;
import io.ostec.focusflow.dto.response.performance.StreakSummaryResponse;
import io.ostec.focusflow.dto.response.performance.WeeklyAnalyticsResponse;
import io.ostec.focusflow.service.performance.PerformanceQueryService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class PerformanceMapper {

    public DailyPerformanceResponse toDailyPerformanceResponse(PerformanceQueryService.DailyPerformanceView view) {
        return new DailyPerformanceResponse(
            view.userId(),
            view.date(),
            safeInt(view.focusScore()),
            safeInt(view.deepWorkMinutes()),
            normalizeRate(view.completionRate()),
            normalizeRate(view.distractionRate()),
            view.state() != null ? view.state() : DailyRecordState.ACCUMULATING,
            toStreakSummaryResponse(view.currentStreak(), view.longestStreak(), view.lastQualifiedDate())
        );
    }

    public DailyPerformanceResponse toDailyPerformanceResponse(
        DailyPerformanceRecordDocument dailyRecord,
        StreakLedgerDocument streakLedger
    ) {
        return new DailyPerformanceResponse(
            dailyRecord.getUserId(),
            dailyRecord.getDate(),
            safeInt(dailyRecord.getFocusScore()),
            safeInt(dailyRecord.getDeepWorkMinutes()),
            normalizeRate(dailyRecord.getCompletionRate()),
            normalizeRate(dailyRecord.getDistractionRate()),
            dailyRecord.getState() != null ? dailyRecord.getState() : DailyRecordState.ACCUMULATING,
            toStreakSummaryResponse(
                streakLedger != null ? streakLedger.getCurrentStreak() : 0,
                streakLedger != null ? streakLedger.getLongestStreak() : 0,
                streakLedger != null ? streakLedger.getLastQualifiedDate() : null
            )
        );
    }

    public WeeklyAnalyticsResponse toWeeklyAnalyticsResponse(
        PerformanceQueryService.WeeklyAnalytics analytics,
        LocalDate lastQualifiedDate
    ) {
        List<WeeklyAnalyticsResponse.DailySnapshotResponse> snapshots = analytics.dailySnapshots().stream()
            .map(snapshot -> new WeeklyAnalyticsResponse.DailySnapshotResponse(
                snapshot.date(),
                snapshot.focusScore(),
                snapshot.deepWorkMinutes(),
                normalizeRate(snapshot.completionRate()),
                normalizeRate(snapshot.distractionRate()),
                snapshot.state() != null ? snapshot.state() : DailyRecordState.ACCUMULATING
            ))
            .toList();

        return new WeeklyAnalyticsResponse(
            analytics.userId(),
            analytics.fromDate(),
            analytics.toDate(),
            analytics.scoredDays(),
            analytics.averageFocusScore(),
            analytics.totalDeepWorkMinutes(),
            normalizeRate(analytics.averageCompletionRate()),
            normalizeRate(analytics.averageDistractionRate()),
            toStreakSummaryResponse(analytics.currentStreak(), analytics.longestStreak(), lastQualifiedDate),
            snapshots
        );
    }

    public StreakSummaryResponse toStreakSummaryResponse(Integer currentStreak, Integer longestStreak, LocalDate lastQualifiedDate) {
        return new StreakSummaryResponse(safeInt(currentStreak), safeInt(longestStreak), lastQualifiedDate);
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private double normalizeRate(Double value) {
        if (value == null || Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(value, 1.0));
    }
}
