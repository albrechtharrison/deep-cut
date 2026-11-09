package org.example.service;

/** Rank titles for a day's score (max 500), from youth hockey to the Hall of Fame. */
public final class Rungs {
    private static final int[] MIN = {0, 75, 125, 175, 225, 275, 325, 375, 425};
    private static final String[] NAME = {"Youth Hockey", "High School / AAA", "Junior Hockey", "College Hockey",
            "Semi Pro", "Minor Pro", "Euro Pro", "NHL", "Hall of Famer"};

    private Rungs() {}

    public static String titleFor(int points) {
        String title = NAME[0];
        for (int i = 0; i < MIN.length; i++) if (points >= MIN[i]) title = NAME[i];
        return title;
    }
}
