package io.ostec.focusflow.dto.response.performance;

import io.ostec.focusflow.document.performance.DailyRecordState;

import java.time.LocalDate;

public record DailyPerformanceResponse(
    String userId,
    LocalDate date,
    Integer focusScore,
    Integer deepWorkMinutes,
    Double completionRate,
    Double distractionRate,
    DailyRecordState state,
    StreakSummaryResponse streak
) {
}
