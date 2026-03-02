package io.ostec.focusflow.document.session;

import java.time.Instant;

public class MidSessionCheckInEmbedded {

    private Instant checkedAt;
    private String note;
    private Integer focusScore;

    public MidSessionCheckInEmbedded() {
    }

    public MidSessionCheckInEmbedded(Instant checkedAt, String note, Integer focusScore) {
        this.checkedAt = checkedAt;
        this.note = note;
        this.focusScore = focusScore;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(Instant checkedAt) {
        this.checkedAt = checkedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Integer getFocusScore() {
        return focusScore;
    }

    public void setFocusScore(Integer focusScore) {
        this.focusScore = focusScore;
    }
}
