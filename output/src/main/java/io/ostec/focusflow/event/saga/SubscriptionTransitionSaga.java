package io.ostec.focusflow.event.saga;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.service.entitlement.EntitlementDecisionService;
import io.ostec.focusflow.service.entitlement.SubscriptionLifecycleService;
import io.ostec.focusflow.service.entitlement.WebhookSignatureValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class SubscriptionTransitionSaga {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionTransitionSaga.class);

    private final WebhookSignatureValidationService webhookSignatureValidationService;
    private final SubscriptionLifecycleService subscriptionLifecycleService;
    private final EntitlementDecisionService entitlementDecisionService;
    private final ObjectMapper objectMapper;

    public SubscriptionTransitionSaga(
        WebhookSignatureValidationService webhookSignatureValidationService,
        SubscriptionLifecycleService subscriptionLifecycleService,
        EntitlementDecisionService entitlementDecisionService,
        ObjectMapper objectMapper
    ) {
        this.webhookSignatureValidationService = webhookSignatureValidationService;
        this.subscriptionLifecycleService = subscriptionLifecycleService;
        this.entitlementDecisionService = entitlementDecisionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean processWebhook(
        String provider,
        String eventId,
        String eventType,
        String payload,
        String signature,
        String sharedSecret
    ) {
        webhookSignatureValidationService.assertValidSignature(
            provider,
            payload,
            signature,
            sharedSecret
        );

        return processValidatedWebhook(provider, eventId, eventType, payload);
    }

    @Transactional
    public boolean processValidatedWebhook(
        String provider,
        String eventId,
        String eventType,
        String payload
    ) {
        try {
            boolean processed = subscriptionLifecycleService.recordBillingWebhook(
                provider,
                eventId,
                eventType,
                payload
            );

            if (!processed) {
                log.info("Duplicate webhook ignored for provider={} eventId={}", provider, eventId);
                return false;
            }

            // Lifecycle processing emits Subscription*/Entitlement* events via outbox.
            invalidateEntitlementCache(payload);
            return true;
        } catch (IllegalStateException ex) {
            // Compensation path: allow caller/infrastructure retry policy and DLQ handling.
            log.error(
                "Subscription transition saga failed for provider={} eventId={}, allowing downstream retry/DLQ handling",
                provider,
                eventId,
                ex
            );
            throw ex;
        }
    }

    private void invalidateEntitlementCache(String payload) {
        String userId = resolveUserIdFromPayload(payload);
        entitlementDecisionService.invalidateCacheForUser(userId);
    }

    private String resolveUserIdFromPayload(String payload) {
        JsonNode payloadNode = parsePayload(payload);
        return extractText(
            payloadNode,
            "userId",
            "subjectUserId",
            "ownerUserId",
            "subscriberId",
            "customerId",
            "accountId"
        );
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Unable to parse payload while resolving userId for entitlement cache invalidation", ex);
            return objectMapper.createObjectNode();
        }
    }

    private String extractText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }
}
