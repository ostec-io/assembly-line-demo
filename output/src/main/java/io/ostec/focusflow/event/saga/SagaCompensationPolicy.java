package io.ostec.focusflow.event.saga;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;

@Component
public class SagaCompensationPolicy {

    private final Duration deepWorkEntitlementTimeout;
    private final Duration deepWorkPlanningTimeout;
    private final Duration deepWorkSessionTimeout;
    private final Duration roomAwaitResultsTimeout;
    private final Duration roomHostDisconnectGracePeriod;

    public SagaCompensationPolicy(
        @Value("${focusflow.saga.deep-work.entitlement-timeout-ms:120000}") Long deepWorkEntitlementTimeoutMs,
        @Value("${focusflow.saga.deep-work.plan-timeout-ms:300000}") Long deepWorkPlanningTimeoutMs,
        @Value("${focusflow.saga.deep-work.session-timeout-ms:21600000}") Long deepWorkSessionTimeoutMs,
        @Value("${focusflow.saga.room.await-results-timeout-ms:600000}") Long roomAwaitResultsTimeoutMs,
        @Value("${focusflow.saga.room.host-disconnect-grace-ms:120000}") Long roomHostDisconnectGracePeriodMs
    ) {
        this.deepWorkEntitlementTimeout = Duration.ofMillis(safeDurationMs(deepWorkEntitlementTimeoutMs, 120_000L));
        this.deepWorkPlanningTimeout = Duration.ofMillis(safeDurationMs(deepWorkPlanningTimeoutMs, 300_000L));
        this.deepWorkSessionTimeout = Duration.ofMillis(safeDurationMs(deepWorkSessionTimeoutMs, 21_600_000L));
        this.roomAwaitResultsTimeout = Duration.ofMillis(safeDurationMs(roomAwaitResultsTimeoutMs, 600_000L));
        this.roomHostDisconnectGracePeriod = Duration.ofMillis(safeDurationMs(roomHostDisconnectGracePeriodMs, 120_000L));
    }

    public Instant deepWorkEntitlementDeadline(Instant startedAt) {
        return plus(startedAt, deepWorkEntitlementTimeout);
    }

    public Instant deepWorkPlanningDeadline(Instant startedAt) {
        return plus(startedAt, deepWorkPlanningTimeout);
    }

    public Instant deepWorkSessionDeadline(Instant startedAt) {
        return plus(startedAt, deepWorkSessionTimeout);
    }

    public Instant roomAwaitResultsDeadline(Instant startedAt) {
        return plus(startedAt, roomAwaitResultsTimeout);
    }

    public Instant roomHostDisconnectDeadline(Instant startedAt) {
        return plus(startedAt, roomHostDisconnectGracePeriod);
    }

    public boolean shouldFallbackToManualPlanning(Exception exception) {
        if (exception == null) {
            return true;
        }

        String message = exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return true;
        }

        String normalized = message.trim().toLowerCase();
        return normalized.contains("provider")
            || normalized.contains("timeout")
            || normalized.contains("plan")
            || normalized.contains("serialize")
            || normalized.contains("session");
    }

    public String manualPlanningReason(Exception exception) {
        if (exception == null || !StringUtils.hasText(exception.getMessage())) {
            return "AI planning failed; switched to manual planning mode";
        }
        return "AI planning failed; switched to manual planning mode: " + exception.getMessage().trim();
    }

    public boolean shouldTransferHost(String currentHostUserId, String candidateHostUserId) {
        return StringUtils.hasText(currentHostUserId)
            && StringUtils.hasText(candidateHostUserId)
            && !currentHostUserId.trim().equals(candidateHostUserId.trim());
    }

    private Instant plus(Instant base, Duration timeout) {
        Instant effectiveBase = base != null ? base : Instant.now();
        return effectiveBase.plus(timeout);
    }

    private long safeDurationMs(Long candidate, long fallback) {
        if (candidate == null || candidate <= 0L) {
            return fallback;
        }
        return candidate;
    }
}
