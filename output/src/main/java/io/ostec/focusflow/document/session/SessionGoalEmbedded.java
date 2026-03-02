package io.ostec.focusflow.document.session;

public class SessionGoalEmbedded {

    private String text;

    public SessionGoalEmbedded() {
    }

    public SessionGoalEmbedded(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
