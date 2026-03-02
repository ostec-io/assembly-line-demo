package io.ostec.focusflow.dto.request.accountability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetParticipantGoalRequest(
    @NotBlank
    @Size(max = 300)
    String goal
) {
}
