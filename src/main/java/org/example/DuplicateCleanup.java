package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs on startup. Removes duplicate rows left by the old double-generation bug, then adds unique
 * indexes so the database itself refuses a second set of questions (or answers) for the same slot.
 * Safe to run on every boot.
 */
@Component
public class DuplicateCleanup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DuplicateCleanup.class);
    private final JdbcTemplate jdbc;

    public DuplicateCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        // One question per date + number: keep the newest row (the prompt results pages have shown).
        int q = jdbc.update("""
                DELETE FROM daily_question d WHERE EXISTS (
                  SELECT 1 FROM daily_question o
                  WHERE o.play_date = d.play_date AND o.question_number = d.question_number AND o.id > d.id)
                """);
        // One answer per player + number: each answer was graded against both copies of a question,
        // so keep the better verdict (the one graded against the question the player actually saw).
        int a = jdbc.update("""
                DELETE FROM answer x WHERE EXISTS (
                  SELECT 1 FROM answer o
                  WHERE o.session_id = x.session_id AND o.question_number = x.question_number
                    AND (o.points > x.points OR (o.points = x.points AND o.id < x.id)))
                """);
        if (q > 0 || a > 0) {
            log.info("Removed {} duplicate questions and {} duplicate answers", q, a);
        }
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_daily_question_day_number "
                + "ON daily_question (play_date, question_number)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_answer_session_number "
                + "ON answer (session_id, question_number)");
    }
}
