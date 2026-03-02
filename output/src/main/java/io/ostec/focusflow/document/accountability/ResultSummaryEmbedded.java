package io.ostec.focusflow.document.accountability;

import java.time.Instant;

public class ResultSummaryEmbedded {

    private String summary;
    private Integer deepWorkMinutes;
    private Instant submittedAt;

    public ResultSummaryEmbedded() {
    }

    public ResultSummaryEmbedded(String summary, Integer deepWorkMinutes, Instant submittedAt) {
        this.summary = summary;
        this.deepWorkMinutes = deepWorkMinutes;
        this.submittedAt = submittedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getDeepWorkMinutes() {
        return deepWorkMinutes;
    }

    public void setDeepWorkMinutes(Integer deepWorkMinutes) {
        this.deepWorkMinutes = deepWorkMinutes;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }
}
