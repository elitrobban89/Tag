# MiniPrisTåget

[![Build & Test](https://github.com/elitrobban89/Tag/actions/workflows/maven.yml/badge.svg)](https://github.com/elitrobban89/Tag/actions/workflows/maven.yml)

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

### Vagnsskisser & platsval
- **Planskiss ovanifrån** – platsvalet är en ritning av vagnen: sätena i 2+2 kring mittgången, radnummer, dörrar i ändarna och klickbara platser
- **Toalett & bistro inritade** – faciliteterna sitter på sin faktiska radposition i skissen, inte som en fotnot
- **Fönster, gång och bord** – ytterkolumnerna markeras som fönsterplatser, kolumnerna mot gången som gångplatser, och bordsgrupper (fyrsits mot varandra) ritas ut; tooltipen säger vilken typ platsen är
- **Tågsilhuett** – hela tågsättet ovanför platskartan, ritat ur samma skiss: vagnslängderna är proportionella mot antalet rader, vald vagn lyser och en prick visar var i tåget din plats sitter
- **Avstånd som siffror** – när du valt plats står det "1 rad till närmaste toalett · 4 rader till bistron"; avståndet räknas i en global radkoordinat som korsar vagnsgränser
- **En källa** – `TrainLayoutService` är enda stället vagnsuppsättningen bor. Platskartan, silhuetten, chattens skiss och AI:ns systemprompt läser samma data, så de kan inte säga emot varandra. Hämtas som JSON via `GET /api/train-layout?layout=<id>`, och `&format=text` ger exakt den skisstext AI:n får se (`vagnskiss://<id>`)
- **Fem tågtyper** – X2000 (3 vagnar), SJ 3000 / X55 (4 vagnar med bistrovagn), X74, Snälltåget och MTR Express
- **Klassbaserad vagn** – automatiskt rätt vagn baserat på vald klass (1 klass → Vagn 3 på X2000)
- **Regionaltåg** – visar "Öppen placering" istället för platskarta för lokaltåg
- **Platsbeläggning** – deterministisk men realistisk: färre platser kvar = mer fullsatt karta
- **Swish-betalning** – demo-flöde med `swish://` deep link, spinner och bokningsbekräftelse
- **Platser räknas ned** – efter betalning minskar antalet platser kvar i realtid

### Vilken tågtyp går egentligen?
Trafikverkets öppna API anger **aldrig** fordonstyp — bara tågnummer, tider, destination och (ibland) operatör. `resolveModel` avgör därför i fem lager:

1. **Bekräftat tågnummer** – t.ex. 442 och 452 Göteborg C → Stockholm C körs med SJ 3000, övriga direkttåg med X2000
2. **Bekräftad avgång** – från/till/avgångstid, för tåg där numret inte är känt
3. **Produktnamn** – `ProductInformation` skiljer "SJ Snabbtåg" från "SJ Regional"
4. **Destination** – SJ mot Sundsvall/Östersund/Oslo/Umeå = SJ 3000
5. **Tågnummerserie** – när operatörsfältet saknas helt: 1xxx och 20xxx Öresundståg, 3xxx och 13xxx Västtrafik, 6xxxx SJ Regional, 4xx SJ snabbtåg

Lager 1–2 och 5 är kurerad kunskap avläst ur verklig data, **inte officiella regler** — de behöver ses över vid tidtabellsskifte.

### Trafikverkets API-omläggning 2026-09-02

Datamängden `TrainAnnouncement` flyttades till namespace **`rail.trafficinfo`**, och frågan är omlagd i förväg (2026-08-19) så att skiftet inte märks i drift. Tjänsten frågar nu med `namespace="rail.trafficinfo"` och **schemaversion 2.0** — den senare kräver namespace och gick alltså inte att använda tidigare.

Frågor upp till schemaversion 1.9 dirigeras om automatiskt av Trafikverket, så den gamla frågan hade fortsatt fungera. Ändringen gjordes ändå: omdirigeringen är en övergångslösning, och natten 1–2 september väntas avbrott under själva skiftet.

**Den nya datamängden sorterar inte som den gamla, och det står inte i Trafikverkets notis.** Uppmätt mot skarpt API för Göteborg C med identiskt filter och limit:

| Fråga | Första träffarna |
|---|---|
| utan namespace | 18:04 · 18:05 · 18:10 · 18:11 · 18:15 |
| med namespace | **21:00 · 22:20 · 23:20** · 18:04 · 18:05 |

Eftersom `limit` kapar listan på API-sidan och avgångarna aldrig sorteras om i Java hade ett ensamt namespace-tillägg tyst visat kvällens sista tåg i stället för de närmaste — inget hade kastat, inget test hade fallit (de kör mot fixturer). Därför bär frågan **`orderby="AdvertisedTimeAtLocation"`**, och de två attributen hör ihop: tas orderby bort återkommer felet utan att något larmar.

Bytet till 2.0 gjordes efter att svaren jämförts fält för fält mot 1.8 på skarpt API, både för tåg i tid och för 25 försenade: `AdvertisedTrainIdent`, `AdvertisedTimeAtLocation`, `EstimatedTimeAtLocation`, `ToLocation`, `TrainOwner`, `Canceled` och `ProductInformation` var identiska. Nya fält i 2.0 påverkar inte tjänsten — `INCLUDE`-listan styr vad som hämtas.

`TrainStation`-frågan (schemaversion 1) berörs inte av omläggningen; det är en annan datamängd.

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
- **Avgångsfokuserad kontext** – klickar du på ett avgångskort för att expandera det fokuseras chatboten automatiskt på just den avgången. Kontextbaren uppdateras till t.ex. "🚂 15:05 – 19:15 · 4h 10min · Göteborg → Stockholm", snabbchipsen byts till avgångsspecifika (🪑 Vilken klass?, 🛜 WiFi & 5G, 🧳 Bagage, 🗺 Till centrum) och en intro-bubbla visas med tider, pris och platser kvar. Väljer du ett annat kort uppdateras kontexten direkt.
- **Tågassistent** – hjälper med billigaste biljett, snabbaste avgång och var det finns platser kvar just nu
- **Glassmorphism-design** – blå/mörkblå panel med `backdrop-filter: blur(24px)` och färgglad svg-tågikon (teal + orange vagnar + blå lokomotiv med räls)
- **Snabbknappar** – 💰 Billigast, ⚡ Snabbast, 🎫 Platser kvar, 🤖 Ge råd (byts till avgångsspecifika vid expanderat kort)
- **Kontext-bar** – visar aktuell rutt + antal avgångar vid sökning, eller specifik avgångstid vid fokuserat kort
- **Markdown** – svarar med **fetstil** och `- listor` som renderas till HTML
- **Rensa** – knapp för att starta ett nytt samtal och återställa avgångsfokus
- **Expandera-läge** – chevron-knapp i chattens header växlar mellan normalt bottenkort och stort läge: helskärmsark på mobil (FAB:en döljs), 560 px bred panel med full höjd på desktop. Bra för långa svar, vagnsskisser och tågbilder. Läget sparas i `localStorage` (`tc-chat-max`) och överlever omladdning. Att FAB:en döljs bara när panelen både är expanderad och öppen är en ren CSS-regel — se nästa punkt
- **Klassbaserat chattillstånd** – chattens tillstånd bor i klasser, aldrig i inline `style.display`. Öppet/stängt är `body.tc-chat-open`, expanderat är `body.tc-chat-max`, och snabbknappsraden döljs med `.tc-quick-off` när samtalet börjat. Poängen är att CSS då kan uttrycka villkor som JS annars måste hålla synkade: `body.tc-chat-open.tc-chat-max .tc-fab-wrap { display: none }` döljer flytknappen exakt när panelen är både expanderad och öppen, och `body.tc-chat-max .tc-quick:not(.tc-quick-off)` tar tillbaka snabbknapparna i liggande expanderat läge utan att krocka med JS-tillståndet. Inline `display` vinner över hela cascaden, så varje CSS-regel på samma element hade blivit tyst verkningslös — det var den buggen som gjorde att snabbknapparna dök upp igen i liggande läge efter en rensning. Enda vägen in i öppet-läget är `tcIsOpen()` / `tcSetOpen()`
- **Mobilanpassad panel** – chatten är ett bottenkort som lämnar sidan bakom synlig, inte en fullskärmsöverlagring: höjdtaket är `min(440px, 58dvh)` (`dvh` så att mobilens adressfält inte spräcker höjden) och panelen spänner `left/right: 10px` i stället för fast bredd. Medvetet val — chatten highlightar avgångskort och väljer platser i sidan bakom sig, och fullskärm hade dolt exakt det. I liggande läge sänks taket till `min(300px, 70dvh)` och snabbknapparna döljs
- **Tåginformation** – AI:n känner till tågtyper (X2000, SJ 3000/X55, MTRX, Öresundståg m.fl.), WiFi/5G och satellituppkoppling ombord, vilka tåg som går var, och restider för topp 5-rutter
- **Tågbilder** – när AI:n nämner en tågtyp (X2000, SJ 3000, MTRX X74, Öresundståg, Snälltåget, MTR Express, Västtåg X61) visas motsvarande tågfoto automatiskt under svaret
- **Var finns toaletten?** – frågor om toalett, bistro eller närmaste plats besvaras med vagn och radnummer ur vagnsskissen, och skissen ritas upp under svaret med källhänvisning (`vagnskiss://<id>`). Har du valt plats är avstånden redan uträknade: "närmaste toalett i vagn 2, ≈1 rad bort; bistron i vagn 3, ≈4 rader"
- **Föreslår ledig plats** – "föreslå en ledig fönsterplats närmast bistron" ger ett konkret förslag plus klickbara knappar som öppnar platskartan och väljer platsen direkt. Filter finns för fönster-, gång- och bordsplats. Beläggningen bor i platskartan och skickas med frågan, så servern aldrig gissar vilka platser som är bokade — och prompten får bara rekommendera ur den listan
- **Streaming-svar** – svaret strömmar direkt token för token från Groq till chatbubblan utan att vänta på hela svaret, via `/api/chat/stream` (SSE); automatisk fallback till `/api/chat` om webbläsaren saknar ReadableStream-stöd
- **Dynamiska follow-up chips** – efter varje svar visas 2–3 kontextuella snabbknappar baserade på vad AI:n svarade (tågtyp, pris, WiFi, restid)
- **Avgångshighlighting** – om AI:n nämner en avgångstid (t.ex. "07:45") scrollas och highlightas det avgångskortet automatiskt i listan
- **Rate limiting** – chatboten begränsad till 10 meddelanden per IP per minut (glidande fönster)
- **Historiktrimning** – max 8 meddelanden skickas till Groq per anrop, äldre meddelanden klipps bort för att hålla token-användningen i schack
- **Cache** – destinationsförslag (`/suggest`) cachas 2 h per (from+kategori); nåbara destinationer per startstation cachas 30 min; avgångssvar cachas 2 min per from+till+datum

### Övrigt
- **Skeleton loader** – animerade shimmer-platshållare visas omedelbart när sökning startar, ersätts av riktiga avgångskort när svaret kommer
- **Favoritresor** – hjärtknapp i sökresultatet sparar aktuell rutt (Från → Till) i webbläsaren; sparade rutter syns som snabbknappar under sökformuläret; max 5 favoriter, enkelt att ta bort
- **Dela avgång** – 🔗-knapp på varje avgångskort genererar en delbar länk med från/till/datum som URL-parametrar; `navigator.share` på mobil, kopierar till urklipp på desktop; sidan auto-söker direkt om URL-parametrar finns vid sidladdning
- **AI-förslag** – tre kategoriknappar (Storstad / Natur / Strand) via Groq AI
- **CO2-besparing** – visar kg CO2 sparat jämfört med bilresa
- **Mobilanpassad** – responsiv layout med media queries för smala skärmar; chattpanelens brytpunkt ligger på 640 px så att även bredare telefoner (Pixel 412 px, iPhone Pro Max 430 px) träffas, plus en egen regel för liggande läge (`max-height: 480px`)
- **UptimeRobot** – pingar `/health` var 5:e minut, håller Render-instansen varm
- **PWA-stöd** – `manifest.json` gör appen installerbar på Android/iOS via "Lägg till på startskärm"

## Bildkällor

| Bild | Källa | Licens |
|---|---|---|
| `train-sj-3000.jpg` | [SJ X55 på Wikimedia Commons](https://commons.wikimedia.org/wiki/File:SJ_X55.jpg) (foto: SJ AB) | CC BY 3.0 |

## Teknikstack

| Lager | Teknik |
|---|---|
| Backend | Java 21, Spring Boot 3.2.5, Thymeleaf |
| Avgångsdata | Trafikverket Open Data API — `TrainAnnouncement` i namespace `rail.trafficinfo`, schemaversion 2.0 |
| AI-chatbot | Groq API (`openai/gpt-oss-120b`, `reasoning_effort: low`), avgångskontextuell, max 8 meddelanden historik |
| AI-förslag | Groq API (`openai/gpt-oss-120b`, `reasoning_effort: low`), cache 2 h per (from+kategori) |
| Deploy | Render (Docker, free tier) |
| Frontend | Inbyggd via `<iframe>` i WordPress/Gutenberg |

## Tester & CI

55 tester i tre lager — ren logik, HTTP-felvägar och controller-lagret (MockMvc, tjänsterna mockas):

| Testklass | Täcker |
|-----------|--------|
| `TrainModelServiceTest` (18) | Prislogiken (MiniPris slutar på 9, klassordning, determinism), operatörsmappning, SJ 3000-valet på tågnummer, bekräftad avgång, produktnamn och destination, tågnummerserier när operatör saknas, platser kvar, restid |
| `TrainLayoutServiceTest` (11) | Vagnsskisserna: alla layouter har vagnar/toalett/bistro, SJ 3000:s fyra vagnar, avstånd till bistro och närmaste toalett över vagnsgräns, platsfakta och promptbeskrivningen, fönster-/gångplatser och bordsgrupper |
| `GroqChatServiceTest` (5) | Meddelandelistan: systemprompt, avgångskontext, historiktrimning till 8, konfigurationskoll |
| `GroqChatServiceHttpTest` (5) | HTTP-felvägar mot lokal stubbserver: 429/401/5xx ger begripliga fel, svar utan content ger standardtext |
| `WebControllerTest` (12) | Autocomplete-sortering (prefix först), valideringsfel 400, auto-imorgon-logiken, chattens rate limit → 429, `/api/train-layout` (JSON + 404) och att skiss + platsfakta hamnar i chattkontexten |
| `SuggestControllerTest` (4) | Kategorivalidering 400, health, rate limit → 429 |

```bash
cd backend
mvn test
```

GitHub Actions ([maven.yml](.github/workflows/maven.yml)) kör testerna på varje push — badgen överst visar status.

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
| `GROQ_MODEL` | (valfri) Groq-modell för chatbot + AI-förslag — default `openai/gpt-oss-120b` |

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
