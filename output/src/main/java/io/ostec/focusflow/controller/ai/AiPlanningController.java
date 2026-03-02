package io.ostec.focusflow.controller.ai;

import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import io.ostec.focusflow.dto.request.ai.AttachGeneratedPlanRequest;
import io.ostec.focusflow.dto.request.ai.EditSessionPlanRequest;
import io.ostec.focusflow.dto.request.ai.GenerateSessionPlanRequest;
import io.ostec.focusflow.dto.request.ai.UpdatePlanLearningMemoryRequest;
import io.ostec.focusflow.dto.response.ai.PlanVersionHistoryResponse;
import io.ostec.focusflow.dto.response.ai.SessionPlanResponse;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.mapper.ai.AiPlanningMapper;
import io.ostec.focusflow.repository.ai.SessionPlanRepository;
import io.ostec.focusflow.service.ai.PlanAttachmentService;
import io.ostec.focusflow.service.ai.PlanGenerationService;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/ai/plans")
@Tag(name = "AI Planning", description = "AI plan generation, editing, attachment, and history")
public class AiPlanningController {

    private final PlanGenerationService planGenerationService;
    private final PlanAttachmentService planAttachmentService;
    private final SessionPlanRepository sessionPlanRepository;
    private final AiPlanningMapper aiPlanningMapper;

    public AiPlanningController(
        PlanGenerationService planGenerationService,
        PlanAttachmentService planAttachmentService,
        SessionPlanRepository sessionPlanRepository,
        AiPlanningMapper aiPlanningMapper
    ) {
        this.planGenerationService = planGenerationService;
        this.planAttachmentService = planAttachmentService;
        this.sessionPlanRepository = sessionPlanRepository;
        this.aiPlanningMapper = aiPlanningMapper;
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('SCOPE_ai.write')")
    @Operation(summary = "Generate an AI session plan")
    public SessionPlanResponse generate(@Valid @RequestBody GenerateSessionPlanRequest request) {
        return aiPlanningMapper.toSessionPlanResponse(
            planGenerationService.generatePlan(request.sessionId(), request.goal(), request.durationMinutes())
        );
    }

    @PostMapping("/{planId}/edit")
    @PreAuthorize("hasAuthority('SCOPE_ai.write')")
    @Operation(summary = "Edit an AI session plan")
    public SessionPlanResponse edit(
        @PathVariable String planId,
        @Valid @RequestBody EditSessionPlanRequest request
    ) {
        return aiPlanningMapper.toSessionPlanResponse(
            planGenerationService.applyUserEdits(
                planId,
                aiPlanningMapper.toTaskBlockEmbeddeds(request.taskBlocks()),
                request.expectedRevision()
            )
        );
    }

    @PostMapping("/{planId}/attach")
    @PreAuthorize("hasAuthority('SCOPE_ai.write')")
    @Operation(summary = "Attach a generated AI plan to a session")
    public SessionPlanResponse attach(
        @PathVariable String planId,
        @Valid @RequestBody AttachGeneratedPlanRequest request
    ) {
        return aiPlanningMapper.toSessionPlanResponse(
            planAttachmentService.attachLatestPlan(planId, request.sessionId(), request.revision())
        );
    }

    @PostMapping("/memory")
    @PreAuthorize("hasAuthority('SCOPE_ai.write')")
    @Operation(summary = "Update plan learning memory")
    public ResponseEntity<Void> updateMemory(@Valid @RequestBody UpdatePlanLearningMemoryRequest request) {
        planGenerationService.updatePlanMemory(request.feedback(), request.outcome());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{planId}")
    @PreAuthorize("hasAuthority('SCOPE_ai.read')")
    @Operation(summary = "Get AI plan version history")
    public PlanVersionHistoryResponse getPlan(@PathVariable String planId) {
        TenantScope scope = resolveScope();
        SessionPlanArtifactDocument plan = loadOwnedPlan(planId, scope);
        return aiPlanningMapper.toPlanVersionHistoryResponse(plan);
    }

    private SessionPlanArtifactDocument loadOwnedPlan(String planId, TenantScope scope) {
        SessionPlanArtifactDocument plan = sessionPlanRepository
            .findByIdAndTenantIdAndOrgId(planId, scope.tenantId(), scope.orgId())
            .orElseThrow(() -> new IllegalStateException("Plan not found for current tenant/org scope"));

        if (!scope.userId().equals(plan.getUserId())) {
            throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
        }

        return plan;
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

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
