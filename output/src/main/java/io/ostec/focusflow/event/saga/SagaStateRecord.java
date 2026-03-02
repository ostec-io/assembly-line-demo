package io.ostec.focusflow.event.saga;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SagaStateRecord {

    private final String sagaType;
    private final String sagaId;
    private final Instant createdAt;
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    private String state;
    private String tenantId;
    private String orgId;
    private String correlationId;
    private String lastEventId;
    private Instant updatedAt;
    private Instant timeoutAt;
    private String compensationReason;

    public SagaStateRecord(
        String sagaType,
        String sagaId,
        String initialState,
        String tenantId,
        String orgId,
        String correlationId
    ) {
        if (!StringUtils.hasText(sagaType) || !StringUtils.hasText(sagaId) || !StringUtils.hasText(initialState)) {
            throw new IllegalArgumentException("Saga type, saga id, and initial state are required");
        }

        Instant now = Instant.now();
        this.sagaType = sagaType.trim();
        this.sagaId = sagaId.trim();
        this.state = initialState.trim();
        this.tenantId = normalizeNullable(tenantId);
        this.orgId = normalizeNullable(orgId);
        this.correlationId = normalizeNullable(correlationId);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public synchronized void transitionTo(
        String nextState,
        String nextCorrelationId,
        String nextLastEventId,
        Instant nextTimeoutAt
    ) {
        if (StringUtils.hasText(nextState)) {
            this.state = nextState.trim();
        }
        if (StringUtils.hasText(nextCorrelationId)) {
            this.correlationId = nextCorrelationId.trim();
        }
        if (StringUtils.hasText(nextLastEventId)) {
            this.lastEventId = nextLastEventId.trim();
        }
        this.timeoutAt = nextTimeoutAt;
        this.updatedAt = Instant.now();
    }

    public synchronized void setScope(String tenantId, String orgId) {
        if (StringUtils.hasText(tenantId)) {
            this.tenantId = tenantId.trim();
        }
        if (StringUtils.hasText(orgId)) {
            this.orgId = orgId.trim();
        }
        this.updatedAt = Instant.now();
    }

    public synchronized void setCompensationReason(String compensationReason) {
        this.compensationReason = normalizeNullable(compensationReason);
        this.updatedAt = Instant.now();
    }

    public synchronized void putAttribute(String key, String value) {
        if (!StringUtils.hasText(key)) {
            return;
        }

        if (StringUtils.hasText(value)) {
            attributes.put(key.trim(), value.trim());
        } else {
            attributes.remove(key.trim());
        }
        this.updatedAt = Instant.now();
    }

    public synchronized String getAttribute(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return attributes.get(key.trim());
    }

    public synchronized Map<String, String> snapshotAttributes() {
        return Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    public synchronized boolean hasTimeoutElapsed(Instant now) {
        if (timeoutAt == null || now == null) {
            return false;
        }
        return !timeoutAt.isAfter(now);
    }

    public synchronized boolean isTerminal() {
        return "Completed".equals(state)
            || "Closed".equals(state)
            || "Expired".equals(state)
            || "Failed".equals(state);
    }

    public String getSagaType() {
        return sagaType;
    }

    public String getSagaId() {
        return sagaId;
    }

    public synchronized String getState() {
        return state;
    }

    public synchronized String getTenantId() {
        return tenantId;
    }

    public synchronized String getOrgId() {
        return orgId;
    }

    public synchronized String getCorrelationId() {
        return correlationId;
    }

    public synchronized String getLastEventId() {
        return lastEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public synchronized Instant getUpdatedAt() {
        return updatedAt;
    }

    public synchronized Instant getTimeoutAt() {
        return timeoutAt;
    }

    public synchronized String getCompensationReason() {
        return compensationReason;
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
