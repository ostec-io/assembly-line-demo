package io.ostec.focusflow.service.entitlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.entitlement.SubscriptionAccountDocument;
import io.ostec.focusflow.document.entitlement.SubscriptionPlan;
import io.ostec.focusflow.document.entitlement.SubscriptionState;
import io.ostec.focusflow.document.entitlement.UsageCounterDocument;
import io.ostec.focusflow.event.model.entitlement.UsageLimitReachedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.metrics.FocusMetricsService;
import io.ostec.focusflow.repository.entitlement.SubscriptionAccountRepository;
import io.ostec.focusflow.repository.entitlement.UsageCounterRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EntitlementDecisionService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementDecisionService.class);

    private static final String AGGREGATE_TYPE = "EntitlementAccount";
    private static final String EVENT_VERSION = "1";
    private static final int DEFAULT_DAILY_LIMIT = 25;
    private static final Map<String, Integer> FEATURE_DAILY_LIMITS = Map.of(
        "ai.plan-memory", 20,
        "accountability.private-room", 10,
        "focus.deep-work.analytics", 50
    );

    private final SubscriptionAccountRepository subscriptionAccountRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final FocusMetricsService focusMetricsService;
    private final ObjectMapper objectMapper;
    private final Map<String, Boolean> featureAccessCache = new ConcurrentHashMap<>();

    public EntitlementDecisionService(
        SubscriptionAccountRepository subscriptionAccountRepository,
        UsageCounterRepository usageCounterRepository,
        OutboxPublisherService outboxPublisherService,
        FocusMetricsService focusMetricsService,
        ObjectMapper objectMapper
    ) {
        this.subscriptionAccountRepository = subscriptionAccountRepository;
        this.usageCounterRepository = usageCounterRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.focusMetricsService = focusMetricsService;
        this.objectMapper = objectMapper;
    }

    public boolean checkFeatureAccess(String featureKey, String action, String resourceId) {
        long startedAtNanos = System.nanoTime();
        if (!StringUtils.hasText(featureKey)) {
            return false;
        }

        try {
            TenantScope scope = resolveScope();
            String cacheKey = accessCacheKey(scope, featureKey, action, resourceId);
            return featureAccessCache.computeIfAbsent(cacheKey, key -> evaluateFeatureAccess(scope, featureKey));
        } catch (Exception ex) {
            log.warn("Fail-closed feature access decision for featureKey={}", featureKey, ex);
            return false;
        } finally {
            focusMetricsService.recordEntitlementCheckLatency(
                Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    public boolean checkDailyUsage(String featureKey) {
        long startedAtNanos = System.nanoTime();
        if (!StringUtils.hasText(featureKey)) {
            return false;
        }

        try {
            TenantScope scope = resolveScope();
            if (!evaluateFeatureAccess(scope, featureKey)) {
                return false;
            }

            int dailyLimit = resolveDailyLimit(featureKey);
            if (dailyLimit <= 0) {
                return false;
            }

            LocalDate usageDate = LocalDate.now();
            int usageCount = usageCounterRepository
                .findByTenantIdAndOrgIdAndUserIdAndFeatureKeyAndUsageDate(
                    scope.tenantId(),
                    scope.orgId(),
                    scope.userId(),
                    featureKey.trim(),
                    usageDate
                )
                .map(document -> document.getUsageCount() != null ? document.getUsageCount() : 0)
                .orElse(0);

            return usageCount < dailyLimit;
        } catch (Exception ex) {
            log.warn("Fail-closed daily usage decision for featureKey={}", featureKey, ex);
            return false;
        } finally {
            focusMetricsService.recordEntitlementCheckLatency(
                Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    @Transactional
    public boolean grantUsage(String featureKey, String idempotencyKey) {
        return grantUsageInternal(featureKey, idempotencyKey, false);
    }

    public void invalidateCacheForUser(String userId) {
        TenantScope scope = resolveScope();
        String effectiveUserId = StringUtils.hasText(userId) ? userId.trim() : scope.userId();
        String prefix = scope.tenantId() + ":" + scope.orgId() + ":" + effectiveUserId + ":";
        featureAccessCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private boolean grantUsageInternal(String featureKey, String idempotencyKey, boolean retriedAfterCollision) {
        if (!StringUtils.hasText(featureKey) || !StringUtils.hasText(idempotencyKey)) {
            return false;
        }

        TenantScope scope;
        try {
            scope = resolveScope();
        } catch (Exception ex) {
            log.warn("Fail-closed usage grant because tenant context was unavailable");
            return false;
        }

        String normalizedFeatureKey = featureKey.trim();
        String normalizedIdempotencyKey = idempotencyKey.trim();
        if (!evaluateFeatureAccess(scope, normalizedFeatureKey)) {
            return false;
        }

        int dailyLimit = resolveDailyLimit(normalizedFeatureKey);
        if (dailyLimit <= 0) {
            return false;
        }

        LocalDate usageDate = LocalDate.now();
        UsageCounterDocument counter = usageCounterRepository
            .findByTenantIdAndOrgIdAndUserIdAndFeatureKeyAndUsageDate(
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                normalizedFeatureKey,
                usageDate
            )
            .orElseGet(() -> newUsageCounter(scope, normalizedFeatureKey, usageDate));

        if (counter.getUsageCount() == null) {
            counter.setUsageCount(0);
        }

        if (counter.getIdempotencyKeys().contains(normalizedIdempotencyKey)) {
            return true;
        }

        if (counter.getUsageCount() >= dailyLimit) {
            publishUsageLimitReached(scope, normalizedFeatureKey, usageDate, counter.getUsageCount(), dailyLimit);
            return false;
        }

        counter.getIdempotencyKeys().add(normalizedIdempotencyKey);
        counter.setUsageCount(counter.getUsageCount() + 1);

        try {
            usageCounterRepository.save(counter);
        } catch (DuplicateKeyException ex) {
            if (!retriedAfterCollision) {
                return grantUsageInternal(normalizedFeatureKey, normalizedIdempotencyKey, true);
            }
            log.warn(
                "Fail-closed usage grant after duplicate-key collision for tenantId={}, orgId={}, userId={}, featureKey={}",
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                normalizedFeatureKey
            );
            return false;
        }

        if (counter.getUsageCount() >= dailyLimit) {
            publishUsageLimitReached(scope, normalizedFeatureKey, usageDate, counter.getUsageCount(), dailyLimit);
        }

        return true;
    }

    private boolean evaluateFeatureAccess(TenantScope scope, String featureKey) {
        Optional<SubscriptionAccountDocument> accountOptional = subscriptionAccountRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId());

        if (accountOptional.isEmpty()) {
            return false;
        }

        SubscriptionAccountDocument account = accountOptional.get();
        if (account.getPlan() != SubscriptionPlan.PRO || account.getState() != SubscriptionState.ACTIVE) {
            return false;
        }

        Instant now = Instant.now();
        if (account.getCycleStart() == null || account.getCycleStart().isAfter(now)) {
            return false;
        }

        if (account.getCycleEnd() == null || !account.getCycleEnd().isAfter(now)) {
            return false;
        }

        return account.getEntitlements().stream().anyMatch(feature -> featureMatches(feature, featureKey));
    }

    private boolean featureMatches(String grantedEntitlement, String requestedFeature) {
        return StringUtils.hasText(grantedEntitlement)
            && StringUtils.hasText(requestedFeature)
            && grantedEntitlement.trim().equalsIgnoreCase(requestedFeature.trim());
    }

    private int resolveDailyLimit(String featureKey) {
        return FEATURE_DAILY_LIMITS.getOrDefault(featureKey.toLowerCase(), DEFAULT_DAILY_LIMIT);
    }

    private UsageCounterDocument newUsageCounter(TenantScope scope, String featureKey, LocalDate usageDate) {
        UsageCounterDocument counter = new UsageCounterDocument();
        counter.setTenantId(scope.tenantId());
        counter.setOrgId(scope.orgId());
        counter.setUserId(scope.userId());
        counter.setFeatureKey(featureKey);
        counter.setUsageDate(usageDate);
        counter.setUsageCount(0);
        return counter;
    }

    private void publishUsageLimitReached(
        TenantScope scope,
        String featureKey,
        LocalDate usageDate,
        int usageCount,
        int dailyLimit
    ) {
        Instant occurredAt = Instant.now();
        String eventId = deterministicEventId(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            "UsageLimitReached",
            featureKey + ":" + usageDate + ":" + usageCount + ":" + dailyLimit
        );
        String correlationId = resolveCorrelationId(eventId);

        UsageLimitReachedEvent event = new UsageLimitReachedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            featureKey,
            usageDate,
            usageCount,
            dailyLimit,
            "Daily usage limit reached"
        );

        outboxPublisherService.enqueueWithEventId(
            event.eventId(),
            AGGREGATE_TYPE,
            scope.userId(),
            "UsageLimitReached",
            EVENT_VERSION,
            toJson(event),
            correlationId,
            event.eventId()
        );
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize entitlement event payload", ex);
        }
    }

    private String deterministicEventId(
        String tenantId,
        String orgId,
        String userId,
        String eventType,
        String materialSuffix
    ) {
        String material = tenantId + ":" + orgId + ":" + userId + ":" + eventType + ":" + materialSuffix;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String fallback) {
        String correlationId = TenantContextHolder.getCorrelationId();
        return StringUtils.hasText(correlationId) ? correlationId : fallback;
    }

    private String accessCacheKey(TenantScope scope, String featureKey, String action, String resourceId) {
        return scope.tenantId()
            + ":"
            + scope.orgId()
            + ":"
            + scope.userId()
            + ":"
            + featureKey.trim().toLowerCase()
            + ":"
            + normalizeNullable(action)
            + ":"
            + normalizeNullable(resourceId);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "none";
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
