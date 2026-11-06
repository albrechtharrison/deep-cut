package org.example.model;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
public class PlayerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String playerName;
    private LocalDate playDate;
    private boolean locked;

    public PlayerSession() {}

    public PlayerSession(String playerName, LocalDate playDate) {
        this.playerName = playerName;
        this.playDate = playDate;
        this.locked = false;
    }

    public Long getId() { return id; }
    public String getPlayerName() { return playerName; }
    public LocalDate getPlayDate() { return playDate; }
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }
}
