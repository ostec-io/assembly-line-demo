package io.ostec.focusflow.mapper.accountability;

import io.ostec.focusflow.document.accountability.AccountabilityRoomDocument;
import io.ostec.focusflow.document.accountability.RoomParticipantEmbedded;
import io.ostec.focusflow.dto.response.accountability.AccountabilityRoomResponse;
import io.ostec.focusflow.dto.response.accountability.RoomLobbyItemResponse;
import io.ostec.focusflow.dto.response.accountability.RoomResultBoardResponse;
import io.ostec.focusflow.service.accountability.AccountabilityRoomQueryService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccountabilityRoomMapper {

    public AccountabilityRoomResponse toAccountabilityRoomResponse(AccountabilityRoomDocument room) {
        int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;
        int goalsSetCount = room.getParticipants() != null
            ? (int) room.getParticipants().stream().filter(this::hasGoal).count()
            : 0;
        int resultsSubmittedCount = room.getParticipants() != null
            ? (int) room.getParticipants().stream().filter(this::hasSubmittedResult).count()
            : 0;

        return new AccountabilityRoomResponse(
            room.getId(),
            room.getTitle(),
            room.getHostUserId(),
            room.getStatus(),
            room.getVisibility(),
            room.getCapacity(),
            room.getLockedAt(),
            room.getStartedAt(),
            room.getClosedAt(),
            room.getCreatedAt(),
            participantCount,
            goalsSetCount,
            resultsSubmittedCount
        );
    }

    public RoomLobbyItemResponse toRoomLobbyItemResponse(AccountabilityRoomDocument room) {
        return new RoomLobbyItemResponse(
            room.getId(),
            room.getTitle(),
            room.getHostUserId(),
            room.getStatus(),
            room.getVisibility(),
            room.getCapacity(),
            room.getParticipants() != null ? room.getParticipants().size() : 0,
            room.getCreatedAt()
        );
    }

    public RoomResultBoardResponse toRoomResultBoardResponse(AccountabilityRoomQueryService.RoomLivePresence presence) {
        List<RoomResultBoardResponse.ParticipantResultBoardItemResponse> participants = presence.participants().stream()
            .map(this::toParticipantResultBoardItemResponse)
            .toList();

        return new RoomResultBoardResponse(
            presence.roomId(),
            presence.status(),
            presence.visibility(),
            presence.hostUserId(),
            presence.startedAt(),
            presence.participantCount(),
            presence.submittedResultCount(),
            participants
        );
    }

    private RoomResultBoardResponse.ParticipantResultBoardItemResponse toParticipantResultBoardItemResponse(
        AccountabilityRoomQueryService.ParticipantPresence participant
    ) {
        return new RoomResultBoardResponse.ParticipantResultBoardItemResponse(
            participant.userId(),
            participant.joinedAt(),
            participant.hasGoal(),
            participant.hasSubmittedResult(),
            participant.deepWorkMinutes()
        );
    }

    private boolean hasGoal(RoomParticipantEmbedded participant) {
        return participant.getGoal() != null;
    }

    private boolean hasSubmittedResult(RoomParticipantEmbedded participant) {
        return participant.getResultSummary() != null;
    }
}
