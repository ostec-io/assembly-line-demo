package io.ostec.focusflow.dto.response.accountability;

import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;

import java.time.Instant;
import java.util.List;

public record RoomResultBoardResponse(
    String roomId,
    RoomStatus status,
    RoomVisibility visibility,
    String hostUserId,
    Instant startedAt,
    Integer participantCount,
    Integer submittedResultCount,
    List<ParticipantResultBoardItemResponse> participants
) {
    public record ParticipantResultBoardItemResponse(
        String userId,
        Instant joinedAt,
        boolean hasGoal,
        boolean hasSubmittedResult,
        Integer deepWorkMinutes
    ) {
    }
}
