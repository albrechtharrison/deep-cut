package org.example.model;

import jakarta.persistence.*;
import java.time.LocalDate;

/** One saved scout verdict per question per answer, so the same answer is only graded (and paid for) once. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"playDate", "questionNumber", "answerKey"}))
public class GradeCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate playDate;
    private int questionNumber;
    private String answerKey;

    private boolean valid;
    private int score;
    private String playerName;

    @Column(length = 1000)
    private String reason;

    public GradeCache() {}

    public GradeCache(LocalDate playDate, int questionNumber, String answerKey,
                      boolean valid, int score, String playerName, String reason) {
        this.playDate = playDate;
        this.questionNumber = questionNumber;
        this.answerKey = answerKey;
        this.valid = valid;
        this.score = score;
        this.playerName = playerName;
        this.reason = reason;
    }

    public boolean isValid() { return valid; }
    public int getScore() { return score; }
    public String getPlayerName() { return playerName; }
    public String getReason() { return reason; }
}
