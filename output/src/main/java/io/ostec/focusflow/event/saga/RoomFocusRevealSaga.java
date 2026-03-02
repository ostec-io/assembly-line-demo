package io.ostec.focusflow.event.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomParticipantEmbedded;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.event.model.accountability.RoomClosedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.repository.accountability.AccountabilityRoomRepository;
import io.ostec.focusflow.service.accountability.AccountabilityRoomCommandService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class RoomFocusRevealSaga {

    private static final Logger log = LoggerFactory.getLogger(RoomFocusRevealSaga.class);

    private static final String SAGA_TYPE = "RoomFocusRevealSaga";
    private static final String SAGA_AGGREGATE_TYPE = "RoomFocusRevealSaga";
    private static final String ROOM_AGGREGATE_TYPE = "AccountabilityRoom";
    private static final String EVENT_VERSION = "1";

    private static final String STATE_ACTIVE = "Active";
    private static final String STATE_AWAITING_RESULTS = "AwaitingResults";
    private static final String STATE_REVEAL_READY = "RevealReady";
    private static final String STATE_CLOSED = "Closed";

    private static final String STEP_ROOM_SESSION_STARTED = "onRoomSessionStarted";
    private static final String STEP_PARTICIPANT_RESULT_SUBMITTED = "onParticipantResultSubmitted";
    private static final String STEP_REVEAL_TRIGGERED = "onRevealTriggered";
    private static final String STEP_HOST_DISCONNECTED = "onHostDisconnected";

    private static final String EVENT_REVEAL_TRIGGERED = "RoomRevealTriggered";
    private static final String EVENT_HOST_TRANSFERRED = "RoomHostTransferred";
    private static final String EVENT_TIMEOUT_ESCALATED = "RoomRevealTimeoutEscalated";

    private final SagaStateStore sagaStateStore;
    private final SagaCompensationPolicy sagaCompensationPolicy;
    private final AccountabilityRoomCommandService accountabilityRoomCommandService;
    private final AccountabilityRoomRepository accountabilityRoomRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    public RoomFocusRevealSaga(
        SagaStateStore sagaStateStore,
        SagaCompensationPolicy sagaCompensationPolicy,
        AccountabilityRoomCommandService accountabilityRoomCommandService,
        AccountabilityRoomRepository accountabilityRoomRepository,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper
    ) {
        this.sagaStateStore = sagaStateStore;
        this.sagaCompensationPolicy = sagaCompensationPolicy;
        this.accountabilityRoomCommandService = accountabilityRoomCommandService;
        this.accountabilityRoomRepository = accountabilityRoomRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        switch (normalizedType) {
            case "RoomSessionStarted" -> onRoomSessionStarted(envelope);
            case "ParticipantResultSubmitted" -> onParticipantResultSubmitted(envelope);
            case "RoomResultsRevealed", "RoomRevealTriggered" -> onRevealTriggered(envelope);
            case "HostDisconnected", "UserSuspended" -> onHostDisconnected(envelope);
            default -> {
                // No-op: unrelated event type.
            }
        }
    }

    @Scheduled(fixedDelayString = "${focusflow.saga.room-timeout-check-ms:30000}")
    public void checkTimeouts() {
        Instant now = Instant.now();
        for (SagaStateRecord record : sagaStateStore.findTimedOut(SAGA_TYPE, now)) {
            if (record.isTerminal()) {
                continue;
            }

            String tenantId = record.getTenantId();
            String orgId = record.getOrgId();
            if (!hasScope(tenantId, orgId)) {
                continue;
            }

            String roomId = record.getSagaId();
            String correlationId = StringUtils.hasText(record.getCorrelationId())
                ? record.getCorrelationId()
                : UUID.randomUUID().toString();
            String hostUserId = record.getAttribute("hostUserId");
            String previousState = record.getState();

            Optional<AccountabilityRoomDocument> roomOptional = accountabilityRoomRepository
                .findByIdAndTenantIdAndOrgId(roomId, tenantId, orgId);
            if (roomOptional.isPresent() && allParticipantsSubmitted(roomOptional.get())) {
                record.transitionTo(
                    STATE_REVEAL_READY,
                    correlationId,
                    record.getLastEventId(),
                    sagaCompensationPolicy.roomHostDisconnectDeadline(now)
                );
                triggerReveal(roomOptional.get(), correlationId, record.getLastEventId(), record);
                continue;
            }

            forceCloseRoom(
                tenantId,
                orgId,
                roomId,
                hostUserId,
                correlationId,
                "Timeout escalated from state " + previousState,
                record.getLastEventId()
            );

            record.transitionTo(STATE_CLOSED, correlationId, record.getLastEventId(), null);
            record.setCompensationReason("Timeout close from state " + previousState);

            String timeoutEventId = deterministicEventId(correlationId, EVENT_TIMEOUT_ESCALATED, roomId + ":" + previousState);
            publishSagaEvent(
                timeoutEventId,
                roomId,
                EVENT_TIMEOUT_ESCALATED,
                new RoomRevealTimeoutEscalatedPayload(
                    timeoutEventId,
                    now,
                    tenantId,
                    orgId,
                    roomId,
                    previousState,
                    record.getCompensationReason()
                ),
                correlationId,
                record.getLastEventId(),
                tenantId,
                orgId,
                hostUserId
            );
        }
    }

    public void onRoomSessionStarted(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping RoomSessionStarted because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String roomId = extractText(payload, "roomId");
        String hostUserId = extractText(payload, "hostUserId", "userId", "subjectUserId");
        if (!StringUtils.hasText(roomId) || !StringUtils.hasText(hostUserId)) {
            log.warn("Skipping RoomSessionStarted because roomId or hostUserId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            roomId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_ROOM_SESSION_STARTED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            roomId,
            STATE_ACTIVE,
            envelope.tenantId(),
            envelope.orgId(),
            correlationId
        );
        record.setScope(envelope.tenantId(), envelope.orgId());
        record.putAttribute("hostUserId", hostUserId);
        record.transitionTo(
            STATE_ACTIVE,
            correlationId,
            envelope.eventId(),
            sagaCompensationPolicy.roomAwaitResultsDeadline(Instant.now())
        );
        record.setCompensationReason(null);
    }

    public void onParticipantResultSubmitted(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping ParticipantResultSubmitted because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String roomId = extractText(payload, "roomId");
        if (!StringUtils.hasText(roomId)) {
            log.warn("Skipping ParticipantResultSubmitted because roomId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            roomId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_PARTICIPANT_RESULT_SUBMITTED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            roomId,
            STATE_ACTIVE,
            envelope.tenantId(),
            envelope.orgId(),
            correlationId
        );
        if (STATE_ACTIVE.equals(record.getState())) {
            record.transitionTo(
                STATE_AWAITING_RESULTS,
                correlationId,
                envelope.eventId(),
                sagaCompensationPolicy.roomAwaitResultsDeadline(Instant.now())
            );
        }

        Optional<AccountabilityRoomDocument> roomOptional = accountabilityRoomRepository
            .findByIdAndTenantIdAndOrgId(roomId, envelope.tenantId(), envelope.orgId());
        if (roomOptional.isEmpty()) {
            record.transitionTo(STATE_CLOSED, correlationId, envelope.eventId(), null);
            record.setCompensationReason("Room not found during reveal saga");
            return;
        }

        AccountabilityRoomDocument room = roomOptional.get();
        record.putAttribute("hostUserId", room.getHostUserId());

        int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;
        int submittedCount = submittedResultCount(room);
        record.putAttribute("participantCount", String.valueOf(participantCount));
        record.putAttribute("submittedCount", String.valueOf(submittedCount));

        if (participantCount > 0 && submittedCount >= participantCount) {
            record.transitionTo(
                STATE_REVEAL_READY,
                correlationId,
                envelope.eventId(),
                sagaCompensationPolicy.roomHostDisconnectDeadline(Instant.now())
            );
            triggerReveal(room, correlationId, envelope.eventId(), record);
            return;
        }

        record.transitionTo(
            STATE_AWAITING_RESULTS,
            correlationId,
            envelope.eventId(),
            sagaCompensationPolicy.roomAwaitResultsDeadline(Instant.now())
        );
    }

    public void onRevealTriggered(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping reveal trigger because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String roomId = extractText(payload, "roomId");
        if (!StringUtils.hasText(roomId)) {
            log.warn("Skipping reveal trigger because roomId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            roomId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_REVEAL_TRIGGERED
        )) {
            return;
        }

        String hostUserId = extractText(payload, "hostUserId", "userId", "subjectUserId");
        if (!StringUtils.hasText(hostUserId)) {
            hostUserId = sagaStateStore.find(SAGA_TYPE, roomId)
                .map(record -> record.getAttribute("hostUserId"))
                .orElse(null);
        }

        processRevealTriggered(
            envelope.tenantId(),
            envelope.orgId(),
            roomId,
            hostUserId,
            correlationId,
            envelope.eventId()
        );
    }

    private void onHostDisconnected(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String disconnectedUserId = extractText(payload, "userId", "hostUserId", "subjectUserId");
        if (!StringUtils.hasText(disconnectedUserId)) {
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        Instant now = Instant.now();
        for (SagaStateRecord record : sagaStateStore.findBySagaType(SAGA_TYPE)) {
            if (record.isTerminal()) {
                continue;
            }
            if (!envelope.tenantId().equals(record.getTenantId()) || !envelope.orgId().equals(record.getOrgId())) {
                continue;
            }
            if (!disconnectedUserId.equals(record.getAttribute("hostUserId"))) {
                continue;
            }

            String roomId = record.getSagaId();
            if (!sagaStateStore.markEventHandled(
                SAGA_TYPE,
                roomId,
                envelope.tenantId(),
                envelope.orgId(),
                envelope.eventId(),
                correlationId,
                STEP_HOST_DISCONNECTED
            )) {
                continue;
            }

            Optional<AccountabilityRoomDocument> roomOptional = accountabilityRoomRepository
                .findByIdAndTenantIdAndOrgId(roomId, envelope.tenantId(), envelope.orgId());
            if (roomOptional.isEmpty()) {
                record.transitionTo(STATE_CLOSED, correlationId, envelope.eventId(), null);
                record.setCompensationReason("Room missing while handling host disconnect");
                continue;
            }

            AccountabilityRoomDocument room = roomOptional.get();
            String candidateHostUserId = room.getParticipants().stream()
                .map(RoomParticipantEmbedded::getUserId)
                .filter(StringUtils::hasText)
                .filter(candidate -> !candidate.equals(disconnectedUserId))
                .findFirst()
                .orElse(null);

            if (sagaCompensationPolicy.shouldTransferHost(disconnectedUserId, candidateHostUserId)) {
                room.setHostUserId(candidateHostUserId);
                accountabilityRoomRepository.save(room);

                record.putAttribute("hostUserId", candidateHostUserId);
                record.setCompensationReason("Host transferred after disconnect");
                record.transitionTo(
                    record.getState(),
                    correlationId,
                    envelope.eventId(),
                    sagaCompensationPolicy.roomAwaitResultsDeadline(now)
                );

                String transferEventId = deterministicEventId(correlationId, EVENT_HOST_TRANSFERRED, roomId);
                publishRoomEvent(
                    transferEventId,
                    roomId,
                    EVENT_HOST_TRANSFERRED,
                    new RoomHostTransferredPayload(
                        transferEventId,
                        now,
                        envelope.tenantId(),
                        envelope.orgId(),
                        roomId,
                        disconnectedUserId,
                        candidateHostUserId,
                        envelope.eventId()
                    ),
                    correlationId,
                    envelope.eventId(),
                    envelope.tenantId(),
                    envelope.orgId(),
                    candidateHostUserId
                );
            } else {
                record.setCompensationReason("Host disconnected; timeout close scheduled");
                record.transitionTo(
                    record.getState(),
                    correlationId,
                    envelope.eventId(),
                    sagaCompensationPolicy.roomHostDisconnectDeadline(now)
                );
            }
        }
    }

    private void triggerReveal(
        AccountabilityRoomDocument room,
        String correlationId,
        String sourceEventId,
        SagaStateRecord record
    ) {
        if (room == null || !StringUtils.hasText(room.getId())) {
            return;
        }

        String tenantId = room.getTenantId();
        String orgId = room.getOrgId();
        String roomId = room.getId();
        String hostUserId = StringUtils.hasText(room.getHostUserId()) ? room.getHostUserId() : record.getAttribute("hostUserId");

        if (!hasScope(tenantId, orgId)) {
            return;
        }

        try {
            withTenantContext(tenantId, orgId, hostUserId, correlationId, () ->
                accountabilityRoomCommandService.revealRoomResults(roomId)
            );

            String revealTriggeredEventId = deterministicEventId(correlationId, EVENT_REVEAL_TRIGGERED, roomId);
            publishSagaEvent(
                revealTriggeredEventId,
                roomId,
                EVENT_REVEAL_TRIGGERED,
                new RoomRevealTriggeredPayload(
                    revealTriggeredEventId,
                    Instant.now(),
                    tenantId,
                    orgId,
                    roomId,
                    hostUserId,
                    room.getParticipants() != null ? room.getParticipants().size() : 0,
                    submittedResultCount(room),
                    sourceEventId
                ),
                correlationId,
                sourceEventId,
                tenantId,
                orgId,
                hostUserId
            );

            processRevealTriggered(tenantId, orgId, roomId, hostUserId, correlationId, revealTriggeredEventId);
        } catch (Exception ex) {
            record.setCompensationReason("Reveal trigger failed: " + ex.getMessage());
            record.transitionTo(
                STATE_REVEAL_READY,
                correlationId,
                sourceEventId,
                sagaCompensationPolicy.roomHostDisconnectDeadline(Instant.now())
            );
            log.debug("Reveal trigger failed for room {}", roomId, ex);
        }
    }

    private void processRevealTriggered(
        String tenantId,
        String orgId,
        String roomId,
        String hostUserId,
        String correlationId,
        String sourceEventId
    ) {
        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            roomId,
            STATE_REVEAL_READY,
            tenantId,
            orgId,
            correlationId
        );
        if (StringUtils.hasText(hostUserId)) {
            record.putAttribute("hostUserId", hostUserId);
        } else {
            hostUserId = record.getAttribute("hostUserId");
        }

        record.transitionTo(
            STATE_REVEAL_READY,
            correlationId,
            sourceEventId,
            sagaCompensationPolicy.roomHostDisconnectDeadline(Instant.now())
        );

        try {
            withTenantContext(tenantId, orgId, hostUserId, correlationId, () ->
                accountabilityRoomCommandService.closeRoom(roomId)
            );
            record.transitionTo(STATE_CLOSED, correlationId, sourceEventId, null);
            record.setCompensationReason(null);
        } catch (Exception ex) {
            record.setCompensationReason("Close after reveal failed; forcing close: " + ex.getMessage());
            forceCloseRoom(
                tenantId,
                orgId,
                roomId,
                hostUserId,
                correlationId,
                record.getCompensationReason(),
                sourceEventId
            );
            record.transitionTo(STATE_CLOSED, correlationId, sourceEventId, null);
        }
    }

    private void forceCloseRoom(
        String tenantId,
        String orgId,
        String roomId,
        String hostUserId,
        String correlationId,
        String reason,
        String sourceEventId
    ) {
        Optional<AccountabilityRoomDocument> roomOptional = accountabilityRoomRepository
            .findByIdAndTenantIdAndOrgId(roomId, tenantId, orgId);

        Instant closedAt = Instant.now();
        String effectiveHostUserId = hostUserId;
        if (roomOptional.isPresent()) {
            AccountabilityRoomDocument room = roomOptional.get();
            if (!StringUtils.hasText(effectiveHostUserId)) {
                effectiveHostUserId = room.getHostUserId();
            }
            if (room.getStatus() != RoomStatus.CLOSED) {
                room.setStatus(RoomStatus.CLOSED);
                room.setClosedAt(closedAt);
                accountabilityRoomRepository.save(room);
            }
        }

        String closeEventId = deterministicEventId(
            correlationId,
            "RoomClosed",
            roomId + ":" + normalizeNullable(reason)
        );
        RoomClosedEvent closedEvent = new RoomClosedEvent(
            closeEventId,
            closedAt,
            tenantId,
            orgId,
            roomId,
            effectiveHostUserId,
            closedAt
        );

        publishRoomEvent(
            closedEvent.eventId(),
            roomId,
            "RoomClosed",
            closedEvent,
            correlationId,
            sourceEventId,
            tenantId,
            orgId,
            effectiveHostUserId
        );
    }

    private int submittedResultCount(AccountabilityRoomDocument room) {
        if (room == null || room.getParticipants() == null) {
            return 0;
        }
        return (int) room.getParticipants().stream()
            .filter(participant -> participant.getResultSummary() != null)
            .count();
    }

    private boolean allParticipantsSubmitted(AccountabilityRoomDocument room) {
        if (room == null || room.getParticipants() == null || room.getParticipants().isEmpty()) {
            return false;
        }

        return room.getParticipants().stream()
            .allMatch(participant ->
                participant.getResultSummary() != null
                    && StringUtils.hasText(participant.getResultSummary().getSummary())
            );
    }

    private void publishSagaEvent(
        String eventId,
        String aggregateId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId,
        String tenantId,
        String orgId,
        String userId
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> outboxPublisherService.enqueueWithEventId(
            eventId,
            SAGA_AGGREGATE_TYPE,
            aggregateId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            StringUtils.hasText(causationId) ? causationId : eventId
        ));
    }

    private void publishRoomEvent(
        String eventId,
        String roomId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId,
        String tenantId,
        String orgId,
        String userId
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> outboxPublisherService.enqueueWithEventId(
            eventId,
            ROOM_AGGREGATE_TYPE,
            roomId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            StringUtils.hasText(causationId) ? causationId : eventId
        ));
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Unable to parse room saga payload", ex);
            return objectMapper.createObjectNode();
        }
    }

    private String extractText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }

        return null;
    }

    private String resolveCorrelationId(DomainEventEnvelope envelope) {
        if (StringUtils.hasText(envelope.correlationId())) {
            return envelope.correlationId();
        }
        if (StringUtils.hasText(envelope.eventId())) {
            return envelope.eventId();
        }
        return UUID.randomUUID().toString();
    }

    private String normalizeEventType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "";
        }

        String type = rawType;
        int lastDotIndex = type.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < type.length() - 1) {
            type = type.substring(lastDotIndex + 1);
        }

        if (type.endsWith("Event")) {
            type = type.substring(0, type.length() - "Event".length());
        }

        return type;
    }

    private String deterministicEventId(String correlationId, String eventType, String aggregateKey) {
        String material = correlationId + ":" + eventType + ":" + aggregateKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize room saga payload", ex);
        }
    }

    private boolean hasScope(String tenantId, String orgId) {
        return StringUtils.hasText(tenantId) && StringUtils.hasText(orgId);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : "none";
    }

    private void withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        String correlationId,
        Runnable action
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        String correlationId,
        Supplier<T> action
    ) {
        if (!hasScope(tenantId, orgId)) {
            throw new IllegalStateException("Tenant and org context are required");
        }

        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();
        String effectiveUserId = StringUtils.hasText(userId) ? userId : "system";
        String effectiveCorrelationId = StringUtils.hasText(correlationId) ? correlationId : UUID.randomUUID().toString();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(tenantId)
                .orgId(orgId)
                .userId(effectiveUserId)
                .correlationId(effectiveCorrelationId)
                .build());
            return action.get();
        } finally {
            if (hadPreviousContext && previous != null && previous.hasAnyContext()) {
                TenantContextHolder.setContext(previous);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private record RoomRevealTriggeredPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String roomId,
        String hostUserId,
        int participantCount,
        int submittedResultCount,
        String sourceEventId
    ) {
    }

    private record RoomHostTransferredPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String roomId,
        String fromHostUserId,
        String toHostUserId,
        String sourceEventId
    ) {
    }

    private record RoomRevealTimeoutEscalatedPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String roomId,
        String previousState,
        String reason
    ) {
    }
}
