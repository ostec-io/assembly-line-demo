package io.ostec.focusflow.document.insights;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "projection_lag")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_stream_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'stream': 1}",
        unique = true
    ),
    @CompoundIndex(
        name = "tenant_org_lag_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'lagSeconds': -1}"
    )
})
public class ProjectionLagDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String stream;
    private Instant lastEventAt;
    private Long lagSeconds;

    public ProjectionLagDocument() {
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

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public void setLastEventAt(Instant lastEventAt) {
        this.lastEventAt = lastEventAt;
    }

    public Long getLagSeconds() {
        return lagSeconds;
    }

    public void setLagSeconds(Long lagSeconds) {
        this.lagSeconds = lagSeconds;
    }
}
