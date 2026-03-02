package io.ostec.focusflow.document.session;

import java.time.Instant;

public class CompletionSummaryEmbedded {

    private String summary;
    private String outcome;
    private Integer satisfactionScore;
    private Instant completedAt;

    public CompletionSummaryEmbedded() {
    }

    public CompletionSummaryEmbedded(String summary, String outcome, Integer satisfactionScore, Instant completedAt) {
        this.summary = summary;
        this.outcome = outcome;
        this.satisfactionScore = satisfactionScore;
        this.completedAt = completedAt;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Integer getSatisfactionScore() {
        return satisfactionScore;
    }

    public void setSatisfactionScore(Integer satisfactionScore) {
        this.satisfactionScore = satisfactionScore;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
