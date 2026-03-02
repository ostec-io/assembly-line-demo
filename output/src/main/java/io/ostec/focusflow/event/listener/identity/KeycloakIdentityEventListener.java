package io.ostec.focusflow.event.listener.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.identity.IdentityEventSyncService;
import io.ostec.focusflow.service.identity.IdentityEventSyncService.IdentitySyncCommand;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class KeycloakIdentityEventListener {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityEventListener.class);

    private static final Set<String> REGISTRATION_EVENT_TYPES = Set.of(
        "REGISTER",
        "USER_REGISTER",
        "USER_REGISTERED",
        "CREATE_USER",
        "USER_CREATED",
        "ADMIN_CREATE_USER"
    );

    private static final Set<String> AUTHENTICATION_EVENT_TYPES = Set.of(
        "LOGIN",
        "USER_LOGIN",
        "AUTHENTICATE",
        "AUTHENTICATION",
        "USER_AUTHENTICATED"
    );

    private static final Set<String> TOKEN_EVENT_TYPES = Set.of(
        "CODE_TO_TOKEN",
        "TOKEN",
        "TOKEN_ISSUED",
        "ACCESS_TOKEN_ISSUED",
        "REFRESH_TOKEN",
        "TOKEN_REFRESH",
        "TOKEN_REFRESHED"
    );

    private static final Set<String> SUSPENSION_EVENT_TYPES = Set.of(
        "USER_DISABLED",
        "DISABLE_USER",
        "USER_SUSPENDED",
        "SUSPEND_USER",
        "USER_LOCKED",
        "LOCK_USER",
        "BRUTE_FORCE_USER_DISABLED"
    );

    private static final Set<String> IGNORED_EVENT_TYPES = Set.of(
        "LOGOUT",
        "TOKEN_REVOKED",
        "LOGIN_ERROR",
        "REGISTER_ERROR",
        "REFRESH_TOKEN_ERROR"
    );

    private final IdentityEventSyncService identityEventSyncService;
    private final ObjectMapper objectMapper;

    public KeycloakIdentityEventListener(
        IdentityEventSyncService identityEventSyncService,
        ObjectMapper objectMapper
    ) {
        this.identityEventSyncService = identityEventSyncService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        JsonNode payload = parsePayload(envelope.payload());
        if (!isKeycloakEvent(envelope, payload)) {
            return;
        }

        String rawType = firstNonBlank(envelope.eventType(), extractText(payload, "eventType", "type", "operationType", "action"));
        String canonicalType = canonicalType(rawType);

        MirrorEventType mirrorEventType = mapMirrorType(canonicalType, payload);
        if (mirrorEventType == MirrorEventType.NONE) {
            return;
        }

        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(userId)) {
            log.warn("Skipping keycloak identity event because userId could not be resolved for type={}", rawType);
            return;
        }

        String tenantId = resolveTenantId(payload, envelope);
        String orgId = resolveOrgId(payload, envelope);
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(orgId)) {
            log.warn("Skipping keycloak identity event because tenant/org context is missing for type={}", rawType);
            return;
        }

        IdentitySyncCommand command = new IdentitySyncCommand(
            envelope.eventId(),
            envelope.correlationId(),
            resolveOccurredAt(payload, envelope),
            userId,
            resolveKeycloakUserId(payload, envelope, userId),
            extractText(payload, "email", "userEmail", "preferred_username", "username"),
            extractText(payload, "displayName", "name", "fullName", "given_name"),
            extractText(payload, "ipAddress", "ip", "clientIp", "remoteAddress"),
            extractText(payload, "userAgent", "user_agent", "clientUserAgent", "userAgentHeader"),
            extractText(payload, "jti", "tokenId", "accessTokenId", "sid", "sessionId"),
            extractInstant(payload, "expiresAt", "exp", "expiry", "expiration", "tokenExpiresAt"),
            extractText(payload, "reason", "message", "description", "error", "details")
        );

        withTenantContext(tenantId, orgId, userId, envelope, () -> dispatchMirrorEvent(mirrorEventType, command));
    }

    private void dispatchMirrorEvent(MirrorEventType mirrorEventType, IdentitySyncCommand command) {
        switch (mirrorEventType) {
            case USER_REGISTERED -> identityEventSyncService.syncUserRegistered(command);
            case USER_AUTHENTICATED -> identityEventSyncService.syncUserAuthenticated(command);
            case ACCESS_TOKEN_ISSUED -> identityEventSyncService.syncAccessTokenIssued(command);
            case USER_SUSPENDED -> identityEventSyncService.syncUserSuspended(command);
            case NONE -> {
                // No-op
            }
        }
    }

    private MirrorEventType mapMirrorType(String canonicalType, JsonNode payload) {
        if (!StringUtils.hasText(canonicalType) || IGNORED_EVENT_TYPES.contains(canonicalType)) {
            return MirrorEventType.NONE;
        }

        if (SUSPENSION_EVENT_TYPES.contains(canonicalType) || isSuspensionPayload(payload) || containsAny(canonicalType, "SUSPEND", "DISABLE", "LOCK")) {
            return MirrorEventType.USER_SUSPENDED;
        }

        if (TOKEN_EVENT_TYPES.contains(canonicalType) || containsAny(canonicalType, "TOKEN")) {
            return MirrorEventType.ACCESS_TOKEN_ISSUED;
        }

        if (isUserCreationEvent(canonicalType, payload)
            || REGISTRATION_EVENT_TYPES.contains(canonicalType)
            || containsAny(canonicalType, "REGISTER", "CREATE_USER", "USER_CREATE")) {
            return MirrorEventType.USER_REGISTERED;
        }

        if (AUTHENTICATION_EVENT_TYPES.contains(canonicalType) || containsAny(canonicalType, "LOGIN", "AUTHENTIC")) {
            return MirrorEventType.USER_AUTHENTICATED;
        }

        return MirrorEventType.NONE;
    }

    private boolean isUserCreationEvent(String canonicalType, JsonNode payload) {
        if (!"CREATE".equals(canonicalType)) {
            return false;
        }
        return isUserResource(payload);
    }

    private boolean isUserResource(JsonNode payload) {
        String resourceType = extractText(payload, "resourceType", "resource", "entityType", "resourcePath");
        if (!StringUtils.hasText(resourceType)) {
            return false;
        }

        String normalized = resourceType.trim().toUpperCase(Locale.ROOT);
        return "USER".equals(normalized)
            || normalized.endsWith("/USER")
            || normalized.contains("USERS");
    }

    private boolean isSuspensionPayload(JsonNode payload) {
        Boolean enabled = extractBoolean(payload, "enabled", "isEnabled", "active");
        if (enabled != null && !enabled) {
            return true;
        }

        String status = extractText(payload, "status", "state", "userStatus", "accountStatus");
        if (!StringUtils.hasText(status)) {
            return false;
        }

        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "SUSPENDED".equals(normalized)
            || "DISABLED".equals(normalized)
            || "LOCKED".equals(normalized)
            || "DEPROVISIONED".equals(normalized)
            || "FALSE".equals(normalized);
    }

    private boolean isKeycloakEvent(DomainEventEnvelope envelope, JsonNode payload) {
        String eventType = normalize(envelope.eventType());
        String aggregateType = normalize(envelope.aggregateType());
        String producer = normalize(envelope.producer());

        if (containsAny(eventType, "KEYCLOAK") || containsAny(aggregateType, "KEYCLOAK") || containsAny(producer, "KEYCLOAK")) {
            return true;
        }

        String source = extractText(payload, "identityProvider", "provider", "issuer", "source", "sourceSystem");
        return StringUtils.hasText(source) && "keycloak".equalsIgnoreCase(source.trim());
    }

    private void withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        DomainEventEnvelope envelope,
        Runnable action
    ) {
        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(tenantId)
                .orgId(orgId)
                .userId(userId)
                .correlationId(firstNonBlank(envelope.correlationId(), envelope.eventId(), UUID.randomUUID().toString()))
                .build());
            action.run();
        } finally {
            if (hadPreviousContext && previous != null && previous.hasAnyContext()) {
                TenantContextHolder.setContext(previous);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Unable to parse keycloak identity payload", ex);
            return objectMapper.createObjectNode();
        }
    }

    private Instant resolveOccurredAt(JsonNode payload, DomainEventEnvelope envelope) {
        Instant fromPayload = extractInstant(payload, "occurredAt", "timestamp", "eventTime", "createdAt", "time");
        if (fromPayload != null) {
            return fromPayload;
        }

        if (envelope.occurredAt() != null) {
            return envelope.occurredAt();
        }

        return Instant.now();
    }

    private Instant extractInstant(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.findValue(key);
            Instant parsed = parseInstant(value);
            if (parsed != null) {
                return parsed;
            }
        }

        return null;
    }

    private Instant parseInstant(JsonNode valueNode) {
        if (valueNode == null) {
            return null;
        }

        if (valueNode.isNumber()) {
            long value = valueNode.asLong();
            return value > 100_000_000_000L
                ? Instant.ofEpochMilli(value)
                : Instant.ofEpochSecond(value);
        }

        if (!valueNode.isTextual()) {
            return null;
        }

        String text = valueNode.asText();
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String trimmed = text.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ex) {
            try {
                long value = Long.parseLong(trimmed);
                return value > 100_000_000_000L
                    ? Instant.ofEpochMilli(value)
                    : Instant.ofEpochSecond(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private Boolean extractBoolean(JsonNode node, String... keys) {
        if (node == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.findValue(key);
            if (value == null || value.isNull()) {
                continue;
            }

            if (value.isBoolean()) {
                return value.asBoolean();
            }

            if (value.isTextual()) {
                String text = value.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }

                String normalized = text.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(normalized)) {
                    return Boolean.FALSE;
                }
            }
        }

        return null;
    }

    private String resolveUserId(JsonNode payload, DomainEventEnvelope envelope) {
        String payloadUserId = extractText(
            payload,
            "userId",
            "subjectUserId",
            "ownerUserId",
            "actorUserId",
            "keycloakUserId",
            "sub",
            "id",
            "user_id"
        );

        if (StringUtils.hasText(payloadUserId)) {
            return payloadUserId.trim();
        }

        if (StringUtils.hasText(envelope.aggregateId())) {
            return envelope.aggregateId().trim();
        }

        if (StringUtils.hasText(envelope.producer()) && envelope.producer().contains(":")) {
            String[] segments = envelope.producer().split(":", 2);
            if (segments.length == 2 && StringUtils.hasText(segments[1])) {
                return segments[1].trim();
            }
        }

        return null;
    }

    private String resolveKeycloakUserId(JsonNode payload, DomainEventEnvelope envelope, String fallbackUserId) {
        return firstNonBlank(
            extractText(payload, "keycloakUserId", "sub", "subject", "userUuid"),
            StringUtils.hasText(envelope.aggregateId()) ? envelope.aggregateId() : null,
            fallbackUserId
        );
    }

    private String resolveTenantId(JsonNode payload, DomainEventEnvelope envelope) {
        return firstNonBlank(
            envelope.tenantId(),
            extractText(payload, "tenantId", "tenant", "realm", "realmId")
        );
    }

    private String resolveOrgId(JsonNode payload, DomainEventEnvelope envelope) {
        return firstNonBlank(
            envelope.orgId(),
            extractText(payload, "orgId", "organizationId", "org", "clientOrgId")
        );
    }

    private String extractText(JsonNode node, String... candidateKeys) {
        if (node == null || candidateKeys == null) {
            return null;
        }

        for (String key : candidateKeys) {
            JsonNode value = node.findValue(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }

        return null;
    }

    private String canonicalType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "";
        }

        String value = rawType.trim();
        int lastDotIndex = value.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < value.length() - 1) {
            value = value.substring(lastDotIndex + 1);
        }

        if (value.endsWith("Event")) {
            value = value.substring(0, value.length() - "Event".length());
        }

        String canonical = value
            .replace('-', '_')
            .replace(' ', '_')
            .replace(':', '_')
            .replace('/', '_')
            .toUpperCase(Locale.ROOT);

        while (canonical.contains("__")) {
            canonical = canonical.replace("__", "_");
        }

        return canonical;
    }

    private boolean containsAny(String input, String... values) {
        if (!StringUtils.hasText(input) || values == null) {
            return false;
        }

        String normalizedInput = input.toUpperCase(Locale.ROOT);
        for (String value : values) {
            if (StringUtils.hasText(value) && normalizedInput.contains(value.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }

        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }

        return null;
    }

    private enum MirrorEventType {
        USER_REGISTERED,
        USER_AUTHENTICATED,
        ACCESS_TOKEN_ISSUED,
        USER_SUSPENDED,
        NONE
    }
}
