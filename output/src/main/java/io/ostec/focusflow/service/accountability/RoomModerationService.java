package io.ostec.focusflow.service.accountability;

import io.ostec.focusflow.audit.AuditLogService;
import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomModerationAuditDocument;
import io.ostec.focusflow.document.accountability.RoomParticipantEmbedded;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.accountability.RoomModerationAuditRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RoomModerationService {

    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";
    private static final String POLICY_ROOM_ID = "POLICY_STATE";
    private static final String PRIVATE_ROOM_ENTITLEMENT = "accountability.private-room";
    private static final String ENTITLEMENT_POLICY_PREFIX = "entitlement:";

    private final RoomModerationAuditRepository roomModerationAuditRepository;
    private final AuditLogService auditLogService;

    public RoomModerationService(
        RoomModerationAuditRepository roomModerationAuditRepository,
        AuditLogService auditLogService
    ) {
        this.roomModerationAuditRepository = roomModerationAuditRepository;
        this.auditLogService = auditLogService;
    }

    public boolean canLockRoom(AccountabilityRoomDocument room, String actorUserId) {
        return room != null
            && StringUtils.hasText(actorUserId)
            && actorUserId.equals(room.getHostUserId())
            && (room.getStatus() == RoomStatus.OPEN || room.getStatus() == RoomStatus.LOCKED);
    }

    public boolean canRemoveParticipant(AccountabilityRoomDocument room, String actorUserId, String participantUserId) {
        if (room == null
            || !StringUtils.hasText(actorUserId)
            || !StringUtils.hasText(participantUserId)
            || !actorUserId.equals(room.getHostUserId())) {
            return false;
        }

        if (participantUserId.equals(room.getHostUserId())) {
            return false;
        }

        return room.getParticipants().stream()
            .map(RoomParticipantEmbedded::getUserId)
            .anyMatch(participantUserId::equals);
    }

    public boolean canRevealResults(AccountabilityRoomDocument room, String actorUserId) {
        if (room == null || !StringUtils.hasText(actorUserId) || !actorUserId.equals(room.getHostUserId())) {
            return false;
        }

        if (room.getStatus() != RoomStatus.IN_SESSION || room.getParticipants().isEmpty()) {
            return false;
        }

        return room.getParticipants().stream().allMatch(participant ->
            participant.getResultSummary() != null
                && StringUtils.hasText(participant.getResultSummary().getSummary())
        );
    }

    public void assertPrivateRoomAccessAllowed(RoomVisibility visibility, String userId) {
        if (visibility != RoomVisibility.PRIVATE) {
            return;
        }

        TenantScope scope = resolveScope();
        String effectiveUserId = StringUtils.hasText(userId) ? userId : scope.userId();

        RoomModerationAuditDocument entitlementState = roomModerationAuditRepository
            .findByIdAndTenantIdAndOrgId(policyStateId(effectiveUserId), scope.tenantId(), scope.orgId())
            .orElse(null);

        if (entitlementState != null && !ALLOW.equalsIgnoreCase(entitlementState.getAction())) {
            throw new IllegalStateException(ApiErrorCode.ENTITLEMENT_DENIED.name());
        }
    }

    public void upsertEntitlementState(String userId, boolean granted, String reason, String sourceEventId) {
        TenantScope scope = resolveScope();
        String effectiveUserId = StringUtils.hasText(userId) ? userId : scope.userId();
        String entitlementStateId = policyStateId(effectiveUserId);

        RoomModerationAuditDocument entitlementState = roomModerationAuditRepository
            .findByIdAndTenantIdAndOrgId(entitlementStateId, scope.tenantId(), scope.orgId())
            .orElseGet(RoomModerationAuditDocument::new);

        entitlementState.setId(entitlementStateId);
        entitlementState.setTenantId(scope.tenantId());
        entitlementState.setOrgId(scope.orgId());
        entitlementState.setRoomId(POLICY_ROOM_ID);
        entitlementState.setActorUserId(effectiveUserId);
        entitlementState.setAction(granted ? ALLOW : DENY);
        entitlementState.setReason(resolveEntitlementReason(granted, reason, sourceEventId));

        roomModerationAuditRepository.save(entitlementState);

        if (granted) {
            auditLogService.logEntitlementGranted(
                scope.tenantId(),
                scope.orgId(),
                effectiveUserId,
                PRIVATE_ROOM_ENTITLEMENT,
                entitlementState.getReason(),
                sourceEventId,
                TenantContextHolder.getCorrelationId()
            );
        } else {
            auditLogService.logEntitlementRevoked(
                scope.tenantId(),
                scope.orgId(),
                effectiveUserId,
                PRIVATE_ROOM_ENTITLEMENT,
                entitlementState.getReason(),
                sourceEventId,
                TenantContextHolder.getCorrelationId()
            );
        }
    }

    private String resolveEntitlementReason(boolean granted, String reason, String sourceEventId) {
        String effectiveReason = StringUtils.hasText(reason)
            ? reason.trim()
            : (granted ? "Entitlement granted" : "Entitlement revoked");

        if (!StringUtils.hasText(sourceEventId)) {
            return effectiveReason;
        }

        return effectiveReason + " (sourceEventId=" + sourceEventId + ")";
    }

    private String policyStateId(String userId) {
        return ENTITLEMENT_POLICY_PREFIX + userId;
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
