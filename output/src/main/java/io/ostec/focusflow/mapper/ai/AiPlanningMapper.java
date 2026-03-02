package io.ostec.focusflow.mapper.ai;

import io.ostec.focusflow.document.ai.PlanRevisionEmbedded;
import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import io.ostec.focusflow.document.ai.TaskBlockEmbedded;
import io.ostec.focusflow.dto.request.ai.TaskBlockRequest;
import io.ostec.focusflow.dto.response.ai.PlanVersionHistoryResponse;
import io.ostec.focusflow.dto.response.ai.SessionPlanResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AiPlanningMapper {

    public List<TaskBlockEmbedded> toTaskBlockEmbeddeds(List<TaskBlockRequest> taskBlocks) {
        if (taskBlocks == null) {
            return List.of();
        }

        return taskBlocks.stream().map(this::toTaskBlockEmbedded).toList();
    }

    public SessionPlanResponse toSessionPlanResponse(SessionPlanArtifactDocument plan) {
        List<SessionPlanResponse.TaskBlockResponse> taskBlocks = toTaskBlockResponses(plan.getTaskBlocks());

        return new SessionPlanResponse(
            plan.getPlanId(),
            plan.getSessionId(),
            plan.getStatus(),
            plan.getTemplateVersion(),
            plan.getProvider(),
            plan.getRevision(),
            plan.getAttachedVersion(),
            plan.getSessionDurationMinutes(),
            sumEstimatedMinutes(plan.getTaskBlocks()),
            plan.getDurationToleranceMinutes(),
            plan.getGeneratedAt(),
            plan.getAttachedAt(),
            plan.getCreatedAt(),
            plan.getUpdatedAt(),
            taskBlocks
        );
    }

    public PlanVersionHistoryResponse toPlanVersionHistoryResponse(SessionPlanArtifactDocument plan) {
        return new PlanVersionHistoryResponse(
            plan.getPlanId(),
            plan.getSessionId(),
            plan.getStatus(),
            plan.getRevision(),
            plan.getAttachedVersion(),
            plan.getGeneratedAt(),
            plan.getAttachedAt(),
            plan.getCreatedAt(),
            plan.getUpdatedAt(),
            toPlanRevisionResponses(plan)
        );
    }

    private TaskBlockEmbedded toTaskBlockEmbedded(TaskBlockRequest taskBlock) {
        return new TaskBlockEmbedded(
            taskBlock.blockId(),
            taskBlock.title(),
            taskBlock.details(),
            taskBlock.estimateMinutes(),
            taskBlock.sequence()
        );
    }

    private List<SessionPlanResponse.TaskBlockResponse> toTaskBlockResponses(List<TaskBlockEmbedded> taskBlocks) {
        if (taskBlocks == null || taskBlocks.isEmpty()) {
            return List.of();
        }

        return taskBlocks.stream()
            .filter(block -> block != null)
            .map(this::toTaskBlockResponse)
            .toList();
    }

    private SessionPlanResponse.TaskBlockResponse toTaskBlockResponse(TaskBlockEmbedded taskBlock) {
        return new SessionPlanResponse.TaskBlockResponse(
            taskBlock.getBlockId(),
            taskBlock.getTitle(),
            taskBlock.getDetails(),
            taskBlock.getEstimateMinutes(),
            taskBlock.getSequence()
        );
    }

    private List<PlanVersionHistoryResponse.PlanRevisionResponse> toPlanRevisionResponses(SessionPlanArtifactDocument plan) {
        if (plan.getRevisions() == null || plan.getRevisions().isEmpty()) {
            return List.of(new PlanVersionHistoryResponse.PlanRevisionResponse(
                plan.getRevision(),
                plan.getTemplateVersion(),
                plan.getProvider(),
                sumEstimatedMinutes(plan.getTaskBlocks()),
                plan.getUpdatedAt() != null ? plan.getUpdatedAt() : plan.getGeneratedAt(),
                toTaskBlockResponses(plan.getTaskBlocks())
            ));
        }

        return plan.getRevisions().stream()
            .filter(revision -> revision != null)
            .map(this::toPlanRevisionResponse)
            .toList();
    }

    private PlanVersionHistoryResponse.PlanRevisionResponse toPlanRevisionResponse(PlanRevisionEmbedded revision) {
        return new PlanVersionHistoryResponse.PlanRevisionResponse(
            revision.getRevision(),
            revision.getTemplateVersion(),
            revision.getProvider(),
            revision.getTotalEstimatedMinutes(),
            revision.getRevisedAt(),
            toTaskBlockResponses(revision.getTaskBlocks())
        );
    }

    private Integer sumEstimatedMinutes(List<TaskBlockEmbedded> taskBlocks) {
        if (taskBlocks == null || taskBlocks.isEmpty()) {
            return 0;
        }

        return taskBlocks.stream()
            .filter(block -> block != null && block.getEstimateMinutes() != null)
            .mapToInt(TaskBlockEmbedded::getEstimateMinutes)
            .sum();
    }
}
