package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GroqChatService {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    @Value("${groq.api.key:}")
    private String apiKey;

    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String model;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chat(List<Map<String, String>> messages, String departureContext) throws Exception {
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("""
                Du är en hjälpsam assistent för tågresenärer i Sverige på appen MiniPrisTåget.
                Du hjälper användare att hitta billigaste biljetter, snabbaste avgångar och avgångar med platser kvar.

                Du svarar ENDAST på frågor om tågresor, avgångar, biljettpriser, restider och tågoperatörer i Sverige.
                Om frågan inte handlar om tågresor svarar du:
                "Det kan jag inte hjälpa med här — jag är specialiserad på tågresor."

                Svara alltid på svenska. Var kortfattad och konkret. Du får använda **fetstil** och listor med - för att strukturera svaret.
                """);

        if (departureContext != null && !departureContext.isBlank()) {
            systemPrompt.append("\n\nAktuell sökning:\n").append(departureContext);
        }

        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", systemPrompt.toString()));
        msgs.addAll(messages);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 500,
                "temperature", 0.4,
                "messages", msgs
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200)
            throw new RuntimeException("Groq svarade " + response.statusCode());

        JsonNode json = mapper.readTree(response.body());
        return json.at("/choices/0/message/content").asText("Inget svar.");
    }
}
