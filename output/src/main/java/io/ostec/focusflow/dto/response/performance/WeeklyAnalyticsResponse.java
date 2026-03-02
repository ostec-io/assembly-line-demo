package io.ostec.focusflow.dto.response.performance;

import io.ostec.focusflow.document.performance.DailyRecordState;

import java.time.LocalDate;
import java.util.List;

public record WeeklyAnalyticsResponse(
    String userId,
    LocalDate fromDate,
    LocalDate toDate,
    Integer scoredDays,
    Double averageFocusScore,
    Integer totalDeepWorkMinutes,
    Double averageCompletionRate,
    Double averageDistractionRate,
    StreakSummaryResponse streak,
    List<DailySnapshotResponse> dailySnapshots
) {
    public record DailySnapshotResponse(
        LocalDate date,
        Integer focusScore,
        Integer deepWorkMinutes,
        Double completionRate,
        Double distractionRate,
        DailyRecordState state
    ) {
    }
}
