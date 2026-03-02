package io.ostec.focusflow.service.identity;

import io.ostec.focusflow.document.identity.AuthSessionAuditDocument;
import io.ostec.focusflow.document.identity.UserProfileShadowDocument;
import io.ostec.focusflow.document.identity.UserProfileStatus;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.identity.AuthSessionAuditRepository;
import io.ostec.focusflow.repository.identity.UserProfileShadowRepository;
import io.ostec.foundation.security.principal.AuthUser;
import io.ostec.foundation.security.principal.SecurityContextHelper;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CurrentUserProfileService {

    private final UserProfileShadowRepository userProfileShadowRepository;
    private final AuthSessionAuditRepository authSessionAuditRepository;

    public CurrentUserProfileService(
        UserProfileShadowRepository userProfileShadowRepository,
        AuthSessionAuditRepository authSessionAuditRepository
    ) {
        this.userProfileShadowRepository = userProfileShadowRepository;
        this.authSessionAuditRepository = authSessionAuditRepository;
    }

    public CurrentUserProfileView getCurrentUserProfile() {
        TenantScope scope = resolveScope();

        Optional<UserProfileShadowDocument> shadow = userProfileShadowRepository.findByTenantIdAndOrgIdAndUserId(
            scope.tenantId(),
            scope.orgId(),
            scope.userId()
        );

        List<AuthSessionAuditDocument> recentAudits = authSessionAuditRepository.findTop50ByTenantIdAndOrgIdAndUserIdOrderByIssuedAtDesc(
            scope.tenantId(),
            scope.orgId(),
            scope.userId()
        );

        String fallbackEmail = normalize(SecurityContextHelper.getCurrentEmail());
        String fallbackDisplayName = SecurityContextHelper.getCurrentUser()
            .map(AuthUser::getEffectiveDisplayName)
            .filter(StringUtils::hasText)
            .map(String::trim)
            .orElse(null);

        String email = shadow
            .map(UserProfileShadowDocument::getEmail)
            .filter(StringUtils::hasText)
            .orElse(fallbackEmail);

        String displayName = shadow
            .map(UserProfileShadowDocument::getDisplayName)
            .filter(StringUtils::hasText)
            .orElseGet(() -> StringUtils.hasText(fallbackDisplayName) ? fallbackDisplayName : email);

        UserProfileStatus status = shadow
            .map(UserProfileShadowDocument::getStatus)
            .orElse(UserProfileStatus.ACTIVE);

        Instant lastLoginAt = shadow
            .map(UserProfileShadowDocument::getLastLoginAt)
            .orElseGet(() -> recentAudits.stream()
                .map(AuthSessionAuditDocument::getIssuedAt)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null));

        List<AuthEventView> authEvents = recentAudits.stream()
            .map(this::toAuthEventView)
            .toList();

        return new CurrentUserProfileView(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            normalize(SecurityContextHelper.getCurrentKeycloakUserId()),
            normalize(email),
            normalize(displayName),
            status,
            lastLoginAt,
            authEvents,
            shadow.isPresent()
        );
    }

    private AuthEventView toAuthEventView(AuthSessionAuditDocument auditDocument) {
        return new AuthEventView(
            normalize(auditDocument.getEventType()),
            auditDocument.getIssuedAt(),
            normalize(auditDocument.getIpHash()),
            normalize(auditDocument.getUserAgentHash())
        );
    }

    private TenantScope resolveScope() {
        String tenantId = firstNonBlank(SecurityContextHelper.getCurrentTenantId(), TenantContextHolder.getTenantId());
        String orgId = firstNonBlank(SecurityContextHelper.getCurrentOrgId(), TenantContextHolder.getOrgId());
        String userId = firstNonBlank(SecurityContextHelper.getCurrentUserId(), TenantContextHolder.getUserId());

        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(orgId) || !StringUtils.hasText(userId)) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name());
        }

        return new TenantScope(tenantId, orgId, userId);
    }

    private String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }

        if (StringUtils.hasText(second)) {
            return second.trim();
        }

        return null;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    public record CurrentUserProfileView(
        String tenantId,
        String orgId,
        String userId,
        String keycloakUserId,
        String email,
        String displayName,
        UserProfileStatus status,
        Instant lastLoginAt,
        List<AuthEventView> recentAuthEvents,
        boolean shadowSynchronized
    ) {
    }

    public record AuthEventView(
        String eventType,
        Instant issuedAt,
        String ipHash,
        String userAgentHash
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
