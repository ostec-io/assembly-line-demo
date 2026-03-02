package io.ostec.focusflow.service.accountability;

import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomParticipantEmbedded;
import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.accountability.AccountabilityRoomRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AccountabilityRoomQueryService {

    private final AccountabilityRoomRepository accountabilityRoomRepository;

    public AccountabilityRoomQueryService(AccountabilityRoomRepository accountabilityRoomRepository) {
        this.accountabilityRoomRepository = accountabilityRoomRepository;
    }

    public Page<AccountabilityRoomDocument> listOpenPublicRooms(Pageable pageable) {
        TenantScope scope = resolveScope();
        Pageable effectivePageable = pageable != null
            ? pageable
            : PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        return accountabilityRoomRepository.findByTenantIdAndOrgIdAndVisibilityAndStatus(
            scope.tenantId(),
            scope.orgId(),
            RoomVisibility.PUBLIC,
            RoomStatus.OPEN,
            effectivePageable
        );
    }

    public Page<AccountabilityRoomDocument> listOpenPublicRooms(Integer page, Integer size) {
        int resolvedPage = page != null ? Math.max(page, 0) : 0;
        int resolvedSize = size != null ? Math.clamp(size, 1, 200) : 20;
        PageRequest pageable = PageRequest.of(resolvedPage, resolvedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return listOpenPublicRooms(pageable);
    }

    public RoomLivePresence getRoomLivePresence(String roomId) {
        TenantScope scope = resolveScope();
        AccountabilityRoomDocument room = accountabilityRoomRepository
            .findByIdAndTenantIdAndOrgId(roomId, scope.tenantId(), scope.orgId())
            .orElseThrow(() -> new IllegalStateException("Room not found for current tenant/org scope"));

        List<ParticipantPresence> participants = room.getParticipants().stream()
            .map(this::toParticipantPresence)
            .toList();

        long submittedCount = participants.stream()
            .filter(ParticipantPresence::hasSubmittedResult)
            .count();

        return new RoomLivePresence(
            scope.tenantId(),
            scope.orgId(),
            room.getId(),
            room.getStatus(),
            room.getVisibility(),
            room.getHostUserId(),
            room.getStartedAt(),
            participants.size(),
            (int) submittedCount,
            participants
        );
    }

    private ParticipantPresence toParticipantPresence(RoomParticipantEmbedded participant) {
        Integer deepWorkMinutes = participant.getResultSummary() != null
            ? participant.getResultSummary().getDeepWorkMinutes()
            : null;

        return new ParticipantPresence(
            participant.getUserId(),
            participant.getJoinedAt(),
            participant.getGoal() != null,
            participant.getResultSummary() != null,
            deepWorkMinutes
        );
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

    public record RoomLivePresence(
        String tenantId,
        String orgId,
        String roomId,
        RoomStatus status,
        RoomVisibility visibility,
        String hostUserId,
        Instant startedAt,
        int participantCount,
        int submittedResultCount,
        List<ParticipantPresence> participants
    ) {
    }

    public record ParticipantPresence(
        String userId,
        Instant joinedAt,
        boolean hasGoal,
        boolean hasSubmittedResult,
        Integer deepWorkMinutes
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
