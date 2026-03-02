package io.ostec.focusflow.document.insights;

import io.ostec.focusflow.document.common.AuditedDocument;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Document(collection = "kpi_snapshots")
@CompoundIndexes({
    @CompoundIndex(
        name = "tenant_org_kpi_computed_desc",
        def = "{'tenantId': 1, 'orgId': 1, 'kpiKey': 1, 'computedAt': -1}"
    ),
    @CompoundIndex(
        name = "tenant_org_kpi_window",
        def = "{'tenantId': 1, 'orgId': 1, 'kpiKey': 1, 'windowStart': 1, 'windowEnd': 1}"
    )
})
public class KpiSnapshotDocument extends AuditedDocument {

    @Id
    private String id;

    private String tenantId;
    private String orgId;
    private String kpiKey;
    private Instant windowStart;
    private Instant windowEnd;
    private Double value;
    private Map<String, String> dimensions = new LinkedHashMap<>();
    private Instant computedAt;

    public KpiSnapshotDocument() {
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

    public String getKpiKey() {
        return kpiKey;
    }

    public void setKpiKey(String kpiKey) {
        this.kpiKey = kpiKey;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Double getValue() {
        return value;
    }

    public void setValue(Double value) {
        this.value = value;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions != null ? dimensions : new LinkedHashMap<>();
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
