package io.ostec.focusflow.document.performance;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "streak_ledgers")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1}",
        unique = true
    )
})
public class StreakLedgerDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private Integer currentStreak;
    private Integer longestStreak;
    private LocalDate lastQualifiedDate;
    private List<String> processedEventIds = new ArrayList<>();

    public StreakLedgerDocument() {
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

    public Integer getCurrentStreak() {
        return currentStreak;
    }

    public void setCurrentStreak(Integer currentStreak) {
        this.currentStreak = currentStreak;
    }

    public Integer getLongestStreak() {
        return longestStreak;
    }

    public void setLongestStreak(Integer longestStreak) {
        this.longestStreak = longestStreak;
    }

    public LocalDate getLastQualifiedDate() {
        return lastQualifiedDate;
    }

    public void setLastQualifiedDate(LocalDate lastQualifiedDate) {
        this.lastQualifiedDate = lastQualifiedDate;
    }

    public List<String> getProcessedEventIds() {
        return processedEventIds;
    }

    public void setProcessedEventIds(List<String> processedEventIds) {
        this.processedEventIds = processedEventIds != null ? processedEventIds : new ArrayList<>();
    }
}
