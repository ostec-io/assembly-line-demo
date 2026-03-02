package io.ostec.focusflow.event.model.performance;

import io.ostec.focusflow.document.performance.DailyRecordState;

import java.time.Instant;
import java.time.LocalDate;

public record DailyScoreCalculatedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    LocalDate date,
    String sourceEventId,
    String sourceEventType,
    Integer focusScore,
    Integer deepWorkMinutes,
    Double completionRate,
    Double distractionRate,
    String formulaVersion,
    DailyRecordState state
) {
}
