package io.ostec.focusflow.document.accountability;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "room_moderation_audits")
@CompoundIndexes({
    @CompoundIndex(name = "tenant_org_room_created_desc", def = "{'tenantId': 1, 'orgId': 1, 'roomId': 1, 'createdAt': -1}")
})
public class RoomModerationAuditDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String roomId;
    private String actorUserId;
    private String action;
    private String reason;

    public RoomModerationAuditDocument() {
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

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(String actorUserId) {
        this.actorUserId = actorUserId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
