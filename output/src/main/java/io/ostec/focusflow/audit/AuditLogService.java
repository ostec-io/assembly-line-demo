package io.ostec.focusflow.audit;

import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    public void logEntitlementGranted(
        String tenantId,
        String orgId,
        String userId,
        String entitlementKey,
        String reason,
        String sourceEventId,
        String correlationId
    ) {
        log.info(
            "audit.action=ENTITLEMENT_GRANTED tenantId={} orgId={} userId={} entitlement={} reason={} sourceEventId={} correlationId={}",
            tenantId,
            orgId,
            userId,
            entitlementKey,
            normalizeReason(reason, "Entitlement granted"),
            sourceEventId,
            resolveCorrelationId(correlationId, sourceEventId)
        );
    }

    public void logEntitlementRevoked(
        String tenantId,
        String orgId,
        String userId,
        String entitlementKey,
        String reason,
        String sourceEventId,
        String correlationId
    ) {
        log.info(
            "audit.action=ENTITLEMENT_REVOKED tenantId={} orgId={} userId={} entitlement={} reason={} sourceEventId={} correlationId={}",
            tenantId,
            orgId,
            userId,
            entitlementKey,
            normalizeReason(reason, "Entitlement revoked"),
            sourceEventId,
            resolveCorrelationId(correlationId, sourceEventId)
        );
    }

    public void logBillingTransition(
        String tenantId,
        String orgId,
        String userId,
        String fromPlan,
        String fromState,
        String toPlan,
        String toState,
        String reason,
        String sourceEventId,
        String correlationId
    ) {
        log.info(
            "audit.action=BILLING_TRANSITION tenantId={} orgId={} userId={} fromPlan={} fromState={} toPlan={} toState={} reason={} sourceEventId={} correlationId={}",
            tenantId,
            orgId,
            userId,
            fromPlan,
            fromState,
            toPlan,
            toState,
            normalizeReason(reason, "Subscription transition"),
            sourceEventId,
            resolveCorrelationId(correlationId, sourceEventId)
        );
    }

    public void logModerationAction(
        String tenantId,
        String orgId,
        String roomId,
        String actorUserId,
        String action,
        String reason,
        String correlationId
    ) {
        log.info(
            "audit.action=MODERATION tenantId={} orgId={} roomId={} actorUserId={} moderationAction={} reason={} correlationId={}",
            tenantId,
            orgId,
            roomId,
            actorUserId,
            action,
            normalizeReason(reason, "Moderation action"),
            resolveCorrelationId(correlationId, null)
        );
    }

    public void logScoreCorrection(
        String tenantId,
        String orgId,
        String userId,
        LocalDate scoreDate,
        int previousScore,
        int correctedScore,
        String reason,
        String correctedByUserId,
        String sourceEventId,
        String correlationId
    ) {
        log.info(
            "audit.action=SCORE_CORRECTION tenantId={} orgId={} userId={} scoreDate={} previousScore={} correctedScore={} correctedByUserId={} reason={} sourceEventId={} correlationId={}",
            tenantId,
            orgId,
            userId,
            scoreDate,
            previousScore,
            correctedScore,
            correctedByUserId,
            normalizeReason(reason, "Score correction"),
            sourceEventId,
            resolveCorrelationId(correlationId, sourceEventId)
        );
    }

    private String resolveCorrelationId(String correlationId, String fallbackSourceEventId) {
        if (StringUtils.hasText(correlationId)) {
            return correlationId.trim();
        }

        String fromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(fromContext)) {
            return fromContext.trim();
        }

        if (StringUtils.hasText(fallbackSourceEventId)) {
            return fallbackSourceEventId.trim();
        }

        return UUID.randomUUID().toString();
    }

    private String normalizeReason(String reason, String fallback) {
        if (StringUtils.hasText(reason)) {
            return reason.trim();
        }
        return fallback;
    }
}
