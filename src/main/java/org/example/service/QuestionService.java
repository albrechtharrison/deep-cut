package org.example.service;

import org.example.model.DailyQuestion;
import org.example.repository.DailyQuestionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class QuestionService {

    private final DailyQuestionRepository questionRepo;

    public QuestionService(DailyQuestionRepository questionRepo) {
        this.questionRepo = questionRepo;
    }

    public static final int QUESTIONS_PER_DAY = 5;

    private final Object generateLock = new Object();

    public List<DailyQuestion> getTodaysQuestions() {
        LocalDate today = GameDay.today();
        List<DailyQuestion> existing = loadDay(today);
        if (!existing.isEmpty()) {
            return existing;
        }
        // Without the lock, two players opening the game at the same moment both saw "no questions yet"
        // and each saved a set of 5, giving 10.
        synchronized (generateLock) {
            existing = loadDay(today);
            if (!existing.isEmpty()) {
                return existing;
            }
            try {
                return generateAndSaveQuestions(today);
            } catch (DataIntegrityViolationException e) {
                // Another server instance saved today's set first; the unique index rejected ours.
                return loadDay(today);
            }
        }
    }

    /** Today's questions with exactly one per question number, even if old duplicate rows exist. */
    private List<DailyQuestion> loadDay(LocalDate date) {
        Map<Integer, DailyQuestion> byNumber = new TreeMap<>();
        for (DailyQuestion q : questionRepo.findByPlayDateOrderByQuestionNumber(date)) {
            DailyQuestion kept = byNumber.get(q.getQuestionNumber());
            // Keep the newest row per number: that's the prompt the results page has been showing.
            if (kept == null || q.getId() > kept.getId()) {
                byNumber.put(q.getQuestionNumber(), q);
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    private List<DailyQuestion> generateAndSaveQuestions(LocalDate date) {
        // Skip any prompt asked in the last two weeks so days don't repeat.
        Set<String> recent = new HashSet<>();
        for (DailyQuestion q : questionRepo.findByPlayDateBetween(date.minusDays(14), date.minusDays(1))) {
            recent.add(q.getPromptText());
        }
        List<String> prompts = QuestionBank.promptsFor(date, recent, QUESTIONS_PER_DAY);
        if (prompts.size() != QUESTIONS_PER_DAY) {
            throw new IllegalStateException("Question bank produced " + prompts.size() + " prompts for " + date);
        }
        List<DailyQuestion> questions = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            questions.add(new DailyQuestion(date, i + 1, prompts.get(i)));
        }
        return questionRepo.saveAllAndFlush(questions);
    }

    static String stripMarkdown(String s) {
        s = s.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first >= 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        return s;
    }
}
