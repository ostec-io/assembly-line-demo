package io.ostec.focusflow.dto.response.performance;

import java.time.LocalDate;

public record StreakSummaryResponse(
    Integer currentStreak,
    Integer longestStreak,
    LocalDate lastQualifiedDate
) {
}
