package io.ostec.focusflow.event.publisher;

import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.event.outbox.OutboxEventDocument;
import io.ostec.focusflow.event.outbox.OutboxEventDocument.OutboxStatus;
import io.ostec.focusflow.event.outbox.OutboxEventRepository;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.foundation.security.principal.SecurityContextHelper;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OutboxPublisherService(
        OutboxEventRepository outboxEventRepository,
        ApplicationEventPublisher applicationEventPublisher
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public void enqueue(
        String aggregateType,
        String aggregateId,
        String eventType,
        String eventVersion,
        String payload,
        String correlationId,
        String causationId
    ) {
        enqueueWithEventId(
            null,
            aggregateType,
            aggregateId,
            eventType,
            eventVersion,
            payload,
            correlationId,
            causationId
        );
    }

    @Transactional
    public void enqueueWithEventId(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String eventVersion,
        String payload,
        String correlationId,
        String causationId
    ) {
        String tenantId = requireTenantId();
        String orgId = requireOrgId();
        String currentUserId = resolveCurrentUserId();

        String effectiveEventId = StringUtils.hasText(eventId) ? eventId : UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        String effectiveCorrelationId = StringUtils.hasText(correlationId) ? correlationId : UUID.randomUUID().toString();
        String effectiveCausationId = StringUtils.hasText(causationId) ? causationId : effectiveEventId;

        OutboxEventDocument eventDocument = OutboxEventDocument.pending(
            effectiveEventId,
            eventType,
            eventVersion,
            occurredAt,
            "focusflow:" + currentUserId,
            effectiveCorrelationId,
            effectiveCausationId,
            tenantId,
            orgId,
            aggregateType,
            aggregateId,
            payload
        );

        try {
            outboxEventRepository.save(eventDocument);
        } catch (DuplicateKeyException ex) {
            log.info(
                "Ignoring duplicate outbox event insert for eventId={}, tenantId={}, orgId={}",
                effectiveEventId,
                tenantId,
                orgId
            );
        }
    }

    @Transactional
    @Scheduled(fixedDelayString = "${focusflow.outbox.publisher-interval-ms:5000}")
    public void publishPending() {
        String tenantId = requireTenantId();
        String orgId = requireOrgId();
        String currentUserId = resolveCurrentUserId();

        List<OutboxEventDocument> pendingEvents = outboxEventRepository
            .findTop100ByTenantIdAndOrgIdAndStatusOrderByOccurredAtAsc(tenantId, orgId, OutboxStatus.PENDING);

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEventDocument pendingEvent : pendingEvents) {
            try {
                applicationEventPublisher.publishEvent(toEnvelope(pendingEvent));
                pendingEvent.markPublished(Instant.now());
            } catch (Exception ex) {
                pendingEvent.markFailed();
                log.error(
                    "Outbox publish failed for eventId={}, tenantId={}, orgId={}, userId={}",
                    pendingEvent.getEventId(),
                    tenantId,
                    orgId,
                    currentUserId,
                    ex
                );
            }
        }

        outboxEventRepository.saveAll(pendingEvents);
    }

    private DomainEventEnvelope toEnvelope(OutboxEventDocument eventDocument) {
        return new DomainEventEnvelope(
            eventDocument.getEventId(),
            eventDocument.getEventType(),
            eventDocument.getEventVersion(),
            eventDocument.getOccurredAt(),
            eventDocument.getProducer(),
            eventDocument.getCorrelationId(),
            eventDocument.getCausationId(),
            eventDocument.getTenantId(),
            eventDocument.getOrgId(),
            eventDocument.getAggregateType(),
            eventDocument.getAggregateId(),
            eventDocument.getPayload()
        );
    }

    private String requireTenantId() {
        try {
            return TenantContextHolder.requireTenantId();
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    private String requireOrgId() {
        try {
            return TenantContextHolder.requireOrgId();
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    private String resolveCurrentUserId() {
        String userId = SecurityContextHelper.getCurrentUserId();
        return StringUtils.hasText(userId) ? userId : "system";
    }
}
