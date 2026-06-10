# MiniPrisTåget

TågBiljett bokningsapp inbyggd via iframe på [elitrobban.se/minipristaget](https://elitrobban.se/minipristaget/).

## Funktioner

- **GPS** – hittar automatiskt närmaste tågstation via Trafikverkets API
- **Avgångar** – visar riktiga avgångstider från Trafikverket Open Data
- **AI-förslag** – tre kategoriknappar (Storstad / Natur / Strand) låter Groq AI föreslå en destination som går att nå med tåg
- **Tur & retur** – valfritt returdatum med separata returavgångar
- **Ingen sidnavigering** – sökning sker via AJAX, användaren stannar kvar på WordPress-sidan hela tiden

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
    │   ├── WebController.java       # GET /, POST /api/search, GET /nearest-station
    │   ├── SuggestController.java   # POST /suggest (Groq AI)
    │   ├── TrafikverketService.java # Hämtar stationer + avgångar
    │   ├── TrainDeparture.java
    │   ├── TrainStation.java
    │   ├── SearchFormRequest.java
    │   └── SuggestRequest.java
    └── resources/
        ├── application.properties
        └── templates/
            ├── index.html           # Sökformulär + resultat (AJAX)
            └── results.html         # Fallback Thymeleaf-vy
```

## API-endpoints

| Metod | URL | Beskrivning |
|---|---|---|
| GET | `/` | Startsida med sökformulär |
| GET | `/nearest-station?lat=X&lon=Y` | Närmaste tågstation (JSON) |
| POST | `/api/search` | Sök avgångar (JSON) |
| POST | `/suggest` | AI-destination via Groq (JSON) |
| GET | `/health` | Hälsokontroll |

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
  height="850"
  frameborder="0"
  allow="geolocation"
  style="border-radius:20px; border:none;">
</iframe>
```

> `allow="geolocation"` krävs för att GPS-funktionen ska fungera i iframen.

## Lokal körning

```bash
cd backend
mvn spring-boot:run
```

Appen startar på `http://localhost:3000`. Sätt miljövariabler i `application.properties` eller som systemmiljövariabler.
