package io.ostec.focusflow.document.accountability;

import java.time.Instant;

public class RoomParticipantEmbedded {

    private String userId;
    private Instant joinedAt;
    private ParticipantGoalEmbedded goal;
    private ResultSummaryEmbedded resultSummary;

    public RoomParticipantEmbedded() {
    }

    public RoomParticipantEmbedded(
        String userId,
        Instant joinedAt,
        ParticipantGoalEmbedded goal,
        ResultSummaryEmbedded resultSummary
    ) {
        this.userId = userId;
        this.joinedAt = joinedAt;
        this.goal = goal;
        this.resultSummary = resultSummary;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public ParticipantGoalEmbedded getGoal() {
        return goal;
    }

    public void setGoal(ParticipantGoalEmbedded goal) {
        this.goal = goal;
    }

    public ResultSummaryEmbedded getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(ResultSummaryEmbedded resultSummary) {
        this.resultSummary = resultSummary;
    }
}
