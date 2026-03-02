package io.ostec.focusflow.document.session;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "focus_sessions")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_org_user_status", def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'status': 1}"),
    @CompoundIndex(name = "tenant_org_user_created_desc", def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "tenant_org_room_status", def = "{'tenantId': 1, 'orgId': 1, 'roomId': 1, 'status': 1}"),
    @CompoundIndex(
        name = "tenant_org_user_active_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'status': 1}",
        unique = true,
        partialFilter = "{'status': 'ACTIVE'}"
    )
})
public class FocusSessionDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private SessionGoalEmbedded goal;
    private Integer durationMinutes;
    private FocusSessionStatus status;
    private String planId;
    private String roomId;
    private Instant startedAt;
    private Instant endedAt;
    private List<MidSessionCheckInEmbedded> checkIns = new ArrayList<>();
    private List<InterruptionSignalEmbedded> interruptions = new ArrayList<>();
    private CompletionSummaryEmbedded completionSummary;

    public FocusSessionDocument() {
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

    public SessionGoalEmbedded getGoal() {
        return goal;
    }

    public void setGoal(SessionGoalEmbedded goal) {
        this.goal = goal;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public FocusSessionStatus getStatus() {
        return status;
    }

    public void setStatus(FocusSessionStatus status) {
        this.status = status;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public List<MidSessionCheckInEmbedded> getCheckIns() {
        return checkIns;
    }

    public void setCheckIns(List<MidSessionCheckInEmbedded> checkIns) {
        this.checkIns = checkIns != null ? checkIns : new ArrayList<>();
    }

    public List<InterruptionSignalEmbedded> getInterruptions() {
        return interruptions;
    }

    public void setInterruptions(List<InterruptionSignalEmbedded> interruptions) {
        this.interruptions = interruptions != null ? interruptions : new ArrayList<>();
    }

    public CompletionSummaryEmbedded getCompletionSummary() {
        return completionSummary;
    }

    public void setCompletionSummary(CompletionSummaryEmbedded completionSummary) {
        this.completionSummary = completionSummary;
    }
}
