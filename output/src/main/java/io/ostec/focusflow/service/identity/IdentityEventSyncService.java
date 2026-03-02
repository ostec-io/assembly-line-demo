package io.ostec.focusflow.service.identity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.identity.AuthSessionAuditDocument;
import io.ostec.focusflow.document.identity.UserProfileShadowDocument;
import io.ostec.focusflow.document.identity.UserProfileStatus;
import io.ostec.focusflow.event.model.identity.AccessTokenIssuedEvent;
import io.ostec.focusflow.event.model.identity.UserAuthenticatedEvent;
import io.ostec.focusflow.event.model.identity.UserRegisteredEvent;
import io.ostec.focusflow.event.model.identity.UserSuspendedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.identity.AuthSessionAuditRepository;
import io.ostec.focusflow.repository.identity.UserProfileShadowRepository;
import io.ostec.foundation.security.principal.AuthUser;
import io.ostec.foundation.security.principal.SecurityContextHelper;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityEventSyncService {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventSyncService.class);

    private static final String AGGREGATE_TYPE = "UserProfileShadow";
    private static final String EVENT_VERSION = "1";
    private static final String USER_REGISTERED = "UserRegistered";
    private static final String USER_AUTHENTICATED = "UserAuthenticated";
    private static final String ACCESS_TOKEN_ISSUED = "AccessTokenIssued";
    private static final String USER_SUSPENDED = "UserSuspended";

    private final UserProfileShadowRepository userProfileShadowRepository;
    private final AuthSessionAuditRepository authSessionAuditRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    public IdentityEventSyncService(
        UserProfileShadowRepository userProfileShadowRepository,
        AuthSessionAuditRepository authSessionAuditRepository,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper
    ) {
        this.userProfileShadowRepository = userProfileShadowRepository;
        this.authSessionAuditRepository = authSessionAuditRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void syncUserRegistered(IdentitySyncCommand command) {
        TenantScope scope = resolveScope(command.userId());
        Instant occurredAt = resolveOccurredAt(command.occurredAt());
        String sourceEventId = resolveSourceEventId(command.sourceEventId(), USER_REGISTERED, scope, occurredAt);
        String correlationId = resolveCorrelationId(command.correlationId(), sourceEventId);

        UserProfileShadowDocument profile = loadOrCreateProfile(scope);
        mergeProfileIdentity(profile, command.email(), command.displayName());
        profile.setStatus(UserProfileStatus.ACTIVE);
        userProfileShadowRepository.save(profile);

        String eventId = deterministicEventId(scope, USER_REGISTERED, sourceEventId);
        UserRegisteredEvent event = new UserRegisteredEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            resolveKeycloakUserId(command.keycloakUserId(), scope.userId()),
            profile.getEmail(),
            profile.getDisplayName(),
            sourceEventId
        );

        publishEvent(event.eventId(), scope.userId(), USER_REGISTERED, event, correlationId, sourceEventId);
    }

    @Transactional
    public void syncUserAuthenticated(IdentitySyncCommand command) {
        TenantScope scope = resolveScope(command.userId());
        Instant occurredAt = resolveOccurredAt(command.occurredAt());
        String sourceEventId = resolveSourceEventId(command.sourceEventId(), USER_AUTHENTICATED, scope, occurredAt);
        String correlationId = resolveCorrelationId(command.correlationId(), sourceEventId);

        String ipHash = hashValue(command.ipAddress());
        String userAgentHash = hashValue(command.userAgent());

        UserProfileShadowDocument profile = loadOrCreateProfile(scope);
        mergeProfileIdentity(profile, command.email(), command.displayName());
        if (profile.getStatus() == null || profile.getStatus() == UserProfileStatus.INVITED) {
            profile.setStatus(UserProfileStatus.ACTIVE);
        }
        if (profile.getLastLoginAt() == null || occurredAt.isAfter(profile.getLastLoginAt())) {
            profile.setLastLoginAt(occurredAt);
        }
        userProfileShadowRepository.save(profile);

        upsertAudit(scope, USER_AUTHENTICATED, sourceEventId, occurredAt, ipHash, userAgentHash);

        String eventId = deterministicEventId(scope, USER_AUTHENTICATED, sourceEventId);
        UserAuthenticatedEvent event = new UserAuthenticatedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            resolveKeycloakUserId(command.keycloakUserId(), scope.userId()),
            profile.getEmail(),
            ipHash,
            userAgentHash,
            sourceEventId
        );

        publishEvent(event.eventId(), scope.userId(), USER_AUTHENTICATED, event, correlationId, sourceEventId);
    }

    @Transactional
    public void syncAccessTokenIssued(IdentitySyncCommand command) {
        TenantScope scope = resolveScope(command.userId());
        Instant occurredAt = resolveOccurredAt(command.occurredAt());
        String sourceEventId = resolveSourceEventId(command.sourceEventId(), ACCESS_TOKEN_ISSUED, scope, occurredAt);
        String correlationId = resolveCorrelationId(command.correlationId(), sourceEventId);

        String ipHash = hashValue(command.ipAddress());
        String userAgentHash = hashValue(command.userAgent());

        UserProfileShadowDocument profile = loadOrCreateProfile(scope);
        mergeProfileIdentity(profile, command.email(), command.displayName());
        if (profile.getStatus() == null) {
            profile.setStatus(UserProfileStatus.ACTIVE);
        }
        if (profile.getLastLoginAt() == null || occurredAt.isAfter(profile.getLastLoginAt())) {
            profile.setLastLoginAt(occurredAt);
        }
        userProfileShadowRepository.save(profile);

        upsertAudit(scope, ACCESS_TOKEN_ISSUED, sourceEventId, occurredAt, ipHash, userAgentHash);

        String eventId = deterministicEventId(scope, ACCESS_TOKEN_ISSUED, sourceEventId);
        AccessTokenIssuedEvent event = new AccessTokenIssuedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            resolveKeycloakUserId(command.keycloakUserId(), scope.userId()),
            resolveTokenId(command.tokenId()),
            command.tokenExpiresAt(),
            sourceEventId
        );

        publishEvent(event.eventId(), scope.userId(), ACCESS_TOKEN_ISSUED, event, correlationId, sourceEventId);
    }

    @Transactional
    public void syncUserSuspended(IdentitySyncCommand command) {
        TenantScope scope = resolveScope(command.userId());
        Instant occurredAt = resolveOccurredAt(command.occurredAt());
        String sourceEventId = resolveSourceEventId(command.sourceEventId(), USER_SUSPENDED, scope, occurredAt);
        String correlationId = resolveCorrelationId(command.correlationId(), sourceEventId);

        String ipHash = hashValue(command.ipAddress());
        String userAgentHash = hashValue(command.userAgent());

        UserProfileShadowDocument profile = loadOrCreateProfile(scope);
        mergeProfileIdentity(profile, command.email(), command.displayName());
        profile.setStatus(UserProfileStatus.SUSPENDED);
        userProfileShadowRepository.save(profile);

        upsertAudit(scope, USER_SUSPENDED, sourceEventId, occurredAt, ipHash, userAgentHash);

        String reason = normalize(command.reason());
        String eventId = deterministicEventId(scope, USER_SUSPENDED, sourceEventId);
        UserSuspendedEvent event = new UserSuspendedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            resolveKeycloakUserId(command.keycloakUserId(), scope.userId()),
            StringUtils.hasText(reason) ? reason : "Suspended by identity provider",
            sourceEventId
        );

        publishEvent(event.eventId(), scope.userId(), USER_SUSPENDED, event, correlationId, sourceEventId);
    }

    private UserProfileShadowDocument loadOrCreateProfile(TenantScope scope) {
        return userProfileShadowRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElseGet(() -> {
                UserProfileShadowDocument document = new UserProfileShadowDocument();
                document.setTenantId(scope.tenantId());
                document.setOrgId(scope.orgId());
                document.setUserId(scope.userId());
                document.setStatus(UserProfileStatus.INVITED);
                return document;
            });
    }

    private void mergeProfileIdentity(UserProfileShadowDocument profile, String emailCandidate, String displayNameCandidate) {
        String email = firstNonBlank(emailCandidate, SecurityContextHelper.getCurrentEmail(), profile.getEmail());
        if (StringUtils.hasText(email)) {
            profile.setEmail(email);
        }

        String displayName = firstNonBlank(
            displayNameCandidate,
            SecurityContextHelper.getCurrentUser().map(AuthUser::getEffectiveDisplayName).orElse(null),
            profile.getDisplayName(),
            email
        );

        if (StringUtils.hasText(displayName)) {
            profile.setDisplayName(displayName);
        }
    }

    private void upsertAudit(
        TenantScope scope,
        String eventType,
        String sourceEventId,
        Instant occurredAt,
        String ipHash,
        String userAgentHash
    ) {
        String auditId = deterministicAuditId(scope, eventType, sourceEventId);
        if (authSessionAuditRepository.existsById(auditId)) {
            return;
        }

        AuthSessionAuditDocument auditDocument = new AuthSessionAuditDocument();
        auditDocument.setId(auditId);
        auditDocument.setTenantId(scope.tenantId());
        auditDocument.setOrgId(scope.orgId());
        auditDocument.setUserId(scope.userId());
        auditDocument.setEventType(eventType);
        auditDocument.setIssuedAt(occurredAt);
        auditDocument.setIpHash(ipHash);
        auditDocument.setUserAgentHash(userAgentHash);

        try {
            authSessionAuditRepository.save(auditDocument);
        } catch (DuplicateKeyException ex) {
            log.info(
                "Ignoring duplicate identity audit for sourceEventId={}, eventType={}, tenantId={}, orgId={}",
                sourceEventId,
                eventType,
                scope.tenantId(),
                scope.orgId()
            );
        }
    }

    private void publishEvent(
        String eventId,
        String aggregateId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId
    ) {
        outboxPublisherService.enqueueWithEventId(
            eventId,
            AGGREGATE_TYPE,
            aggregateId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            causationId
        );
    }

    private TenantScope resolveScope(String userIdHint) {
        String tenantId = firstNonBlank(TenantContextHolder.getTenantId(), SecurityContextHelper.getCurrentTenantId());
        String orgId = firstNonBlank(TenantContextHolder.getOrgId(), SecurityContextHelper.getCurrentOrgId());
        String userId = firstNonBlank(userIdHint, TenantContextHolder.getUserId(), SecurityContextHelper.getCurrentUserId());

        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(orgId) || !StringUtils.hasText(userId)) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name());
        }

        return new TenantScope(tenantId, orgId, userId);
    }

    private String resolveSourceEventId(String sourceEventId, String eventType, TenantScope scope, Instant occurredAt) {
        if (StringUtils.hasText(sourceEventId)) {
            return sourceEventId.trim();
        }

        String material = scope.tenantId() + ":"
            + scope.orgId() + ":"
            + scope.userId() + ":"
            + eventType + ":"
            + occurredAt.toEpochMilli();

        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String correlationId, String sourceEventId) {
        if (StringUtils.hasText(correlationId)) {
            return correlationId.trim();
        }
        return sourceEventId;
    }

    private String deterministicEventId(TenantScope scope, String eventType, String sourceEventId) {
        String material = scope.tenantId() + ":"
            + scope.orgId() + ":"
            + scope.userId() + ":"
            + eventType + ":"
            + sourceEventId;

        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String deterministicAuditId(TenantScope scope, String eventType, String sourceEventId) {
        String material = scope.tenantId() + ":"
            + scope.orgId() + ":"
            + scope.userId() + ":"
            + "audit:" + eventType + ":"
            + sourceEventId;

        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveTokenId(String tokenId) {
        return firstNonBlank(tokenId, SecurityContextHelper.getCurrentSessionId());
    }

    private String resolveKeycloakUserId(String keycloakUserId, String fallbackUserId) {
        return firstNonBlank(keycloakUserId, SecurityContextHelper.getCurrentKeycloakUserId(), fallbackUserId);
    }

    private Instant resolveOccurredAt(Instant occurredAt) {
        return occurredAt != null ? occurredAt : Instant.now();
    }

    private String hashValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim();
        if (looksLikeSha256(normalized)) {
            return normalized.toLowerCase(Locale.ROOT);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash identity metadata", ex);
        }
    }

    private boolean looksLikeSha256(String value) {
        if (!StringUtils.hasText(value) || value.length() != 64) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean isHex = (ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F');
            if (!isHex) {
                return false;
            }
        }

        return true;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize identity mirror payload", ex);
        }
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return null;
        }

        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }

        return null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record IdentitySyncCommand(
        String sourceEventId,
        String correlationId,
        Instant occurredAt,
        String userId,
        String keycloakUserId,
        String email,
        String displayName,
        String ipAddress,
        String userAgent,
        String tokenId,
        Instant tokenExpiresAt,
        String reason
    ) {
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
