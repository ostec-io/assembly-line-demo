package io.ostec.focusflow.document.insights;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Document(collection = "analytics_facts")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_event_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'eventId': 1}",
        unique = true
    ),
    @CompoundIndex(
        name = "tenant_org_type_occurred",
        def = "{'tenantId': 1, 'orgId': 1, 'eventType': 1, 'occurredAt': 1}"
    ),
    @CompoundIndex(
        name = "tenant_org_source_occurred",
        def = "{'tenantId': 1, 'orgId': 1, 'sourceContext': 1, 'occurredAt': 1}"
    )
})
public class AnalyticsFactDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String eventId;
    private String eventType;
    private String sourceContext;
    private Instant occurredAt;
    private String userId;
    private Map<String, Object> payload = new LinkedHashMap<>();

    public AnalyticsFactDocument() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSourceContext() {
        return sourceContext;
    }

    public void setSourceContext(String sourceContext) {
        this.sourceContext = sourceContext;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload != null ? payload : new LinkedHashMap<>();
    }
}
