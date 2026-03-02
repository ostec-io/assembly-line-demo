package io.ostec.focusflow.document.performance;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

@Document(collection = "score_correction_audits")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_org_user_date_created_desc", def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'date': 1, 'createdAt': -1}"),
    @CompoundIndex(
        name = "tenant_org_source_event_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'sourceEventId': 1}",
        unique = true
    )
})
public class ScoreCorrectionAuditDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String userId;
    private LocalDate date;
    private Integer previousFocusScore;
    private Integer correctedFocusScore;
    private String reason;
    private String correctedByUserId;
    private String sourceEventId;
    private Instant correctedAt;

    public ScoreCorrectionAuditDocument() {
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

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getPreviousFocusScore() {
        return previousFocusScore;
    }

    public void setPreviousFocusScore(Integer previousFocusScore) {
        this.previousFocusScore = previousFocusScore;
    }

    public Integer getCorrectedFocusScore() {
        return correctedFocusScore;
    }

    public void setCorrectedFocusScore(Integer correctedFocusScore) {
        this.correctedFocusScore = correctedFocusScore;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCorrectedByUserId() {
        return correctedByUserId;
    }

    public void setCorrectedByUserId(String correctedByUserId) {
        this.correctedByUserId = correctedByUserId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public Instant getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(Instant correctedAt) {
        this.correctedAt = correctedAt;
    }
}
