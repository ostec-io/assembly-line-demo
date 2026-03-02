package io.ostec.focusflow.document.accountability;

import java.time.Instant;

public class ParticipantGoalEmbedded {

    private String text;
    private Instant setAt;

    public ParticipantGoalEmbedded() {
    }

    public ParticipantGoalEmbedded(String text, Instant setAt) {
        this.text = text;
        this.setAt = setAt;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Instant getSetAt() {
        return setAt;
    }

    public void setSetAt(Instant setAt) {
        this.setAt = setAt;
    }
}
