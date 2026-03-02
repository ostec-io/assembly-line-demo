package io.ostec.focusflow.dto.request.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EditSessionPlanRequest(
    @NotNull
    @Size(min = 1)
    List<@Valid TaskBlockRequest> taskBlocks,
    @NotNull
    Long expectedRevision
) {
}
