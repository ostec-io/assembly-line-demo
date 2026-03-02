package io.ostec.focusflow.document.accountability;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "accountability_rooms")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_status_visibility_created_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'status': 1, 'visibility': 1, 'createdAt': -1}"
    ),
    @CompoundIndex(name = "tenant_org_host_status", def = "{'tenantId': 1, 'orgId': 1, 'hostUserId': 1, 'status': 1}"),
    @CompoundIndex(name = "tenant_org_participant_user", def = "{'tenantId': 1, 'orgId': 1, 'participants.userId': 1}")
})
public class AccountabilityRoomDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String title;
    private String hostUserId;
    private RoomStatus status;
    private RoomVisibility visibility;
    private Integer capacity;
    private Instant lockedAt;
    private Instant startedAt;
    private Instant closedAt;
    private List<RoomParticipantEmbedded> participants = new ArrayList<>();

    public AccountabilityRoomDocument() {
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getHostUserId() {
        return hostUserId;
    }

    public void setHostUserId(String hostUserId) {
        this.hostUserId = hostUserId;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public RoomVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(RoomVisibility visibility) {
        this.visibility = visibility;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public List<RoomParticipantEmbedded> getParticipants() {
        return participants;
    }

    public void setParticipants(List<RoomParticipantEmbedded> participants) {
        this.participants = participants != null ? participants : new ArrayList<>();
    }
}
