package io.ostec.focusflow.document.identity;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "user_profile_shadows")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1}",
        unique = true
    ),
    @CompoundIndex(
        name = "tenant_org_email_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'email': 1}",
        unique = true
    )
})
public class UserProfileShadowDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String userId;
    private String email;
    private String displayName;
    private UserProfileStatus status;
    private Instant lastLoginAt;

    public UserProfileShadowDocument() {
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public UserProfileStatus getStatus() {
        return status;
    }

    public void setStatus(UserProfileStatus status) {
        this.status = status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}
