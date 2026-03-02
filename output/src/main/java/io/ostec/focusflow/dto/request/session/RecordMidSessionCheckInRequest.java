package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecordMidSessionCheckInRequest(
    @NotBlank
    String status,
    @Size(max = 500)
    String note
) {
}
