package io.ostec.focusflow.controller.identity;

import io.ostec.focusflow.document.identity.UserProfileShadowDocument;
import io.ostec.focusflow.document.identity.UserProfileStatus;
import io.ostec.focusflow.dto.request.identity.UpdateDisplayNameRequest;
import io.ostec.focusflow.dto.response.identity.AuthSessionAuditResponse;
import io.ostec.focusflow.dto.response.identity.CurrentUserProfileResponse;
import io.ostec.focusflow.mapper.identity.IdentityMapper;
import io.ostec.focusflow.repository.identity.UserProfileShadowRepository;
import io.ostec.focusflow.service.identity.CurrentUserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/me")
@Tag(name = "Current User", description = "Read and update endpoints for the authenticated user profile")
public class CurrentUserController {

    private final CurrentUserProfileService currentUserProfileService;
    private final UserProfileShadowRepository userProfileShadowRepository;
    private final IdentityMapper identityMapper;

    public CurrentUserController(
        CurrentUserProfileService currentUserProfileService,
        UserProfileShadowRepository userProfileShadowRepository,
        IdentityMapper identityMapper
    ) {
        this.currentUserProfileService = currentUserProfileService;
        this.userProfileShadowRepository = userProfileShadowRepository;
        this.identityMapper = identityMapper;
    }

    @GetMapping({"", "/"})
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current authenticated user profile")
    public CurrentUserProfileResponse getCurrentUserProfile() {
        return identityMapper.toCurrentUserProfileResponse(currentUserProfileService.getCurrentUserProfile());
    }

    @PatchMapping("/display-name")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Update current authenticated user's display name")
    public CurrentUserProfileResponse updateDisplayName(@Valid @RequestBody UpdateDisplayNameRequest request) {
        CurrentUserProfileService.CurrentUserProfileView profile = currentUserProfileService.getCurrentUserProfile();
        UserProfileShadowDocument shadow = userProfileShadowRepository
            .findByTenantIdAndOrgIdAndUserId(profile.tenantId(), profile.orgId(), profile.userId())
            .orElseGet(() -> buildNewShadow(profile));

        shadow.setDisplayName(request.displayName().trim());
        userProfileShadowRepository.save(shadow);

        return identityMapper.toCurrentUserProfileResponse(currentUserProfileService.getCurrentUserProfile());
    }

    @GetMapping("/auth-history")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get current authenticated user's recent auth session history")
    public List<AuthSessionAuditResponse> getAuthHistory() {
        CurrentUserProfileService.CurrentUserProfileView profile = currentUserProfileService.getCurrentUserProfile();
        return identityMapper.toAuthSessionAuditResponses(profile.recentAuthEvents());
    }

    private UserProfileShadowDocument buildNewShadow(CurrentUserProfileService.CurrentUserProfileView profile) {
        UserProfileShadowDocument shadow = new UserProfileShadowDocument();
        shadow.setTenantId(profile.tenantId());
        shadow.setOrgId(profile.orgId());
        shadow.setUserId(profile.userId());
        shadow.setStatus(profile.status() != null ? profile.status() : UserProfileStatus.ACTIVE);
        shadow.setLastLoginAt(profile.lastLoginAt());

        if (StringUtils.hasText(profile.email())) {
            shadow.setEmail(profile.email());
        }

        if (StringUtils.hasText(profile.displayName())) {
            shadow.setDisplayName(profile.displayName());
        }

        return shadow;
    }
}
