package io.ostec.focusflow.mapper.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionAccountDocument;
import io.ostec.focusflow.document.entitlement.SubscriptionPlan;
import io.ostec.focusflow.document.entitlement.SubscriptionState;
import io.ostec.focusflow.dto.response.entitlement.EntitlementDecisionResponse;
import io.ostec.focusflow.dto.response.entitlement.PlanStatusResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Component
public class EntitlementMapper {

    public EntitlementDecisionResponse toEntitlementDecisionResponse(
        boolean allowed,
        String reasonCode,
        Integer remaining
    ) {
        String normalizedReasonCode = StringUtils.hasText(reasonCode)
            ? reasonCode.trim()
            : allowed ? "ALLOWED" : "DENIED";
        Integer normalizedRemaining = remaining != null ? Math.max(remaining, 0) : null;
        return new EntitlementDecisionResponse(allowed, normalizedReasonCode, normalizedRemaining);
    }

    public PlanStatusResponse toPlanStatusResponse(SubscriptionAccountDocument account) {
        SubscriptionPlan plan = account.getPlan() != null ? account.getPlan() : SubscriptionPlan.FREE;
        SubscriptionState state = account.getState() != null ? account.getState() : SubscriptionState.EXPIRED;
        return new PlanStatusResponse(
            account.getUserId(),
            plan,
            state,
            account.getCycleStart(),
            account.getCycleEnd(),
            isActive(plan, state, account.getCycleStart(), account.getCycleEnd()),
            account.getEntitlements() != null ? List.copyOf(account.getEntitlements()) : List.of(),
            account.getLastWebhookEventId()
        );
    }

    public PlanStatusResponse toDefaultPlanStatusResponse(String userId) {
        return new PlanStatusResponse(
            userId,
            SubscriptionPlan.FREE,
            SubscriptionState.EXPIRED,
            null,
            null,
            false,
            List.of(),
            null
        );
    }

    private boolean isActive(SubscriptionPlan plan, SubscriptionState state, Instant cycleStart, Instant cycleEnd) {
        if (plan != SubscriptionPlan.PRO || state != SubscriptionState.ACTIVE) {
            return false;
        }
        Instant now = Instant.now();
        return cycleStart != null
            && cycleEnd != null
            && !cycleStart.isAfter(now)
            && cycleEnd.isAfter(now);
    }
}
