package io.ostec.focusflow.document.entitlement;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "subscription_accounts")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1}",
        unique = true
    )
})
public class SubscriptionAccountDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private SubscriptionPlan plan;
    private SubscriptionState state;
    private Instant cycleStart;
    private Instant cycleEnd;
    private List<String> entitlements = new ArrayList<>();
    private String lastWebhookEventId;

    public SubscriptionAccountDocument() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    public SubscriptionPlan getPlan() {
        return plan;
    }

    public void setPlan(SubscriptionPlan plan) {
        this.plan = plan;
    }

    public SubscriptionState getState() {
        return state;
    }

    public void setState(SubscriptionState state) {
        this.state = state;
    }

    public Instant getCycleStart() {
        return cycleStart;
    }

    public void setCycleStart(Instant cycleStart) {
        this.cycleStart = cycleStart;
    }

    public Instant getCycleEnd() {
        return cycleEnd;
    }

    public void setCycleEnd(Instant cycleEnd) {
        this.cycleEnd = cycleEnd;
    }

    public List<String> getEntitlements() {
        return entitlements;
    }

    public void setEntitlements(List<String> entitlements) {
        this.entitlements = entitlements != null ? entitlements : new ArrayList<>();
    }

    public String getLastWebhookEventId() {
        return lastWebhookEventId;
    }

    public void setLastWebhookEventId(String lastWebhookEventId) {
        this.lastWebhookEventId = lastWebhookEventId;
    }
}
