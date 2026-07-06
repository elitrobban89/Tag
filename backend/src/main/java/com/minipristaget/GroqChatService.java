package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class GroqChatService {

    private static final int CHAT_MAX_HISTORY = 8;

    private static final String SYSTEM_PROMPT =
        """
        Du är en hjälpsam tågassistent på appen MiniPrisTåget. Du hjälper användare med:
        - Hitta billigaste biljett och avgångar med MiniPris-platser kvar
        - Vilka avgångar som är snabbast eller har platser kvar
        - Bokningsråd baserat på pris, restid och tillgänglighet
        - Information om tågtyper, faciliteter ombord (WiFi, 5G) och ruttider

        Du svarar INTE på frågor som inte rör tågresor eller bokningar. Svara då:
        "Det kan jag inte hjälpa med här — jag är specialiserad på tågresor."

        ## Tågtyper i Sverige
        - **SJ X2000** — snabbtåg, max 200 km/h, WiFi, bra 5G-täckning, tyst- och 1:a klass, bistro
        - **MTRX / X74** — höghastighetståg, WiFi, 1:a och 2:a klass, modern inredning
        - **SJ Intercity / Regional** — WiFi på nyare vagnar, 2:a klass standard
        - **Öresundståg X31** — regionaltåg Malmö–Köpenhamn och södra Sverige, öppen placering, WiFi
        - **Snälltåget** — nattåg och semester, liggvagn tillgänglig, WiFi
        - **MTR Express** — Stockholm–Göteborg, modern, WiFi, konkurrenskraftiga priser

        ## Faciliteter ombord (generellt SJ/MTRX)
        - **WiFi**: ingår kostnadsfritt på X2000, MTRX och de flesta Intercity-tåg
        - **5G**: god täckning längs stambanorna Stockholm–Göteborg och Stockholm–Malmö
        - **Bistro/café**: X2000 och MTRX på längre sträckor
        - **Tyst-kupé**: finns på X2000 (2:a klass Lugn och 1:a klass)
        - **Cykel**: kan medtas på de flesta tåg mot avgift (boka plats)

        ## Topp 5 rutter (ungefärlig restid)
        1. Stockholm → Göteborg: ca 3h (X2000), 2h40 (MTRX snabbaste)
        2. Stockholm → Malmö: ca 4h30 (X2000 direkttåg)
        3. Göteborg → Malmö: ca 2h40 (Öresundståg/SJ Regional)
        4. Stockholm → Sundsvall: ca 3h30 (SJ Regional)
        5. Stockholm → Östersund: ca 4h30 (SJ Intercity)

        Svara alltid på svenska. Var kortfattad och konkret. Använd **fetstil** och listor med - för att strukturera svaret.
        """;

    @Value("${groq.api.key:}")
    private String apiKey;

    // Överstyrbar i tester — pekas mot en lokal stubbserver
    @Value("${groq.api.url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqUrl;

    @Value("${groq.model:openai/gpt-oss-120b}")
    private String model;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    List<Map<String, String>> buildMsgList(List<Map<String, String>> history, String departureContext) {
        String sysContent = departureContext != null && !departureContext.isBlank()
            ? SYSTEM_PROMPT + "\n\nAktuell sökning:\n" + departureContext
            : SYSTEM_PROMPT;
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", sysContent));
        List<Map<String, String>> trimmed = history.size() > CHAT_MAX_HISTORY
            ? history.subList(history.size() - CHAT_MAX_HISTORY, history.size()) : history;
        msgs.addAll(trimmed);
        return msgs;
    }

    public String chat(List<Map<String, String>> messages, String departureContext) throws Exception {
        if (!isConfigured())
            return "AI-assistenten är inte konfigurerad just nu. Försök igen senare.";

        List<Map<String, String>> msgs = buildMsgList(messages, departureContext);

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "max_tokens", 500,
                "temperature", 0.4,
                "reasoning_effort", "low",
                "messages", msgs
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(groqUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 401)
            throw new RuntimeException("AI-tjänsten är inte korrekt konfigurerad. Kontakta oss om felet kvarstår.");
        if (response.statusCode() == 429)
            throw new RuntimeException("För många frågor till AI:n just nu — vänta en stund och försök igen.");
        if (response.statusCode() != 200)
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ". Försök igen om en stund.");

        JsonNode json = mapper.readTree(response.body());
        return json.at("/choices/0/message/content").asText("Inget svar.");
    }

    public InputStream chatStream(List<Map<String, String>> messages, String departureContext) throws Exception {
        if (!isConfigured())
            throw new RuntimeException("AI-assistenten är inte konfigurerad.");

        List<Map<String, String>> msgs = buildMsgList(messages, departureContext);
        Map<String, Object> requestBody = Map.of(
                "model", model, "max_tokens", 500, "temperature", 0.4, "stream", true,
                "reasoning_effort", "low", "messages", msgs);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(groqUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401)
            throw new RuntimeException("AI-tjänsten är inte korrekt konfigurerad.");
        if (response.statusCode() == 429)
            throw new RuntimeException("För många frågor till AI:n just nu — vänta en stund och försök igen.");
        if (response.statusCode() != 200)
            throw new RuntimeException("AI-tjänsten svarade med fel " + response.statusCode() + ".");
        return response.body();
    }
}
