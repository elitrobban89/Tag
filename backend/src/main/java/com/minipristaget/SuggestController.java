package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
public class SuggestController {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    private static final Map<String, String> CATEGORY_PROMPTS = Map.of(
        "Storstad", "en svensk storstad med kultur, nöjen och shopping",
        "Natur",    "en naturskön destination med natur, nationalparker eller friluftsliv i Sverige",
        "Strand",   "en strand- eller skärgårdsdestination längs Sveriges kust med bad och sol"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper   = new ObjectMapper();

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody SuggestRequest req) {
        String category = req.getCategory();

        if (category == null || !CATEGORY_PROMPTS.containsKey(category)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ogiltig kategori"));
        }

        String from     = (req.getFrom() != null && !req.getFrom().isBlank()) ? req.getFrom() : null;
        String fromText = from != null ? "från " + from : "i Sverige";

        String prompt =
            "Du är en tågreseexpert i Sverige. En resenär vill resa " + fromText +
            " och söker " + CATEGORY_PROMPTS.get(category) + ".\n\n" +
            "Ge ETT perfekt destinationsförslag som går bra att nå med tåg. " +
            "Svara ENBART med ortnamnet, ingenting annat. Exempel: Göteborg";

        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                "model",       GROQ_MODEL,
                "messages",    List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.8,
                "max_tokens",  20
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GROQ_URL))
                .header("Authorization", "Bearer " + System.getenv("GROQ_API_KEY"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode json        = mapper.readTree(response.body());
            String   destination = json.path("choices").get(0)
                                       .path("message").path("content")
                                       .asText().trim().replaceAll("[\".]", "");

            return ResponseEntity.ok(Map.of("destination", destination));

        } catch (Exception e) {
            System.err.println("Groq error: " + e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Kunde inte hämta förslag. Försök igen."));
        }
    }
}
