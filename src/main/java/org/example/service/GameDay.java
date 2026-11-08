package org.example.service;

import java.time.LocalDate;
import java.time.ZoneId;

/** The game's calendar day. A new day (and new questions) starts at midnight Eastern, not midnight UTC. */
public final class GameDay {
    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    private GameDay() {}

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
