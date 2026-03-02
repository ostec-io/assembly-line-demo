package io.ostec.focusflow.event.saga;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class SagaStateStore {

    private final ConcurrentMap<String, SagaStateRecord> stateBySagaKey = new ConcurrentHashMap<>();
    private final Set<String> processedEventKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> processedCorrelationStepKeys = ConcurrentHashMap.newKeySet();

    public SagaStateRecord getOrCreate(
        String sagaType,
        String sagaId,
        String initialState,
        String tenantId,
        String orgId,
        String correlationId
    ) {
        String key = sagaKey(sagaType, sagaId);
        return stateBySagaKey.computeIfAbsent(
            key,
            ignored -> new SagaStateRecord(sagaType, sagaId, initialState, tenantId, orgId, correlationId)
        );
    }

    public Optional<SagaStateRecord> find(String sagaType, String sagaId) {
        if (!StringUtils.hasText(sagaType) || !StringUtils.hasText(sagaId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(stateBySagaKey.get(sagaKey(sagaType, sagaId)));
    }

    public List<SagaStateRecord> findBySagaType(String sagaType) {
        if (!StringUtils.hasText(sagaType)) {
            return List.of();
        }

        return stateBySagaKey.values().stream()
            .filter(record -> sagaType.trim().equals(record.getSagaType()))
            .toList();
    }

    public List<SagaStateRecord> findTimedOut(String sagaType, Instant now) {
        if (!StringUtils.hasText(sagaType) || now == null) {
            return List.of();
        }

        return stateBySagaKey.values().stream()
            .filter(record -> sagaType.trim().equals(record.getSagaType()))
            .filter(record -> !record.isTerminal())
            .filter(record -> record.hasTimeoutElapsed(now))
            .toList();
    }

    public boolean markEventHandled(
        String sagaType,
        String sagaId,
        String tenantId,
        String orgId,
        String eventId,
        String correlationId,
        String step
    ) {
        if (!StringUtils.hasText(eventId) && !StringUtils.hasText(correlationId)) {
            return true;
        }

        if (StringUtils.hasText(eventId)) {
            String eventKey = eventKey(sagaType, sagaId, tenantId, orgId, eventId);
            if (!processedEventKeys.add(eventKey)) {
                return false;
            }
        }

        if (StringUtils.hasText(correlationId) && StringUtils.hasText(step)) {
            String correlationStepKey = correlationStepKey(sagaType, sagaId, tenantId, orgId, correlationId, step);
            if (!processedCorrelationStepKeys.add(correlationStepKey)) {
                return false;
            }
        }

        return true;
    }

    private String sagaKey(String sagaType, String sagaId) {
        return normalizeNullable(sagaType) + "::" + normalizeNullable(sagaId);
    }

    private String eventKey(
        String sagaType,
        String sagaId,
        String tenantId,
        String orgId,
        String eventId
    ) {
        return "event:"
            + normalizeNullable(sagaType)
            + ":"
            + normalizeNullable(sagaId)
            + ":"
            + normalizeNullable(tenantId)
            + ":"
            + normalizeNullable(orgId)
            + ":"
            + normalizeNullable(eventId);
    }

    private String correlationStepKey(
        String sagaType,
        String sagaId,
        String tenantId,
        String orgId,
        String correlationId,
        String step
    ) {
        return "corr-step:"
            + normalizeNullable(sagaType)
            + ":"
            + normalizeNullable(sagaId)
            + ":"
            + normalizeNullable(tenantId)
            + ":"
            + normalizeNullable(orgId)
            + ":"
            + normalizeNullable(correlationId)
            + ":"
            + normalizeNullable(step);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : "none";
    }
}
