package io.ostec.focusflow.event.listener.insights;

import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.insights.AnalyticsIngestionService;
import io.ostec.focusflow.service.insights.AnalyticsIngestionService.IngestionResult;
import io.ostec.focusflow.service.insights.AnalyticsIngestionService.IngestionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

@Component
public class InsightsUniversalEventListener {

    private static final Logger log = LoggerFactory.getLogger(InsightsUniversalEventListener.class);

    private static final Set<String> SUPPORTED_TOPIC_PREFIXES = Set.of(
        "focus.session.v1",
        "focus.sessions.v1",
        "focus.accountability.v1",
        "focus.performance.v1",
        "focus.entitlement.v1",
        "focus.ai.v1",
        "focus.identity.v1"
    );

    private final AnalyticsIngestionService analyticsIngestionService;

    public InsightsUniversalEventListener(AnalyticsIngestionService analyticsIngestionService) {
        this.analyticsIngestionService = analyticsIngestionService;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        if (!isSupportedTopicEvent(envelope)) {
            return;
        }

        IngestionResult result = analyticsIngestionService.ingest(envelope);
        if (result.status() == IngestionStatus.SKIPPED_INVALID) {
            log.warn("Invalid analytics envelope skipped: eventId={}, eventType={}", envelope.eventId(), envelope.eventType());
        }
    }

    private boolean isSupportedTopicEvent(DomainEventEnvelope envelope) {
        if (envelope == null || !StringUtils.hasText(envelope.eventType())) {
            return false;
        }

        String eventType = envelope.eventType().trim().toLowerCase();
        if (eventType.startsWith("focus.insights.v1")) {
            return false;
        }

        return SUPPORTED_TOPIC_PREFIXES.stream().anyMatch(prefix -> eventType.equals(prefix) || eventType.startsWith(prefix + "."));
    }
}
