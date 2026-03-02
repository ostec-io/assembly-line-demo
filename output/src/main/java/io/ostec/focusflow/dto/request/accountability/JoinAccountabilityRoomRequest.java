package io.ostec.focusflow.dto.request.accountability;

import jakarta.validation.constraints.NotBlank;

public record JoinAccountabilityRoomRequest(
    @NotBlank
    String roomId
) {
}
