package io.ostec.focusflow.dto.response.accountability;

import io.ostec.focusflow.document.accountability.RoomStatus;
import io.ostec.focusflow.document.accountability.RoomVisibility;

import java.time.Instant;

public record RoomLobbyItemResponse(
    String roomId,
    String title,
    String hostUserId,
    RoomStatus status,
    RoomVisibility visibility,
    Integer capacity,
    Integer participantCount,
    Instant createdAt
) {
}
