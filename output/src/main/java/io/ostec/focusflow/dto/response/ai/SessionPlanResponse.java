package io.ostec.focusflow.dto.response.ai;

import io.ostec.focusflow.document.ai.SessionPlanStatus;

import java.time.Instant;
import java.util.List;

public record SessionPlanResponse(
    String planId,
    String sessionId,
    SessionPlanStatus status,
    String templateVersion,
    String provider,
    Integer revision,
    Integer attachedVersion,
    Integer sessionDurationMinutes,
    Integer totalEstimatedMinutes,
    Integer durationToleranceMinutes,
    Instant generatedAt,
    Instant attachedAt,
    Instant createdAt,
    Instant updatedAt,
    List<TaskBlockResponse> taskBlocks
) {
    public record TaskBlockResponse(
        String blockId,
        String title,
        String details,
        Integer estimateMinutes,
        Integer sequence
    ) {
    }
}
