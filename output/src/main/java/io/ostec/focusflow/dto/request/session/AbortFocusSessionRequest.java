package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AbortFocusSessionRequest(
    @NotBlank
    @Size(max = 500)
    String reason
) {
}
