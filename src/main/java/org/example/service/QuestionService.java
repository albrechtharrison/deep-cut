package org.example.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.model.DailyQuestion;
import org.example.repository.DailyQuestionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class QuestionService {

    private final DailyQuestionRepository questionRepo;
    private final AnthropicClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuestionService(DailyQuestionRepository questionRepo) {
        this.questionRepo = questionRepo;
        this.client = AnthropicOkHttpClient.fromEnv();
    }

    public List<DailyQuestion> getTodaysQuestions() {
        LocalDate today = LocalDate.now();
        List<DailyQuestion> existing = questionRepo.findByPlayDateOrderByQuestionNumber(today);
        if (!existing.isEmpty()) {
            return existing;
        }
        return generateAndSaveQuestions(today);
    }

    private List<DailyQuestion> generateAndSaveQuestions(LocalDate date) {
        String prompt = """
                You are a hockey scout running a daily game called Deep Cut.
                Generate 5 category prompts for today's game. Each prompt is a category where players name ONE real NHL player who fits.
                Make them specific enough that many valid answers exist, but reward obscure picks over famous ones.
                Mix easy and hard categories. Use varied themes: era, position, team, achievement, stat, physical trait, etc.
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
                int num = node.get("number").asInt();
                String text = node.get("prompt").asText();
                DailyQuestion q = new DailyQuestion(date, num, text);
                questions.add(questionRepo.save(q));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse questions from Claude: " + raw, e);
        }
        return questions;
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
