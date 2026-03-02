package io.ostec.focusflow.document.identity;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "auth_session_audits")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_issued_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'issuedAt': -1}"
    )
})
public class AuthSessionAuditDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String userId;
    private String eventType;
    private Instant issuedAt;
    private String ipHash;
    private String userAgentHash;

    public AuthSessionAuditDocument() {
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getIpHash() {
        return ipHash;
    }

    public void setIpHash(String ipHash) {
        this.ipHash = ipHash;
    }

    public String getUserAgentHash() {
        return userAgentHash;
    }

    public void setUserAgentHash(String userAgentHash) {
        this.userAgentHash = userAgentHash;
    }
}
