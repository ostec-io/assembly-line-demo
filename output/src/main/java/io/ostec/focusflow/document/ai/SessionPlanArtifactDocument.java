package io.ostec.focusflow.document.ai;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "session_plan_artifacts")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_plan_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'planId': 1}",
        unique = true
    ),
    @CompoundIndex(
        name = "tenant_org_session_revision_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'sessionId': 1, 'revision': -1}"
    ),
    @CompoundIndex(
        name = "tenant_org_session_status",
        def = "{'tenantId': 1, 'orgId': 1, 'sessionId': 1, 'status': 1}"
    ),
    @CompoundIndex(
        name = "tenant_org_session_attached_version",
        def = "{'tenantId': 1, 'orgId': 1, 'sessionId': 1, 'attachedVersion': 1}"
    )
})
public class SessionPlanArtifactDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String planId;
    private String sessionId;
    private String userId;
    private SessionPlanStatus status;
    private String templateVersion;
    private String provider;
    private List<TaskBlockEmbedded> taskBlocks = new ArrayList<>();
    private List<PlanRevisionEmbedded> revisions = new ArrayList<>();
    private Integer revision;
    private Integer attachedVersion;
    private Integer sessionDurationMinutes;
    private Integer durationToleranceMinutes;
    private Instant generatedAt;
    private Instant attachedAt;

    public SessionPlanArtifactDocument() {
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

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public SessionPlanStatus getStatus() {
        return status;
    }

    public void setStatus(SessionPlanStatus status) {
        this.status = status;
    }

    public String getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(String templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public List<TaskBlockEmbedded> getTaskBlocks() {
        return taskBlocks;
    }

    public void setTaskBlocks(List<TaskBlockEmbedded> taskBlocks) {
        this.taskBlocks = taskBlocks != null ? taskBlocks : new ArrayList<>();
    }

    public List<PlanRevisionEmbedded> getRevisions() {
        return revisions;
    }

    public void setRevisions(List<PlanRevisionEmbedded> revisions) {
        this.revisions = revisions != null ? revisions : new ArrayList<>();
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Integer getAttachedVersion() {
        return attachedVersion;
    }

    public void setAttachedVersion(Integer attachedVersion) {
        this.attachedVersion = attachedVersion;
    }

    public Integer getSessionDurationMinutes() {
        return sessionDurationMinutes;
    }

    public void setSessionDurationMinutes(Integer sessionDurationMinutes) {
        this.sessionDurationMinutes = sessionDurationMinutes;
    }

    public Integer getDurationToleranceMinutes() {
        return durationToleranceMinutes;
    }

    public void setDurationToleranceMinutes(Integer durationToleranceMinutes) {
        this.durationToleranceMinutes = durationToleranceMinutes;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Instant getAttachedAt() {
        return attachedAt;
    }

    public void setAttachedAt(Instant attachedAt) {
        this.attachedAt = attachedAt;
    }
}
