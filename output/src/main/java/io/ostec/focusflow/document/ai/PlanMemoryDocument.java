package io.ostec.focusflow.document.ai;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Document(collection = "plan_memories")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1}",
        unique = true
    )
})
public class PlanMemoryDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private Long memoryVersion;
    private String templateVersion;
    private String preferredProvider;
    private String lastPlanId;
    private String lastSessionId;
    private List<String> stablePreferences = new ArrayList<>();
    private List<String> avoidPatterns = new ArrayList<>();
    private List<PlanRevisionEmbedded> revisionMemory = new ArrayList<>();

    public PlanMemoryDocument() {
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

    public Long getMemoryVersion() {
        return memoryVersion;
    }

    public void setMemoryVersion(Long memoryVersion) {
        this.memoryVersion = memoryVersion;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getPreferredProvider() {
        return preferredProvider;
    }

    public void setPreferredProvider(String preferredProvider) {
        this.preferredProvider = preferredProvider;
    }

    public String getLastPlanId() {
        return lastPlanId;
    }

    public void setLastPlanId(String lastPlanId) {
        this.lastPlanId = lastPlanId;
    }

    public String getLastSessionId() {
        return lastSessionId;
    }

    public void setLastSessionId(String lastSessionId) {
        this.lastSessionId = lastSessionId;
    }

    public List<String> getStablePreferences() {
        return stablePreferences;
    }

    public void setStablePreferences(List<String> stablePreferences) {
        this.stablePreferences = stablePreferences != null ? stablePreferences : new ArrayList<>();
    }

    public List<String> getAvoidPatterns() {
        return avoidPatterns;
    }

    public void setAvoidPatterns(List<String> avoidPatterns) {
        this.avoidPatterns = avoidPatterns != null ? avoidPatterns : new ArrayList<>();
    }

    public List<PlanRevisionEmbedded> getRevisionMemory() {
        return revisionMemory;
    }

    public void setRevisionMemory(List<PlanRevisionEmbedded> revisionMemory) {
        this.revisionMemory = revisionMemory != null ? revisionMemory : new ArrayList<>();
    }
}
