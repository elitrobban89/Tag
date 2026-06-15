# MiniPrisTåget

Tågsökningsapp med MiniPris-deals inbyggd via iframe på [elitrobban.se/minipristaget](https://elitrobban.se/minipristaget/).

## Funktioner

### Sökning & avgångar
- **Avgångar** – riktiga kommande avgångar från Trafikverket Open Data, passerade tåg filtreras bort
- **Stationsautocomplete** – skriv "Göt" → Göteborg, "Sto" → Stockholm, "Mal" → Malmö. Debounced fetch mot `/api/stations`, tangentbordsnavigation (↑/↓/Enter/Escape)
- **GPS** – hittar automatiskt närmaste tågstation (hoppar över GPS om från-fältet redan är ifyllt)
- **Auto-imorgon** – om inga fler tåg kvar idag byter appen automatiskt till morgondagens avgångar med gul notis
- **Svensk tidszon** – servern kör UTC men all tidslogik använder `Europe/Stockholm`

### Priser & klasser
- **MiniPris** – kraftigt rabatterade flash-priser med överstruket ordinariepris och rött MiniPris-badge
- **Reseklasser** – expanderbart val av 2 klass, 2 klass Lugn eller 1 klass med SJ:s förmåner och prislogik
- **Klasslåsning** – vald klass låses när man går vidare till platskarta, kan inte ändras i efterhand

### Platskarta & bokning
- **Interaktiv platskarta** – realistisk vagnskarta per tågtyp (X2000 3 vagnar, X74 2 vagnar)
- **Klassbaserad vagn** – automatiskt rätt vagn baserat på vald klass (1 klass → Vagn 3 på X2000)
- **Regionaltåg** – visar "Öppen placering" istället för platskarta för lokaltåg
- **Platsbeläggnig** – deterministisk men realistisk: färre platser kvar = mer fullsatt karta
- **Swish-betalning** – demo-flöde med `swish://` deep link, spinner och bokningsbekräftelse
- **Platser räknas ned** – efter betalning minskar antalet platser kvar i realtid

### Återresa & kombinerad bokning
- **Spara utresa** – väljer man "Lägg till återresa" istället för att betala direkt sparas utresans vagn och platsnummer automatiskt
- **Kombinerad checkout** – när man sedan valt sittplats på återresan visas en gemensam Swish-kassa med båda resorna och ett totalpris
- **En Swish-betalning** – utresa + återresa betalas i en enda transaktion; båda platserna bokas och räknas ned i samma steg
- **Återresemodul** – omvänd rutt med datumväljare (default nästa dag), eget sök och platskarta

### Mina bokningar
- **Bokningshistorik** – knapp i headern med badge-räknare efter varje genomförd betalning
- **Bokningskort** – visar bokningsref, rutt, datum, avgångstid, plats, klass och pris
- **Demo-reset** – listan återställs automatiskt efter 5 minuter (demo-läge)

### AI-chatt
- **Kontextkänslig chatbot** – läser automatiskt av din sökning och vet exakt vilka avgångar som visas: operatör, avgångstid, pris, restid och platser kvar. Söker du Göteborg C → Stockholm C ser AI:n alla avgångar och kan direkt svara "avgång 07:45 MTRX är snabbast men har inga platser — nästa med MiniPris är 09:15"
- **Tågassistent** – hjälper med billigaste biljett, snabbaste avgång och var det finns platser kvar just nu
- **Glassmorphism-design** – blå/mörkblå panel med `backdrop-filter: blur(24px)` och färgglad svg-tågikon (teal + orange vagnar + blå lokomotiv med räls)
- **Snabbknappar** – 💰 Billigast, ⚡ Snabbast, 🎫 Platser kvar, 🤖 Ge råd
- **Kontext-bar** – visar aktuell rutt, datum och antal avgångar som AI:n känner till
- **Markdown** – svarar med **fetstil** och `- listor` som renderas till HTML
- **Rensa** – knapp för att starta ett nytt samtal utan att ladda om sidan
- **Tåginformation** – AI:n känner till tågtyper (X2000, MTRX, Öresundståg m.fl.), WiFi/5G ombord och restider för topp 5-rutter
- **Tågbilder** – när AI:n nämner en tågtyp (X2000, MTRX X74, Öresundståg, Snälltåget, MTR Express, Västtåg X61) visas motsvarande tågfoto automatiskt under svaret
- **Streaming-svar** – svaret strömmar direkt token för token från Groq till chatbubblan utan att vänta på hela svaret, via `/api/chat/stream` (SSE); automatisk fallback till `/api/chat` om webbläsaren saknar ReadableStream-stöd
- **Dynamiska follow-up chips** – efter varje svar visas 2–3 kontextuella snabbknappar baserade på vad AI:n svarade (tågtyp, pris, WiFi, restid)
- **Avgångshighlighting** – om AI:n nämner en avgångstid (t.ex. "07:45") scrollas och highlightas det avgångskortet automatiskt i listan
- **Rate limiting** – chatboten begränsad till 10 meddelanden per IP per minut (glidande fönster)

### Övrigt
- **Skeleton loader** – animerade shimmer-platshållare visas omedelbart när sökning startar, ersätts av riktiga avgångskort när svaret kommer
- **Favoritresor** – hjärtknapp i sökresultatet sparar aktuell rutt (Från → Till) i webbläsaren; sparade rutter syns som snabbknappar under sökformuläret; max 5 favoriter, enkelt att ta bort
- **Dela avgång** – 🔗-knapp på varje avgångskort genererar en delbar länk med från/till/datum som URL-parametrar; `navigator.share` på mobil, kopierar till urklipp på desktop; sidan auto-söker direkt om URL-parametrar finns vid sidladdning
- **AI-förslag** – tre kategoriknappar (Storstad / Natur / Strand) via Groq AI
- **CO2-besparing** – visar kg CO2 sparat jämfört med bilresa
- **Mobilanpassad** – responsiv layout med media queries för smala skärmar
- **UptimeRobot** – pingar `/health` var 5:e minut, håller Render-instansen varm
- **PWA-stöd** – `manifest.json` gör appen installerbar på Android/iOS via "Lägg till på startskärm"

## Teknikstack

| Lager | Teknik |
|---|---|
| Backend | Java 21, Spring Boot 3.2.5, Thymeleaf |
| Avgångsdata | Trafikverket Open Data API |
| AI-chatbot | Groq API (llama-3.3-70b-versatile), avgångskontextuell |
| AI-förslag | Groq API (llama-3.3-70b-versatile) |
| Deploy | Render (Docker, free tier) |
| Frontend | Inbyggd via `<iframe>` i WordPress/Gutenberg |

## Projektstruktur

```
backend/
├── Dockerfile
├── pom.xml
└── src/main/
    ├── java/com/minipristaget/
    │   ├── Application.java
    │   ├── WebController.java          # GET /, POST /api/search, /api/chat, /api/chat/stream, GET /nearest-station
    │   ├── GroqChatService.java        # Avgångskontextuell AI-chatt via Groq
    │   ├── SuggestController.java      # POST /suggest (Groq AI + rate limiting)
    │   ├── TrafikverketService.java    # Hämtar stationer + avgångar (Swedish timezone)
    │   ├── TrainModelService.java      # Tågmodell-DB, prisberäkning, platsbegränsning
    │   ├── TrainDeparture.java         # Avgångsobjekt med alla berikade fält
    │   ├── TrainStation.java
    │   ├── SearchFormRequest.java
    │   └── SuggestRequest.java
    └── resources/
        ├── application.properties
        ├── static/images/              # Tågfoton per operatör
        │   ├── logo.png
        │   ├── train-sj-x2000.jpg
        │   ├── train-vy.jpg
        │   ├── train-sj-regional.png
        │   ├── train-sj-fast.png
        │   └── train-oresundstag.jpg
        ├── static/
        │   └── train-chat.js           # AI-chatbot (glassmorphism, avgångskontextuell)
        └── templates/
            ├── index.html              # Sökformulär + AJAX-resultat (glassmorphism)
            └── results.html            # Fallback Thymeleaf-vy
```

## API-endpoints

| Metod | URL | Beskrivning |
|---|---|---|
| GET | `/` | Startsida med sökformulär |
| GET | `/api/stations?q=xxx` | Stationsautocomplete – returnerar upp till 8 matchande stationer (JSON) |
| GET | `/nearest-station?lat=X&lon=Y` | Närmaste tågstation (JSON) |
| POST | `/api/search` | Sök avgångar (JSON) – returnerar `autoTomorrow: true` vid auto-datumbyte |
| POST | `/api/chat` | AI-chatbot med avgångskontext (JSON) – rate limited (10/min per IP) |
| POST | `/api/chat/stream` | AI-chatbot streaming (SSE) – returnerar svar token för token – rate limited (10/min per IP) |
| POST | `/suggest` | AI-destination via Groq (JSON) – rate limited |
| GET | `/health` | Hälsokontroll |

## Prislogik (TrainModelService)

| Klass | Multiplikator | Exempel Gbg–Sthlm |
|---|---|---|
| 2 klass (MiniPris) | 1× (bas) | ~179 kr |
| 2 klass Lugn | ×1.18 | ~209 kr |
| 1 klass | ×1.45 | ~259 kr |
| Ordinariepris (överstruket) | ×2.8 | ~499 kr |

## Tågmodeller per operatör

| Operatör | Tågmodell | Bild |
|---|---|---|
| SJ | SJ X2000 | train-sj-x2000.jpg |
| MTRX / VR | X74 | train-vy.jpg |
| VASTTRAF | X61 Västtåg | train-sj-regional.png |
| Ö-TÅG / SKANE | Öresundståg X31 | train-oresundstag.jpg |
| SNALLTAGET | Snälltåget | train-sj-fast.png |
| MTR | MTR Express | train-sj-fast.png |

## Miljövariabler (Render)

| Variabel | Beskrivning |
|---|---|
| `TRAFIKVERKET_API_KEY` | API-nyckel från Trafikverket Open Data |
| `GROQ_API_KEY` | API-nyckel från Groq (används av chatbot + AI-förslag) |

## WordPress-inbäddning

Lägg till ett **Anpassad HTML**-block i Gutenberg med:

```html
<iframe
  src="https://tag-k5we.onrender.com"
  width="100%"
  height="1100"
  frameborder="0"
  allow="geolocation"
  style="border-radius:20px; border:none;">
</iframe>
```

> `allow="geolocation"` krävs för att GPS-funktionen ska fungera i iframen.

## Demo-läge

Appen är ett demonstrationsprojekt — ingen riktig bokning eller betalning sker.

| Funktion | Beteende |
|---|---|
| Swish-betalning | Simulerad — `swish://` deep link öppnar appen på mobil men ingen transaktion genomförs. Efter 3 sekunder visas ett lyckat svar automatiskt. Vid kombinerad tur/retur betalas båda resorna med ett enda simulerat Swish-anrop och totalpriset summeras. |
| Bokningsreferens | Slumpmässigt genererad i JavaScript (t.ex. MP-482931) |
| Platser kvar | Deterministiskt beräknade per tåg-ID, inte realtidsdata |
| Priser | Simulerade MiniPris-priser baserade på avstånd, inte SJ:s riktiga priser |
| Mina bokningar | Sparas lokalt i webbläsarminnet, återställs efter 5 minuter |

## Driftstatus

Appen monitoreras via [UptimeRobot](https://uptimerobot.com) som pingar `/health` var 5:e minut.
Detta håller även Render free tier-instansen vaken så att appen svarar direkt utan cold start-fördröjning.

## Lokal körning

```bash
cd backend
mvn spring-boot:run
```

Appen startar på `http://localhost:3000`. Sätt miljövariabler i `application.properties` eller som systemmiljövariabler.
