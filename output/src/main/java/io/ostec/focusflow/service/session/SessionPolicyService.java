package io.ostec.focusflow.service.session;

import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.document.session.SessionPolicyAuditDocument;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.focusflow.repository.session.SessionPolicyAuditRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class SessionPolicyService {

    private static final int MIN_DURATION_MINUTES = 15;
    private static final int MAX_DURATION_MINUTES = 180;
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private final FocusSessionRepository focusSessionRepository;
    private final SessionPolicyAuditRepository sessionPolicyAuditRepository;

    public SessionPolicyService(
        FocusSessionRepository focusSessionRepository,
        SessionPolicyAuditRepository sessionPolicyAuditRepository
    ) {
        this.focusSessionRepository = focusSessionRepository;
        this.sessionPolicyAuditRepository = sessionPolicyAuditRepository;
    }

    public void validateSessionCreation(String goal, Integer durationMinutes) {
        TenantScope scope = resolveScope();

        if (!StringUtils.hasText(goal)) {
            auditDecision(scope, null, "SessionCreation", DENY, "Goal is required", null);
            throw new IllegalStateException("Session goal must not be blank");
        }

        if (durationMinutes == null || durationMinutes < MIN_DURATION_MINUTES || durationMinutes > MAX_DURATION_MINUTES) {
            auditDecision(scope, null, "SessionCreation", DENY, "Invalid duration", "durationMinutes=" + durationMinutes);
            throw new IllegalStateException(ApiErrorCode.INVALID_DURATION.name());
        }

        if (focusSessionRepository.findFirstByTenantIdAndOrgIdAndUserIdAndStatus(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            FocusSessionStatus.ACTIVE
        ).isPresent()) {
            auditDecision(scope, null, "SessionCreation", DENY, "Active session already exists", null);
            throw new IllegalStateException(ApiErrorCode.ACTIVE_SESSION_EXISTS.name());
        }

        enforceEntitlement(scope);
        enforceUsageLimit(scope);
        auditDecision(scope, null, "SessionCreation", ALLOW, "Validated", null);
    }

    public void validateSessionStart(FocusSessionDocument session) {
        TenantScope scope = resolveScope();
        ensureSessionInScope(scope, session);

        if (session.getStatus() == FocusSessionStatus.ACTIVE) {
            auditDecision(scope, session.getId(), "SessionStart", ALLOW, "Already active", null);
            return;
        }

        if (session.getStatus() != FocusSessionStatus.CREATED) {
            auditDecision(scope, session.getId(), "SessionStart", DENY, "Only CREATED sessions can start", null);
            throw new IllegalStateException("Session cannot be started from status " + session.getStatus());
        }

        Optional<FocusSessionDocument> existingActive = focusSessionRepository.findFirstByTenantIdAndOrgIdAndUserIdAndStatus(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            FocusSessionStatus.ACTIVE
        );
        if (existingActive.isPresent() && !existingActive.get().getId().equals(session.getId())) {
            auditDecision(scope, session.getId(), "SessionStart", DENY, "Another active session already exists", null);
            throw new IllegalStateException(ApiErrorCode.ACTIVE_SESSION_EXISTS.name());
        }

        if (!StringUtils.hasText(session.getPlanId())) {
            auditDecision(scope, session.getId(), "SessionStart", DENY, "Plan is required before start", null);
            throw new IllegalStateException("Session plan must be attached before start");
        }

        enforceEntitlement(scope);
        enforceUsageLimit(scope);
        auditDecision(scope, session.getId(), "SessionStart", ALLOW, "Validated", null);
    }

    public CompletionEligibility assessCompletionEligibility(FocusSessionDocument session, String summary) {
        TenantScope scope = resolveScope();
        ensureSessionInScope(scope, session);

        if (!StringUtils.hasText(summary)) {
            CompletionEligibility eligibility = new CompletionEligibility(false, 0L, requiredMinutes(session), "Summary is required");
            auditCompletion(scope, session.getId(), eligibility);
            return eligibility;
        }

        if (session.getStatus() != FocusSessionStatus.ACTIVE && session.getStatus() != FocusSessionStatus.COMPLETED) {
            CompletionEligibility eligibility = new CompletionEligibility(
                false,
                0L,
                requiredMinutes(session),
                "Session must be ACTIVE before completion"
            );
            auditCompletion(scope, session.getId(), eligibility);
            return eligibility;
        }

        if (session.getStartedAt() == null) {
            CompletionEligibility eligibility = new CompletionEligibility(false, 0L, requiredMinutes(session), "Session start time is missing");
            auditCompletion(scope, session.getId(), eligibility);
            return eligibility;
        }

        long elapsedMinutes = Duration.between(session.getStartedAt(), Instant.now()).toMinutes();
        long requiredMinutes = requiredMinutes(session);
        boolean eligible = elapsedMinutes >= requiredMinutes;

        CompletionEligibility eligibility = new CompletionEligibility(
            eligible,
            Math.max(elapsedMinutes, 0L),
            requiredMinutes,
            eligible ? "Eligible" : "Required timer has not elapsed"
        );

        auditCompletion(scope, session.getId(), eligibility);
        return eligibility;
    }

    public void upsertUserPolicyState(String policyName, String userId, String decision, String reason, String details) {
        TenantScope scope = resolveScope();
        String effectiveUserId = StringUtils.hasText(userId) ? userId : scope.userId();
        String id = policyStateId(policyName, effectiveUserId);

        SessionPolicyAuditDocument document = sessionPolicyAuditRepository
            .findByIdAndTenantIdAndOrgId(id, scope.tenantId(), scope.orgId())
            .orElseGet(SessionPolicyAuditDocument::new);

        document.setId(id);
        document.setTenantId(scope.tenantId());
        document.setOrgId(scope.orgId());
        document.setUserId(effectiveUserId);
        document.setSessionId("POLICY_STATE");
        document.setPolicyName(policyName);
        document.setDecision(StringUtils.hasText(decision) ? decision : DENY);
        document.setReason(reason);
        document.setDetails(details);
        document.setEvaluatedAt(Instant.now());

        sessionPolicyAuditRepository.save(document);
    }

    public record CompletionEligibility(
        boolean eligible,
        long elapsedMinutes,
        long requiredMinutes,
        String reason
    ) {
    }

    private void enforceEntitlement(TenantScope scope) {
        Optional<SessionPolicyAuditDocument> entitlementPolicy = sessionPolicyAuditRepository
            .findByIdAndTenantIdAndOrgId(policyStateId("entitlement", scope.userId()), scope.tenantId(), scope.orgId());

        if (entitlementPolicy.isPresent() && !ALLOW.equalsIgnoreCase(entitlementPolicy.get().getDecision())) {
            auditDecision(scope, null, "Entitlement", DENY, "Entitlement denied", entitlementPolicy.get().getReason());
            throw new IllegalStateException(ApiErrorCode.ENTITLEMENT_DENIED.name());
        }
    }

    private void enforceUsageLimit(TenantScope scope) {
        Optional<SessionPolicyAuditDocument> usageLimitPolicy = sessionPolicyAuditRepository
            .findByIdAndTenantIdAndOrgId(policyStateId("usage-limit", scope.userId()), scope.tenantId(), scope.orgId());

        if (usageLimitPolicy.isPresent() && "BLOCK".equalsIgnoreCase(usageLimitPolicy.get().getDecision())) {
            auditDecision(scope, null, "UsageLimit", DENY, "Usage limit reached", usageLimitPolicy.get().getReason());
            throw new IllegalStateException("Usage limit reached for current user");
        }
    }

    private void ensureSessionInScope(TenantScope scope, FocusSessionDocument session) {
        if (session == null) {
            throw new IllegalStateException("Session is required");
        }

        if (!scope.tenantId().equals(session.getTenantId()) || !scope.orgId().equals(session.getOrgId())) {
            throw new IllegalStateException("Session does not belong to current tenant/org scope");
        }

        if (!scope.userId().equals(session.getUserId())) {
            throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
        }
    }

    private long requiredMinutes(FocusSessionDocument session) {
        Integer durationMinutes = session.getDurationMinutes();
        if (durationMinutes == null || durationMinutes < MIN_DURATION_MINUTES || durationMinutes > MAX_DURATION_MINUTES) {
            return MIN_DURATION_MINUTES;
        }
        return durationMinutes.longValue();
    }

    private void auditCompletion(TenantScope scope, String sessionId, CompletionEligibility eligibility) {
        auditDecision(
            scope,
            sessionId,
            "CompletionEligibility",
            eligibility.eligible() ? ALLOW : DENY,
            eligibility.reason(),
            "elapsedMinutes=" + eligibility.elapsedMinutes() + ",requiredMinutes=" + eligibility.requiredMinutes()
        );
    }

    private void auditDecision(
        TenantScope scope,
        String sessionId,
        String policyName,
        String decision,
        String reason,
        String details
    ) {
        SessionPolicyAuditDocument auditDocument = new SessionPolicyAuditDocument();
        auditDocument.setTenantId(scope.tenantId());
        auditDocument.setOrgId(scope.orgId());
        auditDocument.setUserId(scope.userId());
        auditDocument.setSessionId(StringUtils.hasText(sessionId) ? sessionId : "N/A");
        auditDocument.setPolicyName(policyName);
        auditDocument.setDecision(decision);
        auditDocument.setReason(reason);
        auditDocument.setDetails(details);
        auditDocument.setEvaluatedAt(Instant.now());
        sessionPolicyAuditRepository.save(auditDocument);
    }

    private String policyStateId(String policyName, String userId) {
        return "policy:" + policyName + ":" + userId;
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
