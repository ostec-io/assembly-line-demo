package io.ostec.focusflow.dto.request.session;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompleteFocusSessionRequest(
    @NotBlank
    @Size(max = 2000)
    String summary,
    @Min(0)
    Integer deepWorkMinutes
) {
}
