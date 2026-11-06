package org.example.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ScoutService {

    public record ScoringResult(boolean valid, int score, int points, String reason) {}

    private final AnthropicClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScoutService(@Value("${anthropic.api-key}") String apiKey) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    public String checkSpelling(String rawName) {
        if (rawName == null || rawName.isBlank()) return rawName;

        String prompt = String.format("""
                You are a hockey scout. The player typed: "%s"
                Is this a real NHL player's name, possibly misspelled?
                If it looks like a real NHL player, return their correctly spelled full name.
                If it doesn't match any real NHL player at all, return exactly: UNKNOWN
                Return ONLY the corrected name or UNKNOWN — no other text.
                """, rawName);

        var response = client.messages().create(
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_HAIKU_4_5)
                        .maxTokens(64)
                        .addUserMessage(prompt)
                        .build()
        );

        return response.content().get(0).text().get().text().trim();
    }

    public ScoringResult scoreAnswer(String promptText, String playerName) {
        String prompt = String.format("""
                You are a hockey scout scoring a Deep Cut answer.
                Category prompt: "%s"
                Player named: "%s"

                Is this a real NHL player who fits the category? If yes, rate their obscurity for this category on a scale of 1 to 10:
                - 1 = all-time superstar (Gretzky, Lemieux, Crosby, Ovechkin)
                - 5 = solid NHLer, known to fans
                - 10 = cup of coffee callup, barely anyone knows them

                Return ONLY valid JSON with no markdown:
                {"valid":true,"score":7,"reason":"one sentence explaining the score"}
                or
                {"valid":false,"score":0,"reason":"why this doesn't fit"}
                """, promptText, playerName);

        var response = client.messages().create(
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_HAIKU_4_5)
                        .maxTokens(256)
                        .addUserMessage(prompt)
                        .build()
        );

        String raw = response.content().get(0).text().get().text();
        String json = QuestionService.stripMarkdown(raw);

        try {
            JsonNode node = mapper.readTree(json);
            boolean valid = node.get("valid").asBoolean();
            int score = node.get("score").asInt();
            String reason = node.get("reason").asText();
            int points = valid ? score * 10 : 0;
            return new ScoringResult(valid, score, points, reason);
        } catch (Exception e) {
            return new ScoringResult(false, 0, 0, "Scout couldn't parse the answer.");
        }
    }
}
