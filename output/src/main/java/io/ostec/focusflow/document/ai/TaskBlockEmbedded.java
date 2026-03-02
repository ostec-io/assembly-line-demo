package io.ostec.focusflow.document.ai;

public class TaskBlockEmbedded {

    private String blockId;
    private String title;
    private String details;
    private Integer estimateMinutes;
    private Integer sequence;

    public TaskBlockEmbedded() {
    }

    public TaskBlockEmbedded(
        String blockId,
        String title,
        String details,
        Integer estimateMinutes,
        Integer sequence
    ) {
        this.blockId = blockId;
        this.title = title;
        this.details = details;
        this.estimateMinutes = estimateMinutes;
        this.sequence = sequence;
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Integer getEstimateMinutes() {
        return estimateMinutes;
    }

    public void setEstimateMinutes(Integer estimateMinutes) {
        this.estimateMinutes = estimateMinutes;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }
}
