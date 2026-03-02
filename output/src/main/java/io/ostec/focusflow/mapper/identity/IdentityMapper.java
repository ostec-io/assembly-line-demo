package io.ostec.focusflow.mapper.identity;

import io.ostec.focusflow.dto.response.identity.AuthSessionAuditResponse;
import io.ostec.focusflow.dto.response.identity.CurrentUserProfileResponse;
import io.ostec.focusflow.service.identity.CurrentUserProfileService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IdentityMapper {

    public CurrentUserProfileResponse toCurrentUserProfileResponse(CurrentUserProfileService.CurrentUserProfileView view) {
        return new CurrentUserProfileResponse(
            view.userId(),
            view.email(),
            view.displayName(),
            view.status() != null ? view.status().name() : null
        );
    }

    public List<AuthSessionAuditResponse> toAuthSessionAuditResponses(List<CurrentUserProfileService.AuthEventView> authEvents) {
        if (authEvents == null || authEvents.isEmpty()) {
            return List.of();
        }

        return authEvents.stream()
            .map(this::toAuthSessionAuditResponse)
            .toList();
    }

    public AuthSessionAuditResponse toAuthSessionAuditResponse(CurrentUserProfileService.AuthEventView authEvent) {
        return new AuthSessionAuditResponse(
            authEvent.eventType(),
            authEvent.issuedAt(),
            authEvent.ipHash(),
            authEvent.userAgentHash()
        );
    }
}
