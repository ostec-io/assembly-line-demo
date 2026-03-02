package io.ostec.focusflow.document.session;

import java.time.Instant;

public class InterruptionSignalEmbedded {

    private Instant occurredAt;
    private String category;
    private String detail;

    public InterruptionSignalEmbedded() {
    }

    public InterruptionSignalEmbedded(Instant occurredAt, String category, String detail) {
        this.occurredAt = occurredAt;
        this.category = category;
        this.detail = detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
