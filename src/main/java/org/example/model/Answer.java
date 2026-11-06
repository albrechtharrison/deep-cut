package org.example.model;

import jakarta.persistence.*;

@Entity
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private PlayerSession session;

    private int questionNumber;
    private String rawInput;
    private String correctedName;
    private int scoutScore;
    private int points;

    @Column(length = 1000)
    private String scoutReason;

    public Answer() {}

    public Answer(PlayerSession session, int questionNumber, String rawInput) {
        this.session = session;
        this.questionNumber = questionNumber;
        this.rawInput = rawInput;
    }

    public Long getId() { return id; }
    public PlayerSession getSession() { return session; }
    public int getQuestionNumber() { return questionNumber; }
    public String getRawInput() { return rawInput; }
    public String getCorrectedName() { return correctedName; }
    public int getScoutScore() { return scoutScore; }
    public int getPoints() { return points; }
    public String getScoutReason() { return scoutReason; }

    public void setCorrectedName(String correctedName) { this.correctedName = correctedName; }
    public void setScoutScore(int scoutScore) { this.scoutScore = scoutScore; }
    public void setPoints(int points) { this.points = points; }
    public void setScoutReason(String scoutReason) { this.scoutReason = scoutReason; }
}
