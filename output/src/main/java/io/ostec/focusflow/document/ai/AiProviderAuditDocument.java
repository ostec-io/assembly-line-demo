package io.ostec.focusflow.document.ai;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "ai_provider_audits")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_session_created_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'sessionId': 1, 'createdAt': -1}"
    ),
    @CompoundIndex(
        name = "tenant_org_plan_created_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'planId': 1, 'createdAt': -1}"
    ),
    @CompoundIndex(
        name = "tenant_org_provider_created_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'provider': 1, 'createdAt': -1}"
    )
})
public class AiProviderAuditDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String userId;
    private String sessionId;
    private String planId;
    private String provider;
    private String model;
    private Long latencyMs;
    private Boolean fallbackUsed;
    private String fallbackProvider;
    private Boolean safetyCheckApplied;
    private Boolean safetyFlagged;
    private Boolean safetyBlocked;
    private String safetyReason;
    private String requestId;
    private Instant occurredAt;

    public AiProviderAuditDocument() {
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Boolean getFallbackUsed() {
        return fallbackUsed;
    }

    public void setFallbackUsed(Boolean fallbackUsed) {
        this.fallbackUsed = fallbackUsed;
    }

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(String fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    public Boolean getSafetyCheckApplied() {
        return safetyCheckApplied;
    }

    public void setSafetyCheckApplied(Boolean safetyCheckApplied) {
        this.safetyCheckApplied = safetyCheckApplied;
    }

    public Boolean getSafetyFlagged() {
        return safetyFlagged;
    }

    public void setSafetyFlagged(Boolean safetyFlagged) {
        this.safetyFlagged = safetyFlagged;
    }

    public Boolean getSafetyBlocked() {
        return safetyBlocked;
    }

    public void setSafetyBlocked(Boolean safetyBlocked) {
        this.safetyBlocked = safetyBlocked;
    }

    public String getSafetyReason() {
        return safetyReason;
    }

    public void setSafetyReason(String safetyReason) {
        this.safetyReason = safetyReason;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
