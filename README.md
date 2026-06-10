# MiniPrisTåget

Tågsökningsapp med MiniPris-deals inbyggd via iframe på [elitrobban.se/minipristaget](https://elitrobban.se/minipristaget/).

## Funktioner

- **MiniPris** – kraftigt rabatterade priser med överstruket ordinariepris och platsbegränsning (1–5 platser kvar per avgång)
- **Reseklasser** – expanderbart val av 2 klass, 2 klass Lugn eller 1 klass med SJ:s riktiga förmåner och prislogik
- **GPS** – hittar automatiskt närmaste tågstation via Trafikverkets API
- **Avgångar** – visar riktiga kommande avgångar från Trafikverket Open Data (passerade tåg filtreras bort)
- **Auto-imorgon** – om inga fler tåg kvar idag byter appen automatiskt till morgondagens avgångar
- **AI-förslag** – tre kategoriknappar (Storstad / Natur / Strand) låter Groq AI föreslå en destination
- **CO2-besparing** – visar hur mycket CO2 som sparas jämfört med bilresa
- **Tur & retur** – valfritt returdatum med separata returavgångar
- **Rate limiting** – AI-endpointen begränsad till 10 förfrågningar per IP per 10 minuter

## Teknikstack

| Lager | Teknik |
|---|---|
| Backend | Java 17, Spring Boot 3.2.5, Thymeleaf |
| Avgångsdata | Trafikverket Open Data API |
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
    │   ├── WebController.java          # GET /, POST /api/search, GET /nearest-station
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
        └── templates/
            ├── index.html              # Sökformulär + AJAX-resultat (glassmorphism)
            └── results.html            # Fallback Thymeleaf-vy
```

## API-endpoints

| Metod | URL | Beskrivning |
|---|---|---|
| GET | `/` | Startsida med sökformulär |
| GET | `/nearest-station?lat=X&lon=Y` | Närmaste tågstation (JSON) |
| POST | `/api/search` | Sök avgångar (JSON) – returnerar `autoTomorrow: true` vid auto-datumbyte |
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
| `GROQ_API_KEY` | API-nyckel från Groq |

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
| Swish-betalning | Simulerad — `swish://` deep link öppnar appen på mobil men ingen transaktion genomförs. Efter 3 sekunder visas ett lyckat svar automatiskt. |
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
