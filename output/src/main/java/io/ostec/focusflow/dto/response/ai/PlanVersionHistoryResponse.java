package io.ostec.focusflow.dto.response.ai;

import io.ostec.focusflow.document.ai.SessionPlanStatus;

import java.time.Instant;
import java.util.List;

public record PlanVersionHistoryResponse(
    String planId,
    String sessionId,
    SessionPlanStatus status,
    Integer currentRevision,
    Integer attachedVersion,
    Instant generatedAt,
    Instant attachedAt,
    Instant createdAt,
    Instant updatedAt,
    List<PlanRevisionResponse> revisions
) {
    public record PlanRevisionResponse(
        Integer revision,
        String templateVersion,
        String provider,
        Integer totalEstimatedMinutes,
        Instant revisedAt,
        List<SessionPlanResponse.TaskBlockResponse> taskBlocks
    ) {
    }
}
