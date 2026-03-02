package io.ostec.focusflow.controller.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionAccountDocument;
import io.ostec.focusflow.dto.request.entitlement.ConsumeUsageQuotaRequest;
import io.ostec.focusflow.dto.request.entitlement.EntitlementCheckRequest;
import io.ostec.focusflow.dto.response.entitlement.EntitlementDecisionResponse;
import io.ostec.focusflow.dto.response.entitlement.PlanStatusResponse;
import io.ostec.focusflow.mapper.entitlement.EntitlementMapper;
import io.ostec.focusflow.repository.entitlement.SubscriptionAccountRepository;
import io.ostec.focusflow.repository.entitlement.UsageCounterRepository;
import io.ostec.focusflow.service.entitlement.EntitlementDecisionService;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@RestController
@Validated
@RequestMapping("/api/v1/entitlements")
@Tag(name = "Entitlements", description = "Internal entitlement checks, usage consumption, and plan status")
public class EntitlementController {

    private static final int DEFAULT_DAILY_LIMIT = 25;
    private static final Map<String, Integer> FEATURE_DAILY_LIMITS = Map.of(
        "ai.plan-memory", 20,
        "accountability.private-room", 10,
        "focus.deep-work.analytics", 50
    );

    private final EntitlementDecisionService entitlementDecisionService;
    private final SubscriptionAccountRepository subscriptionAccountRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final EntitlementMapper entitlementMapper;

    public EntitlementController(
        EntitlementDecisionService entitlementDecisionService,
        SubscriptionAccountRepository subscriptionAccountRepository,
        UsageCounterRepository usageCounterRepository,
        EntitlementMapper entitlementMapper
    ) {
        this.entitlementDecisionService = entitlementDecisionService;
        this.subscriptionAccountRepository = subscriptionAccountRepository;
        this.usageCounterRepository = usageCounterRepository;
        this.entitlementMapper = entitlementMapper;
    }

    @PostMapping("/check")
    @PreAuthorize("hasAuthority('SCOPE_internal.entitlements')")
    @Operation(summary = "Check entitlement and daily quota availability")
    public EntitlementDecisionResponse check(@Valid @RequestBody EntitlementCheckRequest request) {
        boolean featureAllowed = entitlementDecisionService.checkFeatureAccess(
            request.featureKey(),
            request.action(),
            request.resourceId()
        );

        if (!featureAllowed) {
            return entitlementMapper.toEntitlementDecisionResponse(false, "ENTITLEMENT_DENIED", 0);
        }

        boolean usageAllowed = entitlementDecisionService.checkDailyUsage(request.featureKey());
        Integer remaining = resolveRemainingQuota(request.featureKey());
        if (!usageAllowed) {
            return entitlementMapper.toEntitlementDecisionResponse(false, "USAGE_QUOTA_EXHAUSTED", remaining);
        }

        return entitlementMapper.toEntitlementDecisionResponse(true, "ALLOWED", remaining);
    }

    @PostMapping("/consume")
    @PreAuthorize("hasAuthority('SCOPE_internal.entitlements')")
    @Operation(summary = "Consume usage quota for an entitled feature")
    public EntitlementDecisionResponse consume(@Valid @RequestBody ConsumeUsageQuotaRequest request) {
        boolean consumed = entitlementDecisionService.grantUsage(request.featureKey(), request.idempotencyKey());
        Integer remaining = resolveRemainingQuota(request.featureKey());

        if (!consumed) {
            boolean featureAllowed = entitlementDecisionService.checkFeatureAccess(
                request.featureKey(),
                "consume",
                null
            );

            if (!featureAllowed) {
                return entitlementMapper.toEntitlementDecisionResponse(false, "ENTITLEMENT_DENIED", 0);
            }

            return entitlementMapper.toEntitlementDecisionResponse(false, "USAGE_QUOTA_EXHAUSTED", remaining);
        }

        return entitlementMapper.toEntitlementDecisionResponse(true, "CONSUMED", remaining);
    }

    @GetMapping("/me/status")
    @PreAuthorize("hasAuthority('SCOPE_billing.read')")
    @Operation(summary = "Get current user's plan status")
    public PlanStatusResponse getMyPlanStatus() {
        TenantScope scope = resolveScope();
        Optional<SubscriptionAccountDocument> account = subscriptionAccountRepository.findByTenantIdAndOrgIdAndUserId(
            scope.tenantId(),
            scope.orgId(),
            scope.userId()
        );
        return account
            .map(entitlementMapper::toPlanStatusResponse)
            .orElseGet(() -> entitlementMapper.toDefaultPlanStatusResponse(scope.userId()));
    }

    private Integer resolveRemainingQuota(String featureKey) {
        try {
            TenantScope scope = resolveScope();
            int limit = resolveDailyLimit(featureKey);
            int usageCount = usageCounterRepository
                .findByTenantIdAndOrgIdAndUserIdAndFeatureKeyAndUsageDate(
                    scope.tenantId(),
                    scope.orgId(),
                    scope.userId(),
                    featureKey.trim(),
                    LocalDate.now()
                )
                .map(counter -> counter.getUsageCount() != null ? counter.getUsageCount() : 0)
                .orElse(0);
            return Math.max(limit - usageCount, 0);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int resolveDailyLimit(String featureKey) {
        return FEATURE_DAILY_LIMITS.getOrDefault(featureKey.trim().toLowerCase(), DEFAULT_DAILY_LIMIT);
    }

    private TenantScope resolveScope() {
        return new TenantScope(
            TenantContextHolder.requireTenantId(),
            TenantContextHolder.requireOrgId(),
            TenantContextHolder.requireUserId()
        );
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
