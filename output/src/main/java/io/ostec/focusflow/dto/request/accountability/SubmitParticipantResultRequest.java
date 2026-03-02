package io.ostec.focusflow.dto.request.accountability;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitParticipantResultRequest(
    @NotBlank
    @Size(max = 1500)
    String summary,
    @Min(0)
    Integer deepWorkMinutes
) {
}
