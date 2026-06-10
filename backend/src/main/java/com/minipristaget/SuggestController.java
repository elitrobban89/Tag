package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@RestController
@CrossOrigin
public class SuggestController {

    private static final String GROQ_URL   = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    private static final Map<String, String> CATEGORY_PROMPTS = Map.of(
        "Storstad", "en svensk storstad med kultur, nöjen och shopping",
        "Natur",    "en naturskön destination med natur, nationalparker eller friluftsliv i Sverige",
        "Strand",   "en strand- eller skärgårdsdestination längs Sveriges kust med bad och sol"
    );

    // Rate limit: max 10 requests per IP per 10 minutes
    private static final int RATE_LIMIT        = 10;
    private static final long WINDOW_MS        = 10 * 60 * 1000L;
    private record RateEntry(AtomicInteger count, long windowStart) {}
    private final ConcurrentHashMap<String, RateEntry> rateLimiter = new ConcurrentHashMap<>();

    @Autowired
    private TrafikverketService trafikverketService;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper   = new ObjectMapper();

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @PostMapping("/suggest")
    public ResponseEntity<?> suggest(@RequestBody SuggestRequest req, HttpServletRequest httpReq) {
        // Rate limiting per IP
        String ip = getClientIp(httpReq);
        long now = System.currentTimeMillis();
        RateEntry entry = rateLimiter.compute(ip, (k, v) -> {
            if (v == null || now - v.windowStart() > WINDOW_MS)
                return new RateEntry(new AtomicInteger(1), now);
            v.count().incrementAndGet();
            return v;
        });
        if (entry.count().get() > RATE_LIMIT) {
            return ResponseEntity.status(429).body(Map.of("error", "För många förfrågningar. Försök igen om en stund."));
        }

        String category = req.getCategory();

        if (category == null || !CATEGORY_PROMPTS.containsKey(category)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ogiltig kategori"));
        }

        String from = (req.getFrom() != null && !req.getFrom().isBlank()) ? req.getFrom().trim() : null;

        // Hämta faktiska direktdestinationer från Trafikverket
        List<String> reachable = fetchReachableDestinations(from);

        String prompt;
        if (!reachable.isEmpty()) {
            prompt =
                "Du är en tågreseexpert. En resenär åker från " + from + " och söker " +
                CATEGORY_PROMPTS.get(category) + ".\n\n" +
                "Dessa direktdestinationer går att nå med tåg från " + from + " idag:\n" +
                String.join(", ", reachable) + "\n\n" +
                "Välj den destination från listan ovan som BÄST matchar kategorin. " +
                "Svara ENBART med ortnamnet exakt som det står i listan, ingenting annat.";
        } else if (from != null) {
            prompt =
                "Du är en tågreseexpert i Sverige. En resenär åker från " + from + " med tåg " +
                "och söker " + CATEGORY_PROMPTS.get(category) + ".\n\n" +
                "Föreslå ETT resmål med DIREKTTÅG från " + from + " (inga byten). " +
                "Svara ENBART med ortnamnet, ingenting annat. Exempel: Göteborg";
        } else {
            prompt =
                "Du är en tågreseexpert i Sverige. En resenär vill resa med tåg och söker " +
                CATEGORY_PROMPTS.get(category) + ".\n\n" +
                "Ge ETT resmål som går att nå med tåg i Sverige. " +
                "Svara ENBART med ortnamnet, ingenting annat. Exempel: Göteborg";
        }

        try {
            String requestBody = mapper.writeValueAsString(Map.of(
                "model",       GROQ_MODEL,
                "messages",    List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.7,
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

    private List<String> fetchReachableDestinations(String fromName) {
        if (fromName == null) return List.of();
        try {
            Optional<TrainStation> station = trafikverketService.findStationByName(fromName);
            if (station.isEmpty()) return List.of();
            List<TrainDeparture> deps = trafikverketService.getDepartures(
                station.get().getSignature(), null, LocalDate.now(ZoneId.of("Europe/Stockholm")));
            return deps.stream()
                .map(TrainDeparture::getDestination)
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.replaceAll("\\s*C$", "").trim()) // ta bort " C" suffix
                .distinct()
                .sorted()
                .limit(25)
                .collect(Collectors.toList());
        } catch (Exception e) {
            System.err.println("Could not fetch reachable destinations: " + e.getMessage());
            return List.of();
        }
    }

    private String getClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank())
            return forwarded.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
