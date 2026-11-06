package org.example.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.DailyQuestion;
import org.example.repository.DailyQuestionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class QuestionService {

    private final DailyQuestionRepository questionRepo;
    private final AnthropicClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuestionService(DailyQuestionRepository questionRepo,
                           @Value("${anthropic.api-key}") String apiKey) {
        this.questionRepo = questionRepo;
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    public static final int QUESTIONS_PER_DAY = 5;

    private final Object generateLock = new Object();

    public List<DailyQuestion> getTodaysQuestions() {
        LocalDate today = LocalDate.now();
        List<DailyQuestion> existing = loadDay(today);
        if (!existing.isEmpty()) {
            return existing;
        }
        // Generating takes several seconds (a Claude call). Without the lock, two players opening the
        // game at the same moment both saw "no questions yet" and each saved a set of 5, giving 10.
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
        String prompt = """
                You are a hockey scout running a daily game called Deep Cut.
                Generate 5 category prompts for today's game. Each prompt is a broad category where players name ONE real NHL player who fits.
                IMPORTANT: Categories must be broad enough that HUNDREDS of valid answers exist — casual fans should be able to answer every one.
                The scoring system already rewards obscure picks, so the categories themselves should be accessible.
                Good examples: "Name an NHL goalie", "Name a player who scored 30+ goals in a season", "Name a player who has won the Stanley Cup", "Name an NHL player from Europe", "Name a player who spent their whole career with one team".
                Bad examples (too niche): "Name a player who scored their only NHL goal in overtime", "Name a defenseman who won the Conn Smythe before 1975".
                Mix themes: position, nationality, era, team, achievement, physical trait, playing style.
                Return ONLY a JSON array with no markdown formatting:
                [{"number":1,"prompt":"..."},{"number":2,"prompt":"..."},{"number":3,"prompt":"..."},{"number":4,"prompt":"..."},{"number":5,"prompt":"..."}]
                """;

        var response = client.messages().create(
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_OPUS_4_7)
                        .maxTokens(1024)
                        .addUserMessage(prompt)
                        .build()
        );

        String raw = response.content().get(0).text().get().text();
        String json = stripMarkdown(raw);

        List<DailyQuestion> questions = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(json);
            for (JsonNode node : arr) {
                if (questions.size() == QUESTIONS_PER_DAY) break;
                String text = node.get("prompt").asText().trim();
                if (text.isEmpty()) continue;
                // Number them ourselves so a bad reply can't produce two Q2s or a Q7.
                questions.add(new DailyQuestion(date, questions.size() + 1, text));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse questions from Claude: " + raw, e);
        }
        if (questions.size() != QUESTIONS_PER_DAY) {
            throw new RuntimeException("Expected " + QUESTIONS_PER_DAY + " questions from Claude: " + raw);
        }
        questions.sort(Comparator.comparingInt(DailyQuestion::getQuestionNumber));
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
