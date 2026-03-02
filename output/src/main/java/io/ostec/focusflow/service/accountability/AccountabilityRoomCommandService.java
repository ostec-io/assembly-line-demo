package io.ostec.focusflow.service.accountability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.audit.AuditLogService;
import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.ParticipantGoalEmbedded;
import io.ostec.focusflow.document.accountability.ResultSummaryEmbedded;
import io.ostec.focusflow.document.accountability.RoomParticipantEmbedded;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;
import io.ostec.focusflow.event.model.accountability.ParticipantGoalSetEvent;
import io.ostec.focusflow.event.model.accountability.ParticipantJoinedRoomEvent;
import io.ostec.focusflow.event.model.accountability.ParticipantResultSubmittedEvent;
import io.ostec.focusflow.event.model.accountability.RoomClosedEvent;
import io.ostec.focusflow.event.model.accountability.RoomCreatedEvent;
import io.ostec.focusflow.event.model.accountability.RoomLockedEvent;
import io.ostec.focusflow.event.model.accountability.RoomResultsRevealedEvent;
import io.ostec.focusflow.event.model.accountability.RoomSessionStartedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.accountability.AccountabilityRoomRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountabilityRoomCommandService {

    private static final String AGGREGATE_TYPE = "AccountabilityRoom";
    private static final String EVENT_VERSION = "1";
    private static final String ROOM_FULL = "ROOM_FULL";
    private static final String PARTICIPANT_NOT_FOUND = "PARTICIPANT_NOT_FOUND";
    private static final String REVEAL_PRECONDITIONS_NOT_MET = "REVEAL_PRECONDITIONS_NOT_MET";

    private final AccountabilityRoomRepository accountabilityRoomRepository;
    private final RoomModerationService roomModerationService;
    private final OutboxPublisherService outboxPublisherService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AccountabilityRoomCommandService(
        AccountabilityRoomRepository accountabilityRoomRepository,
        RoomModerationService roomModerationService,
        OutboxPublisherService outboxPublisherService,
        AuditLogService auditLogService,
        ObjectMapper objectMapper
    ) {
        this.accountabilityRoomRepository = accountabilityRoomRepository;
        this.roomModerationService = roomModerationService;
        this.outboxPublisherService = outboxPublisherService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AccountabilityRoomDocument createRoom(
        String title,
        RoomVisibility visibility,
        Integer capacity,
        String correlationId
    ) {
        TenantScope scope = resolveScope();
        RoomVisibility effectiveVisibility = visibility != null ? visibility : RoomVisibility.PUBLIC;
        String normalizedTitle = normalizeTitle(title);
        Integer normalizedCapacity = normalizeCapacity(capacity);
        roomModerationService.assertPrivateRoomAccessAllowed(effectiveVisibility, scope.userId());

        Instant createdAt = Instant.now();
        AccountabilityRoomDocument room = new AccountabilityRoomDocument();
        room.setTenantId(scope.tenantId());
        room.setOrgId(scope.orgId());
        room.setTitle(normalizedTitle);
        room.setHostUserId(scope.userId());
        room.setVisibility(effectiveVisibility);
        room.setCapacity(normalizedCapacity);
        room.setStatus(RoomStatus.OPEN);
        room.getParticipants().add(new RoomParticipantEmbedded(scope.userId(), createdAt, null, null));

        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String resolvedCorrelationId = resolveCorrelationId(correlationId);
        String eventId = deterministicEventId(resolvedCorrelationId, "RoomCreated", savedRoom.getId());
        RoomCreatedEvent event = new RoomCreatedEvent(
            eventId,
            createdAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            normalizedTitle,
            effectiveVisibility.name(),
            normalizedCapacity
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "RoomCreated",
            event,
            resolvedCorrelationId,
            event.eventId()
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument joinRoom(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);

        if (isParticipant(room, scope.userId())) {
            return room;
        }

        if (room.getStatus() != RoomStatus.OPEN) {
            throw new IllegalStateException(ApiErrorCode.ROOM_LOCKED.name());
        }

        Integer capacity = room.getCapacity() != null ? room.getCapacity() : Integer.MAX_VALUE;
        if (room.getParticipants().size() >= capacity) {
            throw new IllegalStateException(ROOM_FULL);
        }

        roomModerationService.assertPrivateRoomAccessAllowed(room.getVisibility(), scope.userId());

        Instant joinedAt = Instant.now();
        room.getParticipants().add(new RoomParticipantEmbedded(scope.userId(), joinedAt, null, null));
        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(
            correlationId,
            "ParticipantJoinedRoom",
            savedRoom.getId() + ":" + scope.userId()
        );
        ParticipantJoinedRoomEvent event = new ParticipantJoinedRoomEvent(
            eventId,
            joinedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            joinedAt,
            savedRoom.getParticipants().size()
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "ParticipantJoinedRoom",
            event,
            correlationId,
            event.eventId()
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument setParticipantGoal(String roomId, String goal) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);

        if (!StringUtils.hasText(goal)) {
            throw new IllegalStateException("Participant goal must not be blank");
        }

        if (room.getStatus() != RoomStatus.OPEN && room.getStatus() != RoomStatus.LOCKED) {
            throw new IllegalStateException(REVEAL_PRECONDITIONS_NOT_MET);
        }

        RoomParticipantEmbedded participant = findParticipant(room, scope.userId())
            .orElseThrow(() -> new IllegalStateException(PARTICIPANT_NOT_FOUND));

        String normalizedGoal = goal.trim();
        if (participant.getGoal() != null && normalizedGoal.equals(participant.getGoal().getText())) {
            return room;
        }

        Instant setAt = Instant.now();
        participant.setGoal(new ParticipantGoalEmbedded(normalizedGoal, setAt));
        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(
            correlationId,
            "ParticipantGoalSet",
            savedRoom.getId() + ":" + scope.userId()
        );
        ParticipantGoalSetEvent event = new ParticipantGoalSetEvent(
            eventId,
            setAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            normalizedGoal,
            setAt
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "ParticipantGoalSet",
            event,
            correlationId,
            event.eventId()
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument lockRoom(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);

        if (room.getStatus() == RoomStatus.LOCKED) {
            return room;
        }

        if (!roomModerationService.canLockRoom(room, scope.userId())) {
            if (!scope.userId().equals(room.getHostUserId())) {
                throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
            }
            throw new IllegalStateException(ApiErrorCode.ROOM_LOCKED.name());
        }

        Instant lockedAt = Instant.now();
        room.setStatus(RoomStatus.LOCKED);
        room.setLockedAt(lockedAt);
        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "RoomLocked", savedRoom.getId());
        RoomLockedEvent event = new RoomLockedEvent(
            eventId,
            lockedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            lockedAt
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "RoomLocked",
            event,
            correlationId,
            event.eventId()
        );

        auditLogService.logModerationAction(
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            "ROOM_LOCKED",
            "Host locked room",
            correlationId
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument startRoomSession(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);
        ensureHost(scope, room);

        if (room.getStatus() == RoomStatus.IN_SESSION) {
            return room;
        }

        if (room.getStatus() != RoomStatus.OPEN && room.getStatus() != RoomStatus.LOCKED) {
            throw new IllegalStateException(REVEAL_PRECONDITIONS_NOT_MET);
        }

        Instant startedAt = Instant.now();
        if (room.getStatus() == RoomStatus.OPEN && room.getLockedAt() == null) {
            room.setLockedAt(startedAt);
        }
        room.setStatus(RoomStatus.IN_SESSION);
        room.setStartedAt(startedAt);

        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "RoomSessionStarted", savedRoom.getId());
        RoomSessionStartedEvent event = new RoomSessionStartedEvent(
            eventId,
            startedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            startedAt
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "RoomSessionStarted",
            event,
            correlationId,
            event.eventId()
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument submitParticipantResult(String roomId, String summary, Integer deepWorkMinutes) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);

        if (room.getStatus() != RoomStatus.IN_SESSION) {
            throw new IllegalStateException(REVEAL_PRECONDITIONS_NOT_MET);
        }

        if (!StringUtils.hasText(summary)) {
            throw new IllegalStateException("Result summary must not be blank");
        }

        RoomParticipantEmbedded participant = findParticipant(room, scope.userId())
            .orElseThrow(() -> new IllegalStateException(PARTICIPANT_NOT_FOUND));

        if (participant.getResultSummary() != null) {
            return room;
        }

        Instant submittedAt = Instant.now();
        Integer normalizedDeepWorkMinutes = normalizeDeepWorkMinutes(deepWorkMinutes);
        participant.setResultSummary(new ResultSummaryEmbedded(summary.trim(), normalizedDeepWorkMinutes, submittedAt));

        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(
            correlationId,
            "ParticipantResultSubmitted",
            savedRoom.getId() + ":" + scope.userId()
        );
        ParticipantResultSubmittedEvent event = new ParticipantResultSubmittedEvent(
            eventId,
            submittedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            summary.trim(),
            normalizedDeepWorkMinutes,
            submittedAt
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "ParticipantResultSubmitted",
            event,
            correlationId,
            event.eventId()
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument revealRoomResults(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);

        if (room.getStatus() == RoomStatus.REVEAL) {
            return room;
        }

        if (!roomModerationService.canRevealResults(room, scope.userId())) {
            if (!scope.userId().equals(room.getHostUserId())) {
                throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
            }
            throw new IllegalStateException(REVEAL_PRECONDITIONS_NOT_MET);
        }

        Instant revealedAt = Instant.now();
        room.setStatus(RoomStatus.REVEAL);
        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        long submittedCount = savedRoom.getParticipants().stream()
            .filter(participant -> participant.getResultSummary() != null)
            .count();

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "RoomResultsRevealed", savedRoom.getId());
        RoomResultsRevealedEvent event = new RoomResultsRevealedEvent(
            eventId,
            revealedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            savedRoom.getParticipants().size(),
            (int) submittedCount
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "RoomResultsRevealed",
            event,
            correlationId,
            event.eventId()
        );

        auditLogService.logModerationAction(
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            "ROOM_RESULTS_REVEALED",
            "Host revealed room results",
            correlationId
        );

        return savedRoom;
    }

    @Transactional
    public AccountabilityRoomDocument closeRoom(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = loadRoomInScope(roomId, scope);
        ensureHost(scope, room);

        if (room.getStatus() == RoomStatus.CLOSED) {
            return room;
        }

        if (room.getStatus() == RoomStatus.IN_SESSION) {
            throw new IllegalStateException(REVEAL_PRECONDITIONS_NOT_MET);
        }

        Instant closedAt = Instant.now();
        room.setStatus(RoomStatus.CLOSED);
        room.setClosedAt(closedAt);
        AccountabilityRoomDocument savedRoom = accountabilityRoomRepository.save(room);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "RoomClosed", savedRoom.getId());
        RoomClosedEvent event = new RoomClosedEvent(
            eventId,
            closedAt,
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            closedAt
        );

        publishRoomEvent(
            event.eventId(),
            savedRoom.getId(),
            "RoomClosed",
            event,
            correlationId,
            event.eventId()
        );

        auditLogService.logModerationAction(
            scope.tenantId(),
            scope.orgId(),
            savedRoom.getId(),
            scope.userId(),
            "ROOM_CLOSED",
            "Host closed room",
            correlationId
        );

        return savedRoom;
    }

    private AccountabilityRoomDocument loadRoomInScope(String roomId, TenantScope scope) {
        return accountabilityRoomRepository
            .findByIdAndTenantIdAndOrgId(roomId, scope.tenantId(), scope.orgId())
            .orElseThrow(() -> new IllegalStateException("Room not found for current tenant/org scope"));
    }

    private Optional<RoomParticipantEmbedded> findParticipant(AccountabilityRoomDocument room, String userId) {
        return room.getParticipants().stream()
            .filter(participant -> userId.equals(participant.getUserId()))
            .findFirst();
    }

    private boolean isParticipant(AccountabilityRoomDocument room, String userId) {
        return findParticipant(room, userId).isPresent();
    }

    private void ensureHost(TenantScope scope, AccountabilityRoomDocument room) {
        if (!scope.userId().equals(room.getHostUserId())) {
            throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
        }
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalStateException("Room title must not be blank");
        }
        return title.trim();
    }

    private Integer normalizeCapacity(Integer capacity) {
        if (capacity == null) {
            return 8;
        }
        return Math.clamp(capacity, 2, 100);
    }

    private Integer normalizeDeepWorkMinutes(Integer deepWorkMinutes) {
        if (deepWorkMinutes == null) {
            return null;
        }
        return Math.max(0, deepWorkMinutes);
    }

    private void publishRoomEvent(
        String eventId,
        String roomId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId
    ) {
        outboxPublisherService.enqueueWithEventId(
            eventId,
            AGGREGATE_TYPE,
            roomId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            causationId
        );
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize accountability room event payload", ex);
        }
    }

    private String deterministicEventId(String correlationId, String eventType, String roomKey) {
        String material = correlationId + ":" + eventType + ":" + roomKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String preferredCorrelationId) {
        if (StringUtils.hasText(preferredCorrelationId)) {
            return preferredCorrelationId;
        }

        String correlationFromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(correlationFromContext)) {
            return correlationFromContext;
        }

        return UUID.randomUUID().toString();
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
