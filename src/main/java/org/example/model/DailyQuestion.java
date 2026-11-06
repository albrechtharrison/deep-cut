package org.example.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
public class DailyQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate playDate;
    private int questionNumber;

    @Column(length = 1000)
    private String promptText;

    public DailyQuestion() {}

    public DailyQuestion(LocalDate playDate, int questionNumber, String promptText) {
        this.playDate = playDate;
        this.questionNumber = questionNumber;
        this.promptText = promptText;
    }

    public Long getId() { return id; }
    public LocalDate getPlayDate() { return playDate; }
    public int getQuestionNumber() { return questionNumber; }
    public String getPromptText() { return promptText; }
}
