package io.ostec.focusflow.dto.response.identity;

public record CurrentUserProfileResponse(
    String userId,
    String email,
    String displayName,
    String status
) {
}
