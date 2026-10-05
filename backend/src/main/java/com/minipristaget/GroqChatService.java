package com.minipristaget;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

/** @author Robert Andersson Kopler */
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
        - **SJ 3000 (X55)** — snabbtåg i trafik sedan 2012, 200 km/h, byggt av Bombardier.
          Fyra vagnar, bistro, plant insteg och rullstolslift i ändvagnen, 2+2-stolar i BÅDA
          klasserna, dubbla eluttag vid varje plats, nedfällbara bord med mugghållare.
          Ca 62 platser i 1 klass och 183 i 2 klass. Kör bl.a. Göteborg–Stockholm,
          Göteborg–Malmö, Stockholm–Sundsvall/Östersund och Stockholm–Oslo.
        - **VR Snabbtåg (X74, fd MTR Express)** — byggt av schweiziska Stadler, 6 tågsätt,
          105,5 m långa, max 200 km/h, 4 500 kW. Specialbyggda för svenskt vinterklimat,
          ingen korglutning. Mat och dryck ombord, WiFi. Kör Stockholm–Göteborg.
        - **SJ InterCity / SJ Regional** — WiFi på nyare vagnar, 2:a klass standard
        - **SJ Nattåg** — Stockholm–Luleå/Narvik m.fl., sovvagn och liggvagn
        - **Öresundståg (X31K, i Danmark ET)** — Bombardier Contessa, 111 tågsätt, max 180 km/h.
          Tre vagnar och 229 sittplatser per tågsätt, upp till tre kopplade (687 platser).
          1:a och 2:a klass; 2:a klass har tyst avdelning och avdelning för husdjur.
          Eluttag (220 V) vid varje sätesgrupp, trådlöst internet. Mittvagnen har
          rullstolsplatser och handikappanpassad toalett. Öppen placering.
        - **Regionaltåg**: Mälartåg (Mälardalen), Pågatåg X61 (Skåne), Västtåg (Västra Götaland),
          Krösatåg (Småland), Östgötapendeln, Norrtåg X62, X-Tåget, Tåg i Bergslagen — öppen placering
        - **SL Pendeltåg (X60)** — Stockholmsregionen. **Arlanda Express (X3)** — Stockholm C–Arlanda
        - **Vy Snabbtåg** — Göteborg–Oslo. **Snälltåget** — fjärrtåg och nattåg, liggvagn
        - **Ersättningsbuss** — när en avgång har trafiktypen Buss går en buss i stället för tåget

        ## Faciliteter ombord (generellt SJ/MTRX)
        - **WiFi**: ingår kostnadsfritt på X2000, SJ 3000, MTRX och de flesta Intercity-tåg
        - **5G**: god täckning längs stambanorna Stockholm–Göteborg och Stockholm–Malmö
        - **Satellituppkoppling**: SJ utrustar SJ 3000-flottan (20 tåg) med satellitinternet
          för att täcka de vita fläckarna där mobilnätet inte räcker, särskilt Stockholm–Oslo.
          Installationen startar 2026 och hela flottan ska vara klar under 2027.
        - **Bistro/café**: X2000, SJ 3000 och MTRX på längre sträckor
        - **Tyst-kupé**: finns på X2000 och SJ 3000 (2:a klass Lugn) samt i 1:a klass
        - **Cykel**: kan medtas på de flesta tåg mot avgift (boka plats)
        - **Toalett**: finns i varje vagn på fjärrtågen; minst en är rullstolsanpassad

        ## Vagnsskiss och platser
        Följer en vagnsskiss med i kontexten är den appens karta över just det tåget. Använd DEN
        när någon frågar var toaletten eller bistron finns, eller vilken plats som ligger närmast
        något — svara med vagn och radnummer ur skissen. Följer en vald plats med är avstånden
        redan uträknade: återge dem, räkna inte om dem. Saknas skiss: säg att du kan visa den när
        en avgång och plats är vald, och svara allmänt.
        Följer en lista med lediga platser med: rekommendera en av DEM (vagn + platsnummer +
        varför den är bra), aldrig en plats som inte står i listan. Nämn om den är fönster-,
        gång- eller bordsplats. Fönsterplatser är ytterkolumnerna, gångplatserna ligger mot
        mittgången, och bordsplatser är rader där sätena står vända mot varandra.
        Hitta ALDRIG på radnummer, vagnsnummer eller avstånd som inte står i kontexten.

        ## Vilka tåg går var
        - **Snabbtåg X2000 & SJ 3000**: huvudlinjerna mellan storstäderna — Stockholm–Göteborg,
          Stockholm–Malmö/Köpenhamn och Stockholm–Sundsvall.
        - **VR Snabbtåg (X74)**: främst Stockholm–Göteborg.
        - **Västtågen**: Västra Götaland — Kungsbacka–Göteborg, Borås, Uddevalla, Skövde.
        - **Öresundståg**: södra Sverige — Skåne, Halland, upp till Göteborg och vidare till
          Köpenhamn/Helsingör.
        - **Pendeltåg**: SL i Stockholm, Krösatågen i Småland.
        - **Nattåg (SJ/Vy)**: Stockholm och södra Sverige upp till övre Norrland — Umeå, Luleå,
          Kiruna och Narvik.

        ## Restider
        Restid, byten och ankomst står i avgångsdatan och kommer från Trafikverkets tidtabell —
        använd DEM. Gissa aldrig en restid ur minnet (Stockholm–Göteborg tar i dagens tidtabell
        ca 3h25–3h45, inte 2h40).

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

    /** Modellen som faktiskt körs — uppstartsskärmen visar den i stället för en avskriven sträng. */
    public String modelName() {
        return model;
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
        return json.at("/choices/0/message/content").asString("Inget svar.");
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
