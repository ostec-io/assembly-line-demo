package io.ostec.focusflow.event.model.performance;

import io.ostec.focusflow.document.performance.DailyRecordState;

import java.time.Instant;
import java.time.LocalDate;

public record DailyRecordFinalizedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    LocalDate date,
    Integer focusScore,
    Integer deepWorkMinutes,
    Double completionRate,
    Double distractionRate,
    DailyRecordState state,
    String sourceEventId
) {
}
