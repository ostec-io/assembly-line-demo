package io.ostec.focusflow.event.outbox;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "outbox_events")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_org_status_occurred", def = "{'tenantId': 1, 'orgId': 1, 'status': 1, 'occurredAt': 1}"),
    @CompoundIndex(name = "event_id_unique", def = "{'eventId': 1}", unique = true)
})
public class OutboxEventDocument extends AuditedDocument {

    @Id
    private String id;

    private String eventId;
    private String eventType;
    private String eventVersion;
    private Instant occurredAt;
    private String producer;
    private String correlationId;
    private String causationId;
    private String tenantId;
    private String orgId;
    private String aggregateType;
    private String aggregateId;
    private String payload;
    private OutboxStatus status;
    private Instant publishedAt;

    public OutboxEventDocument() {
    }

    public static OutboxEventDocument pending(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String producer,
        String correlationId,
        String causationId,
        String tenantId,
        String orgId,
        String aggregateType,
        String aggregateId,
        String payload
    ) {
        OutboxEventDocument document = new OutboxEventDocument();
        document.eventId = eventId;
        document.eventType = eventType;
        document.eventVersion = eventVersion;
        document.occurredAt = occurredAt;
        document.producer = producer;
        document.correlationId = correlationId;
        document.causationId = causationId;
        document.tenantId = tenantId;
        document.orgId = orgId;
        document.aggregateType = aggregateType;
        document.aggregateId = aggregateId;
        document.payload = payload;
        document.status = OutboxStatus.PENDING;
        return document;
    }

    public void markPublished(Instant publishedInstant) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = publishedInstant;
    }

    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEventVersion() {
        return eventVersion;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getProducer() {
        return producer;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCausationId() {
        return causationId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrgId() {
        return orgId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public enum OutboxStatus {
        PENDING,
        PUBLISHED,
        FAILED
    }
}
