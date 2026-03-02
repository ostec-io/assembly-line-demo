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

@Document(collection = "daily_performance_records")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_user_date_unique",
        def = "{'tenantId': 1, 'orgId': 1, 'userId': 1, 'date': 1}",
        unique = true
    )
})
public class DailyPerformanceRecordDocument extends AuditedDocument {

    @Id
    private String id;

    @Version
    private Long version;

    private String tenantId;
    private String orgId;
    private String userId;
    private LocalDate date;
    private Integer focusScore;
    private Integer deepWorkMinutes;
    private Double completionRate;
    private Double distractionRate;
    private String formulaVersion;
    private DailyRecordState state;
    private List<String> processedEventIds = new ArrayList<>();

    public DailyPerformanceRecordDocument() {
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

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getFocusScore() {
        return focusScore;
    }

    public void setFocusScore(Integer focusScore) {
        this.focusScore = focusScore;
    }

    public Integer getDeepWorkMinutes() {
        return deepWorkMinutes;
    }

    public void setDeepWorkMinutes(Integer deepWorkMinutes) {
        this.deepWorkMinutes = deepWorkMinutes;
    }

    public Double getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(Double completionRate) {
        this.completionRate = completionRate;
    }

    public Double getDistractionRate() {
        return distractionRate;
    }

    public void setDistractionRate(Double distractionRate) {
        this.distractionRate = distractionRate;
    }

    public String getFormulaVersion() {
        return formulaVersion;
    }

    public void setFormulaVersion(String formulaVersion) {
        this.formulaVersion = formulaVersion;
    }

    public DailyRecordState getState() {
        return state;
    }

    public void setState(DailyRecordState state) {
        this.state = state;
    }

    public List<String> getProcessedEventIds() {
        return processedEventIds;
    }

    public void setProcessedEventIds(List<String> processedEventIds) {
        this.processedEventIds = processedEventIds != null ? processedEventIds : new ArrayList<>();
    }
}
