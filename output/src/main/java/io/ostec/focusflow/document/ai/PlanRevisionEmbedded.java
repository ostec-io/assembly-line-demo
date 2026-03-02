package io.ostec.focusflow.document.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PlanRevisionEmbedded {

    private Integer revision;
    private String templateVersion;
    private String provider;
    private Integer totalEstimatedMinutes;
    private Instant revisedAt;
    private List<TaskBlockEmbedded> taskBlocks = new ArrayList<>();

    public PlanRevisionEmbedded() {
    }

    public PlanRevisionEmbedded(
        Integer revision,
        String templateVersion,
        String provider,
        Integer totalEstimatedMinutes,
        Instant revisedAt,
        List<TaskBlockEmbedded> taskBlocks
    ) {
        this.revision = revision;
        this.templateVersion = templateVersion;
        this.provider = provider;
        this.totalEstimatedMinutes = totalEstimatedMinutes;
        this.revisedAt = revisedAt;
        this.taskBlocks = taskBlocks != null ? taskBlocks : new ArrayList<>();
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
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

    public Integer getTotalEstimatedMinutes() {
        return totalEstimatedMinutes;
    }

    public void setTotalEstimatedMinutes(Integer totalEstimatedMinutes) {
        this.totalEstimatedMinutes = totalEstimatedMinutes;
    }

    public Instant getRevisedAt() {
        return revisedAt;
    }

    public void setRevisedAt(Instant revisedAt) {
        this.revisedAt = revisedAt;
    }

    public List<TaskBlockEmbedded> getTaskBlocks() {
        return taskBlocks;
    }

    public void setTaskBlocks(List<TaskBlockEmbedded> taskBlocks) {
        this.taskBlocks = taskBlocks != null ? taskBlocks : new ArrayList<>();
    }
}
