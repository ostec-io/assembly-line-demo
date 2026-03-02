package io.ostec.focusflow.document.entitlement;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "usage_counters")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_feature_date_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'featureKey': 1, 'usageDate': 1}",
        unique = true
    )
})
public class UsageCounterDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private String featureKey;
    private LocalDate usageDate;
    private Integer usageCount;
    private List<String> idempotencyKeys = new ArrayList<>();

    public UsageCounterDocument() {
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

    public String getFeatureKey() {
        return featureKey;
    }

    public void setFeatureKey(String featureKey) {
        this.featureKey = featureKey;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public void setUsageDate(LocalDate usageDate) {
        this.usageDate = usageDate;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }

    public List<String> getIdempotencyKeys() {
        return idempotencyKeys;
    }

    public void setIdempotencyKeys(List<String> idempotencyKeys) {
        this.idempotencyKeys = idempotencyKeys != null ? idempotencyKeys : new ArrayList<>();
    }
}
