package io.ostec.focusflow.dto.request.accountability;

import io.ostec.focusflow.document.accountability.RoomVisibility;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAccountabilityRoomRequest(
    @NotBlank
    String title,
    @NotNull
    RoomVisibility visibility,
    @Min(2)
    @Max(50)
    Integer capacity
) {
}
