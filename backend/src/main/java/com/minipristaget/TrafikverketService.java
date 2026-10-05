package com.minipristaget;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** @author Robert Andersson Kopler */
@Service
public class TrafikverketService {

    private static final String API_URL = "https://api.trafikinfo.trafikverket.se/v2/data.json";
    private static final Pattern WGS84_PATTERN = Pattern.compile("POINT \\(([\\d.]+) ([\\d.]+)\\)");

    @Value("${trafikverket.api.key:}")
    private String apiKey;

    @Autowired
    private TrainModelService trainModelService;

    private final HttpClient   httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper     = new ObjectMapper();

    private static final long DEPARTURE_CACHE_TTL_MS = 2 * 60 * 1000L;
    private record DepartureCacheEntry(List<TrainDeparture> departures, long timestamp) {}

    private List<TrainStation> stationCache = null;
    private Map<String, TrainStation> stationIndex = null;
    private final ConcurrentHashMap<String, DepartureCacheEntry> departureCache = new ConcurrentHashMap<>();

    // ── Hämta alla stationer (cachas i minnet) ────────────────────
    public List<TrainStation> getAllStations() throws Exception {
        if (stationCache != null) return stationCache;

        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainStation" schemaversion="1">
                <FILTER>
                  <EQ name="Advertised" value="true"/>
                </FILTER>
                <INCLUDE>LocationSignature</INCLUDE>
                <INCLUDE>AdvertisedLocationName</INCLUDE>
                <INCLUDE>Geometry.WGS84</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey);

        JsonNode result   = callApi(xml);
        JsonNode stations = result.path("RESPONSE").path("RESULT").get(0).path("TrainStation");

        List<TrainStation> list = new ArrayList<>();
        if (stations.isArray()) {
            for (JsonNode s : stations) {
                String sig  = s.path("LocationSignature").asString();
                String name = s.path("AdvertisedLocationName").asString();
                String wgs  = s.path("Geometry").path("WGS84").asString();
                Matcher m   = WGS84_PATTERN.matcher(wgs);
                if (m.find()) {
                    double lon = Double.parseDouble(m.group(1));
                    double lat = Double.parseDouble(m.group(2));
                    list.add(new TrainStation(sig, name, lat, lon));
                }
            }
        }
        stationCache = list;
        Map<String, TrainStation> index = new HashMap<>();
        for (TrainStation s : list) index.put(s.getSignature().toUpperCase(), s);
        stationIndex = index;
        return list;
    }

    // ── Närmaste station till GPS-koordinater ────────────────────
    public Optional<TrainStation> findNearestStation(double lat, double lon) throws Exception {
        return getAllStations().stream()
            .min((a, b) -> Double.compare(haversine(lat, lon, a.getLat(), a.getLon()),
                                          haversine(lat, lon, b.getLat(), b.getLon())));
    }

    // ── Sök station på namn ───────────────────────────────────────
    public Optional<TrainStation> findStationByName(String name) throws Exception {
        return bestMatch(getAllStations(), name);
    }

    /**
     * Exakt namn först, sedan stadens centralstation ("Göteborg" → "Göteborg C"), sist första
     * station som innehåller texten. Förut vann den första som INNEHÖLL texten, i den ordning
     * Trafikverket råkade lista stationerna — "Göteborg" kunde bli Olskroken eller Sävenäs.
     */
    /**
     * Namn som folk skriver men som Trafikverket inte använder. Kastrup heter "Copenhagen
     * Airport" i datan och gav noll träffar; "København"/"Copenhagen" hittade inte Köpenhamn H.
     */
    static final Map<String, String> ALIAS = Map.ofEntries(
        Map.entry("kastrup", "Copenhagen Airport"),
        Map.entry("kastrup flygplats", "Copenhagen Airport"),
        Map.entry("köpenhamns flygplats", "Copenhagen Airport"),
        Map.entry("köpenhamn flygplats", "Copenhagen Airport"),
        Map.entry("köpenhamn kastrup", "Copenhagen Airport"),
        Map.entry("københavns lufthavn", "Copenhagen Airport"),
        Map.entry("kobenhavns lufthavn", "Copenhagen Airport"),
        Map.entry("cph", "Copenhagen Airport"),
        Map.entry("cph airport", "Copenhagen Airport"),
        Map.entry("köpenhamn", "Köpenhamn H"),
        Map.entry("köpenhamn central", "Köpenhamn H"),
        Map.entry("københavn", "Köpenhamn H"),
        Map.entry("københavn h", "Köpenhamn H"),
        Map.entry("kobenhavn", "Köpenhamn H"),
        Map.entry("copenhagen", "Köpenhamn H"),
        Map.entry("copenhagen central", "Köpenhamn H"),
        Map.entry("arlanda", "Arlanda C"),
        Map.entry("arlanda flygplats", "Arlanda C"),
        Map.entry("oslo s", "Oslo"),
        Map.entry("oslo sentralstasjon", "Oslo"));

    /** Stationsnamn vars alias BÖRJAR med det man skrivit — för autokompletteringen. */
    public static List<String> aliasSuggestions(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.length() < 3) return List.of();   // "Kö" ska inte ge Copenhagen Airport överst
        return ALIAS.entrySet().stream()
            .filter(e -> e.getKey().startsWith(q))
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue).distinct().toList();
    }

    static Optional<TrainStation> bestMatch(List<TrainStation> stations, String name) {
        String lower = name.trim().toLowerCase();
        String alias = ALIAS.get(lower);
        if (alias != null) {
            Optional<TrainStation> a = stations.stream().filter(s -> s.getName().equalsIgnoreCase(alias)).findFirst();
            if (a.isPresent()) return a;
        }
        Optional<TrainStation> exakt = stations.stream()
            .filter(s -> s.getName().equalsIgnoreCase(lower)).findFirst();
        if (exakt.isPresent()) return exakt;
        Optional<TrainStation> central = stations.stream()
            .filter(s -> s.getName().equalsIgnoreCase(lower + " c")).findFirst();
        if (central.isPresent()) return central;
        Optional<TrainStation> innehaller = stations.stream()
            .filter(s -> s.getName().toLowerCase().contains(lower))
            .findFirst();
        if (innehaller.isPresent()) return innehaller;
        // "Trollhättan C" / "Oslo S": Trafikverket heter "Trollhättan" och "Oslo". Ett
        // stationssuffix för mycket gav "Hittade ingen station" — prova utan det.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?)\\s+(c|s|central|centralstation)$").matcher(lower);
        return m.matches() ? bestMatch(stations, m.group(1)) : Optional.empty();
    }

    // ── Hämta avgångar ────────────────────────────────────────────
    public List<TrainDeparture> getDepartures(String fromSignature, String toName, LocalDate date) throws Exception {
        String cacheKey = fromSignature + "|" + (toName != null ? toName.toLowerCase() : "") + "|" + date;
        DepartureCacheEntry hit = departureCache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.timestamp() < DEPARTURE_CACHE_TTL_MS)
            return hit.departures();

        boolean harMal = toName != null && !toName.isBlank();
        // Med mål: hela dygnet. Taket låg på 200, och Stockholm C har fler avgångar än så
        // (pendeltåg inräknade) — en sökning på ett annat datum än i dag tappade därför
        // kvällens tåg utan att någon märkte det. Uppmätt 2026-09-27: 300 räckte till ~22.
        int limit = harMal ? 1000 : 50;
        // Trafikverket times are in Swedish local time — use Stockholm timezone for comparison
        ZoneId stockholm = ZoneId.of("Europe/Stockholm");
        ZonedDateTime nowSweden = ZonedDateTime.now(stockholm);
        String fromTime = date.equals(LocalDate.now(stockholm))
            ? nowSweden.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            : "00:00:00";
        // namespace krävs från 2026-09-02: Trafikverket flyttar TrainAnnouncement till
        // "rail.trafficinfo". Frågor upp till schemaversion 1.9 dirigeras om automatiskt, så det
        // här är inget som HADE slutat fungera — men omdirigeringen är en övergångslösning, och
        // natten 1-2/9 väntas avbrott. Se data.trafikverket.se/news/changes-in-trainannouncement.
        //
        // schemaversion 2.0 kräver namespace och är därför möjlig först nu. Bytet gjordes efter
        // att svaren jämförts fält för fält mot 1.8 på skarpt API 2026-08-19: identiska för allt
        // vi läser, både för tåg i tid och för 25 försenade (EstimatedTimeAtLocation, Canceled,
        // ProductInformation, ToLocation, TrainOwner). Kommer nya fält i 2.0 rör de inte oss —
        // INCLUDE-listan nedan avgör vad som hämtas.
        //
        // orderby är INTE kosmetik och måste följa med i samma ändring: den nya datamängden
        // returnerar träffarna i en annan ordning än den gamla. Uppmätt 2026-08-19 mot skarpt API
        // för Göteborg C, samma filter och limit: utan namespace kom 18:04, 18:05, 18:10, …
        // (tidsordning) — med namespace kom 21:00, 22:20, 23:20 först. Eftersom limit kapar
        // listan på API-sidan och avgångarna aldrig sorteras om här hade kortet visat kvällens
        // sista tåg i stället för de närmaste. Ett namespace-tillägg utan orderby är alltså
        // en tyst regression, inte en no-op.
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="%d" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <EQ name="LocationSignature" value="%s"/>
                    <EQ name="ActivityType" value="Avgang"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT%s"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT23:59:59"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>EstimatedTimeAtLocation</INCLUDE>
                <INCLUDE>ToLocation</INCLUDE>
                <INCLUDE>ViaToLocation</INCLUDE>
                <INCLUDE>TrainOwner</INCLUDE>
                <INCLUDE>Canceled</INCLUDE>
                <!-- Produktnamnet ("SJ Snabbtåg", "SJ Regional"…). Trafikverket anger ALDRIG
                     fordonstyp, så X2000/SJ 3000 går inte att läsa ut — men produktnamnet
                     skiljer i alla fall snabbtåg från regionaltåg. -->
                <INCLUDE>ProductInformation</INCLUDE>
                <INCLUDE>TypeOfTraffic</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, limit, fromSignature, date, fromTime, date);

        JsonNode result        = callApi(xml);
        JsonNode announcements = result.path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");

        // ── Stannar tåget verkligen vid målet, och när är det framme? ──
        // ToLocation är bara tågets SLUTstation, och restiden gissades förut ur fågelvägen och
        // en snitthastighet per tågtyp. Det gav fel på två sätt: tåg som passerar målet utan
        // att stanna (eller slutar före) kunde visas, och en SJ Regional via Västerås–Örebro
        // (~4,5 h till Göteborg) fick snabbtågets restid. Trafikverket har ankomsten vid
        // målstationen — samma tågnummer samma dag — så den hämtas i stället för att gissas.
        TrainStation toSt = harMal ? findStationByName(toName).orElse(null) : null;
        // Danska mål: Trafikverket publicerar inga tider för Kastrup eller Köpenhamn H — tågen
        // står bara som "till Köpenhamn H". Sista svenska stationen, Hyllie, har riktiga tider,
        // så ankomsten räknas därifrån plus den fasta körtiden över bron.
        Integer broMinuter = toSt != null ? DANSKA_MAL.get(toSt.getName()) : null;
        TrainStation hyllie = broMinuter != null && stationCache != null ? bestMatch(stationCache, "Hyllie").orElse(null) : null;
        JsonNode ankomsterRa = toSt == null ? null
            : getArrivalsRaw(hyllie != null ? hyllie.getSignature() : toSt.getSignature(), date);
        Map<String, List<Ankomst>> ankomster = hyllie != null
            ? forskjut(arrivalsByTrain(tillDanmark(ankomsterRa)), broMinuter) : arrivalsByTrain(ankomsterRa);

        List<TrainDeparture> departures = new ArrayList<>();
        Map<TrainDeparture, Integer> riktigRestid = new HashMap<>();
        if (announcements.isArray()) {
            for (JsonNode ann : announcements) {
                if (!harMal) { departures.add(parseAnnouncement(ann)); continue; }
                if (!ankomster.isEmpty()) {
                    // Ankomsten är känd: tåget ska stanna vid målet EFTER avgången här.
                    String depIso = ann.path("AdvertisedTimeAtLocation").asString();
                    Ankomst a = firstArrivalAfter(depIso, ankomster.get(ann.path("AdvertisedTrainIdent").asString()));
                    if (a == null) {
                        // Slutstation utan ankomstpost: stationer utanför det svenska nätet
                        // (Oslo) annonseras som mål men aldrig med en ankomst. Vy:s direkttåg
                        // Göteborg → Oslo föll därför bort. Restiden uppskattas för dem.
                        if (toSt != null && slutarVid(ann, toSt.getSignature())) departures.add(parseAnnouncement(ann));
                        continue;
                    }
                    TrainDeparture dep = parseAnnouncement(ann);
                    setEstimatedArrival(dep, a);
                    departures.add(dep);
                    riktigRestid.put(dep, travelMinutes(depIso, a.planerad()));
                } else if (matchesDestination(ann, toName)) {
                    // Ankomsterna gick inte att hämta — gamla slutstationsmatchningen som reserv
                    departures.add(parseAnnouncement(ann));
                }
            }
        }

        // Enrich with train model info, price and travel time
        TrainStation fromSt = stationIndex != null ? stationIndex.get(fromSignature.toUpperCase()) : null;

        for (TrainDeparture dep : departures) {
            TrainModelService.TrainModelInfo model = trainModelService.resolveModel(
                    dep, fromSt != null ? fromSt.getName() : null);
            dep.setTrainModel(model.name());
            dep.setTrainColor(model.color());
            dep.setTrainImage(model.imageUrl());
            dep.setTransfers(0);
            dep.setOperatorName(TrainModelService.operatorName(
                dep.getOperator(), dep.getProductInformation(), dep.getTypeOfTraffic()));

            // Målet är stationen man SÖKT, inte tågets slutstation. Göteborg → Katrineholm
            // visade "Stockholm C" i platskartan, på kvittot och i Mina bokningar, fast man
            // kliver av i Katrineholm. Slutstationen sparas som "fortsätter mot". Görs EFTER
            // resolveModel: fordonsregeln för SJ 3000 läser tågets verkliga slutstation.
            if (toSt != null && riktigRestid.containsKey(dep)
                    && !toSt.getName().equalsIgnoreCase(dep.getDestination())) {
                dep.setFinalDestination(dep.getDestination());
                dep.setDestination(toSt.getName());
            }

            if (fromSt != null && dep.getDestinationSignature() != null && stationIndex != null) {
                // Pris och CO2 räknas till stationen man SÖKT, inte till tågets slutstation —
                // ett tåg som fortsätter förbi målet ska inte bli dyrare för det.
                TrainStation prisMal = toSt != null && riktigRestid.containsKey(dep)
                    ? toSt : stationIndex.get(dep.getDestinationSignature().toUpperCase());

                if (prisMal != null) {
                    double dist = haversine(fromSt.getLat(), fromSt.getLon(),
                                            prisMal.getLat(), prisMal.getLon());
                    TrainModelService.Priskategori pk = TrainModelService.kategori(model);
                    dep.setPrice(trainModelService.calculatePrice(dist, dep.getTrainId(), pk));
                    dep.setPriceLugn(trainModelService.calculatePriceLugn(dist, dep.getTrainId(), pk));
                    dep.setPrice1klass(trainModelService.calculatePrice1Klass(dist, dep.getTrainId(), pk));
                    dep.setPriceOriginal(trainModelService.calculateOrdinaryPrice(dist, dep.getTrainId(), pk));
                    dep.setSeatsLeft(trainModelService.calculateSeatsLeft(dep.getTrainId()));
                    dep.setHasSeatMap(model.hasSeatMap());
                    dep.setSeatLayout(model.seatLayout());
                    Integer riktig = riktigRestid.get(dep);
                    dep.setTravelMinutes(riktig != null ? riktig
                        : trainModelService.estimateTravelMinutes(dist, model.avgSpeedKmh()));
                    // CO2 savings: car ~110 g/km vs Swedish train ~6 g/km
                    double co2 = Math.round((110.0 - 6.0) * dist / 1000.0 * 10.0) / 10.0;
                    dep.setCo2SavedKg(co2);
                }
            }
        }

        // ── Resor med ETT byte, när direkttågen inte räcker ──
        // Förut listades bara direkttåg, och en sträcka som Stockholm → Kalmar blev tom.
        if (fromSt != null && toSt != null && !ankomster.isEmpty() && departures.size() < 3
                && announcements.isArray()) {
            java.util.Set<String> direkta = new java.util.HashSet<>();
            for (TrainDeparture d : departures) direkta.add(d.getTrainId());
            departures.addAll(findTransferTrips(fromSt, toSt, date, fromTime, announcements,
                                                ankomster, direkta,
                                                reach(ankomsterRa, "FromLocation", "ViaFromLocation")));
            departures.sort(java.util.Comparator.comparing(TrainDeparture::getDepartureTime));
        }

        departureCache.put(cacheKey, new DepartureCacheEntry(departures, System.currentTimeMillis()));
        return departures;
    }

    // ── Byten ─────────────────────────────────────────────────────
    /**
     * Stationer där fjärr- och regionaltåg möts. Bara de som ligger nära linjen mellan start
     * och mål provas (se {@link #chooseHubs}), och högst fyra per sökning — varje bytesstation
     * kostar två anrop mot Trafikverket.
     */
    private static final List<String> BYTESSTATIONER = List.of(
        "Stockholm C", "Göteborg C", "Malmö C", "Hallsberg", "Norrköping C", "Linköping C",
        "Mjölby", "Nässjö C", "Alvesta", "Hässleholm", "Lund C", "Södertälje Syd", "Örebro C",
        "Katrineholm C", "Skövde C", "Falköping C", "Herrljunga C", "Jönköping C", "Växjö",
        "Kristianstad C", "Uppsala C", "Gävle C", "Sundsvall C", "Borlänge C", "Västerås C",
        "Karlstad C", "Halmstad C", "Kalmar C", "Karlskrona C");

    static final int MIN_BYTESTID = 6;
    // 180, inte 120: nattåg går sällan — Kiruna → Luleå är framme 18:50, nattåget mot
    // Stockholm går 21:09. Med 120 minuter blev Kiruna → Stockholm tom.
    static final int MAX_VANTETID = 180;

    /** Ett tåg mellan två stationer: avgång och ankomst som ISO-tider. */
    record Leg(String trainId, String depIso, String arrIso, JsonNode ann, Ankomst ankomst) {}

    /**
     * En resa med byten: tågen i ordning (två = ett byte, tre = två byten) och
     * bytesstationerna däremellan.
     */
    record Connection(List<Leg> legs, List<TrainStation> hubs) {
        Connection(Leg a, Leg b) { this(List.of(a, b), List.of()); }
        Leg first()  { return legs.get(0); }
        Leg second() { return legs.get(1); }
        Leg last()   { return legs.get(legs.size() - 1); }
    }

    /** Ankomster och avgångar vid en bytesstation — hämtas EN gång och delas av alla varianter. */
    private record HubData(TrainStation hub, Map<String, List<Ankomst>> ankomster, JsonNode avgangar) {}

    private List<TrainDeparture> findTransferTrips(TrainStation fromSt, TrainStation toSt, LocalDate date,
                                                   String fromTime, JsonNode fromAnnouncements,
                                                   Map<String, List<Ankomst>> ankomsterVidMal,
                                                   java.util.Set<String> direkta,
                                                   java.util.Set<String> tillMal) {
        List<TrainStation> kandidater = new ArrayList<>();
        for (String namn : BYTESSTATIONER) {
            if (stationCache != null) bestMatch(stationCache, namn).ifPresent(kandidater::add);
        }
        // Stationer som tågen HÄRIFRÅN når och som tågen TILL målet kommer ifrån är riktiga
        // bytespunkter även om de inte står i listan (t.ex. Borlänge för Mora).
        java.util.Set<String> franStart = reach(fromAnnouncements, "ToLocation", "ViaToLocation");
        if (stationIndex != null) {
            for (String sig : franStart) {
                TrainStation s = stationIndex.get(sig);
                if (s != null && tillMal.contains(sig) && kandidater.stream().noneMatch(k -> k.getSignature().equalsIgnoreCase(sig)))
                    kandidater.add(s);
            }
        }
        List<TrainStation> hubs = franStart.isEmpty() || tillMal.isEmpty()
            ? chooseHubs(fromSt, toSt, kandidater, 4)          // reserv: Via-fälten saknas i svaret
            : chooseHubsByReach(fromSt, toSt, kandidater, franStart, tillMal, 6);

        // Bytesstationerna hämtas parallellt — sekventiellt blev sökningen märkbart seg
        List<java.util.concurrent.CompletableFuture<HubData>> jobb = new ArrayList<>();
        for (TrainStation hub : hubs) {
            jobb.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> new HubData(hub,
                getArrivals(hub.getSignature(), date), getDepartureAnnouncements(hub.getSignature(), date, fromTime))));
        }
        List<HubData> data = new ArrayList<>();
        for (var j : jobb) {
            try { data.add(j.get(20, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception e) { /* en bytesstation som inte svarar hoppas över */ }
        }

        // Ett byte
        List<Connection> alla = new ArrayList<>();
        for (HubData h : data) {
            for (Connection c : connect(legsTo(fromAnnouncements, h.ankomster()),
                                        legsTo(h.avgangar(), ankomsterVidMal), direkta)) {
                alla.add(new Connection(c.legs(), List.of(h.hub())));
            }
        }

        // Två byten — bara när ett byte inte räckte. Göteborg → Umeå blev tom: från Stockholm
        // går tågen norrut bara till Sundsvall, så resan kräver två byten. Bytesstationerna
        // tas i ordning längs vägen (den närmare starten först).
        if (alla.isEmpty()) {
            for (HubData h1 : data) {
                for (HubData h2 : data) {
                    if (h1 == h2 || avstand(fromSt, h1.hub()) >= avstand(fromSt, h2.hub())) continue;
                    // Andra bytet måste ligga NÄRMARE målet än det första — annars blir det
                    // resor som går bakåt (Göteborg → Karlstad → Kristinehamn → Oslo).
                    if (avstand(h2.hub(), toSt) >= avstand(h1.hub(), toSt)) continue;
                    List<Connection> tvaForsta = connect(legsTo(fromAnnouncements, h1.ankomster()),
                                                         legsTo(h1.avgangar(), h2.ankomster()), direkta);
                    for (Connection c : extend(tvaForsta, legsTo(h2.avgangar(), ankomsterVidMal))) {
                        alla.add(new Connection(c.legs(), List.of(h1.hub(), h2.hub())));
                    }
                }
            }
        }

        List<TrainDeparture> ut = new ArrayList<>();
        double dist = haversine(fromSt.getLat(), fromSt.getLon(), toSt.getLat(), toSt.getLon());
        for (Connection c : pareto(alla)) ut.add(toDeparture(c, fromSt, toSt, dist));
        return ut;
    }

    private double avstand(TrainStation a, TrainStation b) {
        return haversine(a.getLat(), a.getLon(), b.getLat(), b.getLon());
    }

    /** En resa med byten som ett avgångskort: första tåget bär tiden, resten beskrivs som byten. */
    private TrainDeparture toDeparture(Connection c, TrainStation fromSt, TrainStation toSt, double dist) {
        TrainDeparture dep = parseAnnouncement(c.first().ann());
        List<String> modeller = new ArrayList<>();
        List<String> bolag = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        TrainModelService.TrainModelInfo forstaModell = null;
        // Priset: fjärrtågets prissättning om NÅGON delsträcka är SJ/VR/Vy/nattåg, annars regionalt
        TrainModelService.Priskategori pk = TrainModelService.Priskategori.REGIONAL;
        for (int i = 0; i < c.legs().size(); i++) {
            Leg l = c.legs().get(i);
            TrainDeparture d = i == 0 ? dep : parseAnnouncement(l.ann());
            TrainModelService.TrainModelInfo m = trainModelService.resolveModel(d,
                i == 0 ? fromSt.getName() : c.hubs().get(i - 1).getName());
            if (i == 0) forstaModell = m;
            if (!modeller.contains(m.name())) modeller.add(m.name());
            TrainModelService.Priskategori k = TrainModelService.kategori(m);
            if (k == TrainModelService.Priskategori.SNABB || k == TrainModelService.Priskategori.NATT) pk = TrainModelService.Priskategori.SNABB;
            String b = TrainModelService.operatorName(d.getOperator(), d.getProductInformation(), d.getTypeOfTraffic());
            if (!bolag.contains(b)) bolag.add(b);
            if (i > 0) ids.add(l.trainId());
        }
        List<String> stopp = new ArrayList<>();
        List<String> detaljer = new ArrayList<>();
        for (int i = 0; i < c.hubs().size(); i++) {
            stopp.add(c.hubs().get(i).getName());
            detaljer.add(c.hubs().get(i).getName() + " " + formatTime(c.legs().get(i).arrIso())
                         + " → " + formatTime(c.legs().get(i + 1).depIso()));
        }
        String kombinerat = c.first().trainId() + "+" + String.join("+", ids);

        dep.setDestination(toSt.getName());
        dep.setDestinationSignature(toSt.getSignature());
        dep.setTransfers(c.legs().size() - 1);
        dep.setTransferStation(String.join(" och ", stopp));
        dep.setTransferStops(stopp);
        dep.setTransferDetails(detaljer);
        dep.setTransferArrival(formatTime(c.first().arrIso()));
        dep.setTransferDeparture(formatTime(c.second().depIso()));
        dep.setSecondTrainId(String.join("+", ids));
        dep.setTrainModel(String.join(" + ", modeller));
        dep.setOperatorName(bolag.get(0));
        dep.setTrainColor(forstaModell.color());
        dep.setTrainImage(forstaModell.imageUrl());
        dep.setHasSeatMap(false);      // platskartan gäller ett tåg — med byte väljs plats ombord
        dep.setSeatLayout("none");
        dep.setTravelMinutes(travelMinutes(c.first().depIso(), c.last().arrIso()));
        setEstimatedArrival(dep, c.last().ankomst());
        dep.setPrice(trainModelService.calculatePrice(dist, kombinerat, pk));
        dep.setPriceLugn(trainModelService.calculatePriceLugn(dist, kombinerat, pk));
        dep.setPrice1klass(trainModelService.calculatePrice1Klass(dist, kombinerat, pk));
        dep.setPriceOriginal(trainModelService.calculateOrdinaryPrice(dist, kombinerat, pk));
        dep.setSeatsLeft(trainModelService.calculateSeatsLeft(kombinerat));
        dep.setCo2SavedKg(Math.round((110.0 - 6.0) * dist / 1000.0 * 10.0) / 10.0);
        return dep;
    }

    /** Förläng resor med ett tåg till: det som är framme först, inom bytestiden. */
    static List<Connection> extend(List<Connection> resor, List<Leg> nasta) {
        List<Connection> ut = new ArrayList<>();
        for (Connection c : resor) {
            Leg bast = bastaAnslutning(c.last(), nasta, c);
            if (bast == null) continue;
            List<Leg> legs = new ArrayList<>(c.legs());
            legs.add(bast);
            ut.add(new Connection(legs, c.hubs()));
        }
        return ut;
    }

    /** Tåget från bytet som är framme först, 6–120 min efter ankomsten, och inte redan i resan. */
    private static Leg bastaAnslutning(Leg in, List<Leg> kandidater, Connection resa) {
        Leg bast = null;
        for (Leg l2 : kandidater) {
            if (l2.trainId().equals(in.trainId())) continue;
            if (resa != null && resa.legs().stream().anyMatch(l -> l.trainId().equals(l2.trainId()))) continue;
            Integer byte_ = travelMinutes(in.arrIso(), l2.depIso());
            if (byte_ == null || byte_ < MIN_BYTESTID || byte_ > MAX_VANTETID) continue;
            if (bast == null || travelMinutes(l2.arrIso(), bast.arrIso()) != null) bast = l2;
        }
        return bast;
    }

    /**
     * Bytesstationer som ligger på vägen: omvägen via stationen får vara högst 60 % längre
     * än fågelvägen (järnvägen svänger: Stockholm → Alvesta → Kalmar är 1,6), och start/mål
     * räknas inte. Kortast omväg först.
     */
    static List<TrainStation> chooseHubs(TrainStation from, TrainStation to, List<TrainStation> kandidater, int max) {
        double direkt = haversineStatic(from.getLat(), from.getLon(), to.getLat(), to.getLon());
        if (direkt < 1) return List.of();
        record Kandidat(TrainStation s, double kvot) {}
        List<Kandidat> ok = new ArrayList<>();
        for (TrainStation h : kandidater) {
            double a = haversineStatic(from.getLat(), from.getLon(), h.getLat(), h.getLon());
            double b = haversineStatic(h.getLat(), h.getLon(), to.getLat(), to.getLon());
            if (a < 5 || b < 5) continue;
            double kvot = (a + b) / direkt;
            if (kvot <= 1.6) ok.add(new Kandidat(h, kvot));
        }
        ok.sort(java.util.Comparator.comparingDouble(Kandidat::kvot));
        List<TrainStation> ut = new ArrayList<>();
        for (Kandidat k : ok) {
            if (ut.stream().noneMatch(s -> s.getSignature().equals(k.s().getSignature()))) ut.add(k.s());
            if (ut.size() == max) break;
        }
        return ut;
    }

    /** Avgångarna i {@code anns} som når en station med kända ankomster, efter avgången. */
    static List<Leg> legsTo(JsonNode anns, Map<String, List<Ankomst>> ankomster) {
        List<Leg> legs = new ArrayList<>();
        if (anns == null || !anns.isArray() || ankomster.isEmpty()) return legs;
        for (JsonNode ann : anns) {
            if (ann.path("Canceled").asBoolean(false)) continue;
            String id  = ann.path("AdvertisedTrainIdent").asString("");
            String dep = ann.path("AdvertisedTimeAtLocation").asString("");
            Ankomst a = firstArrivalAfter(dep, ankomster.get(id));
            if (a != null) legs.add(new Leg(id, dep, a.planerad(), ann, a));
        }
        return legs;
    }

    /**
     * Para ihop tåg 1 (till bytet) med det tåg 2 (från bytet) som är FRAMME först, inom
     * {@value #MIN_BYTESTID}–{@value #MAX_VANTETID} minuters bytestid. Tåg 1 som redan går
     * direkt till målet hoppas över — den resan står redan i listan som direkttåg.
     */
    static List<Connection> connect(List<Leg> forsta, List<Leg> andra, java.util.Set<String> direkta) {
        List<Connection> ut = new ArrayList<>();
        for (Leg l1 : forsta) {
            if (direkta.contains(l1.trainId())) continue;
            Leg bast = bastaAnslutning(l1, andra, null);
            if (bast != null) ut.add(new Connection(l1, bast));
        }
        return ut;
    }

    /**
     * Behåll bara resor som ingen annan slår: en resa som avgår tidigare OCH är framme
     * senare (eller samtidigt) än en annan är onödig. Samma tåg 2 nås ofta från flera tåg 1
     * — bara det SENASTE tåg 1 är värt att visa.
     */
    static List<Connection> pareto(List<Connection> alla) {
        List<Connection> ut = new ArrayList<>();
        for (Connection c : alla) {
            boolean slagen = false;
            for (Connection o : alla) {
                if (o == c) continue;
                long depC = java.time.OffsetDateTime.parse(c.first().depIso()).toEpochSecond();
                long depO = java.time.OffsetDateTime.parse(o.first().depIso()).toEpochSecond();
                long arrC = java.time.OffsetDateTime.parse(c.last().arrIso()).toEpochSecond();
                long arrO = java.time.OffsetDateTime.parse(o.last().arrIso()).toEpochSecond();
                boolean minstLikaBra = depO >= depC && arrO <= arrC;
                boolean baattre = depO > depC || arrO < arrC;
                // Exakt samma tider: behåll bara den första i listan
                boolean dubblett = depO == depC && arrO == arrC && alla.indexOf(o) < alla.indexOf(c);
                if ((minstLikaBra && baattre) || dubblett) { slagen = true; break; }
            }
            if (!slagen) ut.add(c);
        }
        return ut;
    }

    private JsonNode getDepartureAnnouncements(String signature, LocalDate date, String fromTime) {
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="1000" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <EQ name="LocationSignature" value="%s"/>
                    <EQ name="ActivityType" value="Avgang"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT%s"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT06:00:00"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>EstimatedTimeAtLocation</INCLUDE>
                <INCLUDE>ToLocation</INCLUDE>
                <INCLUDE>TrainOwner</INCLUDE>
                <INCLUDE>Canceled</INCLUDE>
                <INCLUDE>ProductInformation</INCLUDE>
                <INCLUDE>TypeOfTraffic</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, signature, date, fromTime, date.plusDays(1));
        try {
            return callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");
        } catch (Exception e) {
            return null;
        }
    }

    /** Ankomst vid en station: annonserad tid och (om Trafikverket har en) beräknad tid. */
    record Ankomst(String planerad, String beraknad) {}

    /** Beräknad ankomst sätts bara när den skiljer sig från tidtabellen. */
    private void setEstimatedArrival(TrainDeparture dep, Ankomst a) {
        if (a == null || a.beraknad() == null || a.beraknad().isBlank()) return;
        Integer sen = travelMinutes(a.planerad(), a.beraknad());
        if (sen != null && sen > 0) dep.setEstimatedArrival(formatTime(a.beraknad()));
    }

    // ── Ankomster vid en station: tågnummer → annonserad (+ beräknad) ankomsttid ──
    // Fönstret går till 14:00 nästa dygn så att kvällståg och NATTÅG kommer med (nattåg 93
    // Luleå → Stockholm är framme 12:13; med 06:00 och sedan 12:00 som gräns blev Kiruna → Stockholm tom).
    // Förr: 06:00 nästa dygn så att ett kvällståg som är framme efter midnatt
    // inte faller bort. Misslyckas anropet blir kartan tom, och sökningen faller tillbaka på
    // slutstationsmatchningen — en tom lista av ett nätverksfel vore värre än en gissad restid.
    private Map<String, List<Ankomst>> getArrivals(String toSignature, LocalDate date) {
        return arrivalsByTrain(getArrivalsRaw(toSignature, date));
    }

    /** Råa ankomstannonser, med varifrån tågen kommer (för att välja bytesstationer). */
    private JsonNode getArrivalsRaw(String toSignature, LocalDate date) {
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="2000" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <EQ name="LocationSignature" value="%s"/>
                    <EQ name="ActivityType" value="Ankomst"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT00:00:00"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT14:00:00"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>EstimatedTimeAtLocation</INCLUDE>
                <INCLUDE>FromLocation</INCLUDE>
                <INCLUDE>ViaFromLocation</INCLUDE>
                <INCLUDE>ToLocation</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, toSignature, date, date.plusDays(1));
        try {
            return callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stationssignaturerna i angivna fält (t.ex. ToLocation + ViaToLocation) över alla
     * annonser — de stationer tågen härifrån faktiskt når, eller kommer ifrån.
     */
    static java.util.Set<String> reach(JsonNode anns, String... falt) {
        java.util.Set<String> ut = new java.util.HashSet<>();
        if (anns == null || !anns.isArray()) return ut;
        for (JsonNode a : anns) {
            for (String f : falt) {
                JsonNode lista = a.path(f);
                if (!lista.isArray()) continue;
                for (JsonNode l : lista) {
                    String sig = l.path("LocationName").asString("");
                    if (!sig.isBlank()) ut.add(sig.toUpperCase());
                }
            }
        }
        return ut;
    }

    /**
     * Bytesstationer ur DATAN, inte ur geometrin. Förut valdes de stationer som låg närmast en
     * rak linje: Borås → Stockholm fick Södertälje, Mjölby, Norrköping och Linköping, fast
     * tågen från Borås går till Göteborg och Herrljunga — noll resor. Nu: stationer som tågen
     * från starten når (ToLocation/ViaToLocation) OCH som tågen till målet kommer ifrån
     * (FromLocation/ViaFromLocation) först, sedan de som bara ligger på ena sidan (för två byten).
     * Inom varje grupp kortast omväg först.
     */
    static List<TrainStation> chooseHubsByReach(TrainStation from, TrainStation to, List<TrainStation> kandidater,
                                                java.util.Set<String> franStart, java.util.Set<String> tillMal, int max) {
        List<TrainStation> baada = new ArrayList<>(), startSida = new ArrayList<>(), malSida = new ArrayList<>();
        for (TrainStation h : kandidater) {
            String sig = h.getSignature().toUpperCase();
            if (sig.equalsIgnoreCase(from.getSignature()) || sig.equalsIgnoreCase(to.getSignature())) continue;
            boolean a = franStart.contains(sig), b = tillMal.contains(sig);
            if (a && b) baada.add(h); else if (a) startSida.add(h); else if (b) malSida.add(h);
        }
        java.util.Comparator<TrainStation> omvag = java.util.Comparator.comparingDouble(h ->
            haversineStatic(from.getLat(), from.getLon(), h.getLat(), h.getLon())
          + haversineStatic(h.getLat(), h.getLon(), to.getLat(), to.getLon()));
        baada.sort(omvag); startSida.sort(omvag); malSida.sort(omvag);
        List<TrainStation> ut = new ArrayList<>(baada.subList(0, Math.min(baada.size(), max)));
        // Fyll på växelvis från båda sidor — två byten kräver en station på varje sida
        for (int i = 0; ut.size() < max && (i < startSida.size() || i < malSida.size()); i++) {
            if (i < startSida.size() && ut.size() < max) ut.add(startSida.get(i));
            if (i < malSida.size() && ut.size() < max) ut.add(malSida.get(i));
        }
        return ut;
    }

    /**
     * Den FÖRSTA ankomsten efter avgången. Ett tågnummer kan ha två ankomster i fönstret:
     * tåg 451 (Stockholm 21:22) var framme i Göteborg 01:47 natten FÖRE — gårdagens tur —
     * och 00:47 natten efter. Att ta den första i listan kastade tåget helt.
     */
    static Ankomst firstArrivalAfter(String departureIso, List<Ankomst> arrivals) {
        if (arrivals == null) return null;
        Ankomst best = null;
        Integer bestMin = null;
        for (Ankomst a : arrivals) {
            Integer min = travelMinutes(departureIso, a.planerad());
            if (min != null && (bestMin == null || min < bestMin)) { best = a; bestMin = min; }
        }
        return best;
    }

    /** Alla ankomster per tågnummer, i tidsordning. */
    static Map<String, List<Ankomst>> arrivalsByTrain(JsonNode announcements) {
        Map<String, List<Ankomst>> map = new HashMap<>();
        if (announcements != null && announcements.isArray()) {
            for (JsonNode a : announcements) {
                String id  = a.path("AdvertisedTrainIdent").asString("");
                String t   = a.path("AdvertisedTimeAtLocation").asString("");
                String est = a.path("EstimatedTimeAtLocation").asString("");
                if (!id.isBlank() && !t.isBlank())
                    map.computeIfAbsent(id, k -> new ArrayList<>()).add(new Ankomst(t, est));
            }
        }
        return map;
    }

    /**
     * Restid i minuter mellan avgång och ankomst, eller null om ankomsten saknas eller inte
     * ligger EFTER avgången (då har tåget redan passerat målet, eller går åt andra hållet).
     */
    static Integer travelMinutes(String departureIso, String arrivalIso) {
        if (departureIso == null || arrivalIso == null || departureIso.isBlank() || arrivalIso.isBlank())
            return null;
        try {
            java.time.OffsetDateTime dep = java.time.OffsetDateTime.parse(departureIso);
            java.time.OffsetDateTime arr = java.time.OffsetDateTime.parse(arrivalIso);
            long min = java.time.Duration.between(dep, arr).toMinutes();
            return min > 0 ? (int) min : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Bara tåg som FORTSÄTTER till Danmark (slutstation Dk.*). I Hyllie vänder också många
     * Pågatåg — de når aldrig Kastrup och får inte räknas som att de gör det.
     */
    static JsonNode tillDanmark(JsonNode anns) {
        tools.jackson.databind.node.ArrayNode ut = new ObjectMapper().createArrayNode();
        if (anns == null || !anns.isArray()) return ut;
        for (JsonNode a : anns) {
            JsonNode to = a.path("ToLocation");
            if (to.isArray() && to.size() > 0
                    && to.get(to.size() - 1).path("LocationName").asString("").toUpperCase().startsWith("DK.")) ut.add(a);
        }
        return ut;
    }

    /** Minuter från Hyllie till danska stationer som Öresundstågen stannar vid (körtid över bron). */
    static final Map<String, Integer> DANSKA_MAL = Map.of(
        "Copenhagen Airport", 12,
        "Köpenhamn H", 25);

    /** Ankomsterna flyttade {@code minuter} framåt — Hyllie-tiden blir tiden vid det danska målet. */
    static Map<String, List<Ankomst>> forskjut(Map<String, List<Ankomst>> ankomster, int minuter) {
        Map<String, List<Ankomst>> ut = new HashMap<>();
        ankomster.forEach((id, lista) -> ut.put(id, lista.stream().map(a -> new Ankomst(
            plus(a.planerad(), minuter), a.beraknad() == null || a.beraknad().isBlank() ? "" : plus(a.beraknad(), minuter)))
            .toList()));
        return ut;
    }

    private static String plus(String iso, int minuter) {
        try { return java.time.OffsetDateTime.parse(iso).plusMinutes(minuter).toString(); }
        catch (Exception e) { return iso; }
    }

    /** Tågets sista ToLocation är målstationen. */
    static boolean slutarVid(JsonNode ann, String signatur) {
        JsonNode locs = ann.path("ToLocation");
        return locs.isArray() && locs.size() > 0
            && locs.get(locs.size() - 1).path("LocationName").asString("").equalsIgnoreCase(signatur);
    }

    // ── Privata hjälpmetoder ──────────────────────────────────────
    private boolean matchesDestination(JsonNode ann, String toName) {
        JsonNode locs = ann.path("ToLocation");
        if (!locs.isArray() || locs.isEmpty()) return false;
        String lower = toName.toLowerCase();

        for (JsonNode loc : locs) {
            String sig = loc.path("LocationName").asString();

            if (sig.toLowerCase().startsWith(lower.substring(0, Math.min(3, lower.length())))) return true;

            if (stationIndex != null) {
                TrainStation s = stationIndex.get(sig.toUpperCase());
                if (s != null && s.getName().toLowerCase().contains(lower)) return true;
            }
        }
        return false;
    }

    private TrainDeparture parseAnnouncement(JsonNode ann) {
        TrainDeparture dep = new TrainDeparture();
        dep.setTrainId(ann.path("AdvertisedTrainIdent").asString());

        String adv = ann.path("AdvertisedTimeAtLocation").asString();
        String est = ann.path("EstimatedTimeAtLocation").asString();
        dep.setDepartureTime(formatTime(adv));
        if (!est.isBlank() && !est.equals(adv)) dep.setEstimatedTime(formatTime(est));

        dep.setOperator(ann.path("TrainOwner").asString());
        dep.setCanceled(ann.path("Canceled").asBoolean(false));
        dep.setProductInformation(readProductInformation(ann));
        dep.setTypeOfTraffic(readTypeOfTraffic(ann));

        JsonNode locs = ann.path("ToLocation");
        if (locs.isArray() && locs.size() > 0) {
            String lastSig = locs.get(locs.size() - 1).path("LocationName").asString();
            dep.setDestinationSignature(lastSig);
            String friendlyName = lastSig;
            if (stationIndex != null) {
                TrainStation found = stationIndex.get(lastSig.toUpperCase());
                if (found != null) friendlyName = found.getName();
            }
            dep.setDestination(friendlyName);
        }
        return dep;
    }

    /** ProductInformation kommer som array av objekt eller strängar beroende på schemaversion. */
    /** "Tåg", "Pendeltåg" eller "Buss" — samma form som ProductInformation. */
    private String readTypeOfTraffic(JsonNode ann) {
        JsonNode t = ann.path("TypeOfTraffic");
        if (t.isString()) return t.asString();
        if (t.isArray() && t.size() > 0) {
            JsonNode f = t.get(0);
            return f.isString() ? f.asString() : f.path("Description").asString("");
        }
        return "";
    }

    private String readProductInformation(JsonNode ann) {
        JsonNode pi = ann.path("ProductInformation");
        if (pi.isMissingNode() || pi.isNull()) return "";
        if (pi.isString()) return pi.asString();
        if (pi.isArray() && pi.size() > 0) {
            JsonNode first = pi.get(0);
            if (first.isString()) return first.asString();
            String desc = first.path("Description").asString("");
            return desc.isBlank() ? first.path("Code").asString("") : desc;
        }
        return "";
    }

    private String formatTime(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try { return iso.substring(11, 16); } catch (Exception e) { return iso; }
    }

    private JsonNode callApi(String xml) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "text/xml")
            .POST(HttpRequest.BodyPublishers.ofString(xml))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    // ── Tågets stopp längs vägen (chattens resekarta) ──────────────────────────
    //
    // "SJ Regional 177, 5 h 16 min, direkt?" — ja, men den stannar på ett tjugotal orter via
    // Västerås och Örebro. Utan stoppen såg en lång direktresa ut som ett fel. Här hämtas
    // tågets ALLA annonserade stopp för dagen, i ordning, med tider och koordinater.

    /** Ett stopp: tider som "HH:mm" (tom när tåget bara ankommer eller bara avgår där). */
    public record Stopp(String namn, String signatur, double lat, double lon, String ankomst, String avgang) {}

    private record StoppCache(List<Stopp> stopp, long tid) {}
    private final ConcurrentHashMap<String, StoppCache> stoppCache = new ConcurrentHashMap<>();
    private static final long STOPP_TTL_MS = 10 * 60 * 1000L;

    /** Tågets stopp en viss dag, i tidsordning. Tom lista när tåget inte finns eller källan inte svarar. */
    public List<Stopp> getStopp(String tagnummer, LocalDate datum) {
        if (tagnummer == null || !tagnummer.trim().matches("\\d{1,6}")) return List.of();
        String nyckel = tagnummer.trim() + "|" + datum;
        StoppCache hit = stoppCache.get(nyckel);
        if (hit != null && System.currentTimeMillis() - hit.tid() < STOPP_TTL_MS) return hit.stopp();
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="400" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <EQ name="AdvertisedTrainIdent" value="%s"/>
                    <EQ name="Advertised" value="true"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT00:00:00"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT12:00:00"/>
                  </AND>
                </FILTER>
                <INCLUDE>LocationSignature</INCLUDE>
                <INCLUDE>ActivityType</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, tagnummer.trim(), datum, datum.plusDays(1));
        try {
            getAllStations(); // stationsindexet behövs för namn och koordinater
            JsonNode anns = callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");
            List<Stopp> stopp = byggStopp(anns, stationIndex == null ? Map.of() : stationIndex);
            if (!stopp.isEmpty()) stoppCache.put(nyckel, new StoppCache(stopp, System.currentTimeMillis()));
            return stopp;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Antal stopp PÅ VÄGEN (start och mål oräknade) för flera tåg i en och samma fråga.
     *
     * <p>Avgångslistan skrev "direkt" på en X2000 som stannar fyra gånger — sant i betydelsen
     * inget byte, men det läses som non-stop. Listan har ett tjugotal tåg, och en fråga per tåg
     * hade blivit tjugo anrop mot Trafikverket per sökning; här går alla i EN fråga med IN.
     * Tåg som inte går att slå upp saknas i svaret, och kortet står då kvar på "inget byte".
     */
    public Map<String, Integer> getStoppAntal(List<String> tagnummer, LocalDate datum, String fran, String till) {
        List<String> giltiga = tagnummer.stream().map(String::trim).filter(t -> t.matches("\\d{1,6}"))
                .distinct().limit(60).toList();
        if (giltiga.isEmpty()) return Map.of();
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="6000" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <IN name="AdvertisedTrainIdent" value="%s"/>
                    <EQ name="Advertised" value="true"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT00:00:00"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT12:00:00"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>LocationSignature</INCLUDE>
                <INCLUDE>ActivityType</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, String.join(",", giltiga), datum, datum.plusDays(1));
        try {
            getAllStations();
            JsonNode anns = callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");
            return raknaStopp(anns, stationIndex == null ? Map.of() : stationIndex, fran, till);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Delar upp raderna per tåg, bygger stoppen och räknar dem mellan resenärens stationer. */
    static Map<String, Integer> raknaStopp(JsonNode anns, Map<String, TrainStation> index, String fran, String till) {
        Map<String, tools.jackson.databind.node.ArrayNode> perTag = new java.util.LinkedHashMap<>();
        if (anns == null || !anns.isArray()) return Map.of();
        for (JsonNode a : anns)
            perTag.computeIfAbsent(a.path("AdvertisedTrainIdent").asString(""),
                    k -> new ObjectMapper().createArrayNode()).add(a);
        Map<String, Integer> ut = new java.util.LinkedHashMap<>();
        perTag.forEach((tag, rader) -> {
            List<Stopp> alla = byggStopp(rader, index);
            List<Stopp> del = delstracka(alla, fran, till);
            // Hittades inte både start och mål är talet inte resenärens — hellre inget tal än fel tal.
            if (del.size() >= 2 && sammaStation(del.get(0).namn(), fran)
                    && sammaStation(del.get(del.size() - 1).namn(), till))
                ut.put(tag, del.size() - 2);
        });
        return ut;
    }

    /**
     * Slår ihop Trafikverkets rader (en per aktivitet och plats) till ett stopp per station i
     * tidsordning. Ankomst och avgång på samma plats blir ETT stopp med båda tiderna. En
     * signatur som inte finns i stationsindexet hoppas över — den har inga koordinater att rita.
     */
    static List<Stopp> byggStopp(JsonNode anns, Map<String, TrainStation> index) {
        java.util.LinkedHashMap<String, String[]> perPlats = new java.util.LinkedHashMap<>();
        if (anns == null || !anns.isArray()) return List.of();
        for (JsonNode a : anns) {
            String sig = a.path("LocationSignature").asString("").toUpperCase();
            if (sig.isBlank() || !index.containsKey(sig)) continue;
            String tid = parseTid(a.path("AdvertisedTimeAtLocation").asString("")) == null ? ""
                    : a.path("AdvertisedTimeAtLocation").asString("").substring(11, 16);
            String[] t = perPlats.computeIfAbsent(sig, k -> new String[]{"", ""});
            if ("Ankomst".equalsIgnoreCase(a.path("ActivityType").asString())) t[0] = tid; else t[1] = tid;
        }
        List<Stopp> ut = new ArrayList<>();
        perPlats.forEach((sig, t) -> {
            TrainStation s = index.get(sig);
            ut.add(new Stopp(s.getName(), sig, s.getLat(), s.getLon(), t[0], t[1]));
        });
        return ut;
    }

    /**
     * Klipper ut resenärens del: från första stoppet som heter som påstigningen till det
     * första därefter som heter som målet. Hittas inte båda returneras hela listan — hellre
     * tågets hela väg än en tom karta.
     */
    static List<Stopp> delstracka(List<Stopp> alla, String fran, String till) {
        int a = -1, b = -1;
        for (int i = 0; i < alla.size(); i++) {
            if (a < 0 && sammaStation(alla.get(i).namn(), fran)) a = i;
            else if (a >= 0 && sammaStation(alla.get(i).namn(), till)) { b = i; break; }
        }
        return a >= 0 && b > a ? alla.subList(a, b + 1) : alla;
    }

    private static boolean sammaStation(String namn, String sokt) {
        if (namn == null || sokt == null || sokt.isBlank()) return false;
        String n = namn.toLowerCase(), s = sokt.toLowerCase().trim();
        return n.equals(s) || n.startsWith(s + " ") || s.startsWith(n + " ");
    }

    // ── Trafikläget i hela landet (uppstartsskärmens live-rader) ──────────────
    //
    // En enda fråga: alla avgångar i Sverige från 30 min bakåt till 60 min framåt. Ur den
    // räknas tåg i trafik, kommande avgångar, bolag, punktlighet och inställda. Svaret är
    // stort (tusentals rader, pendeltågen stannar ofta), därför cachas det en minut — splashen
    // visas för varje ny besökare och ska inte bli en fråga mot Trafikverket per sidladdning.

    /** Siffrorna till splashen. Punktlighet är andelen avgångna inom 5 min (Trafikverkets mått). */
    public record TrafikLage(int tagITrafik, int avgangarNastaTimme, int bolag,
                             int avgangnaSenaste, int iTid, int installda) {
        public Integer punktlighetProcent() {
            return avgangnaSenaste == 0 ? null : (int) Math.round(100.0 * iTid / avgangnaSenaste);
        }
    }

    private static final long TRAFIKLAGE_TTL_MS = 60_000L;
    private volatile TrafikLage trafikLage;
    private volatile long trafikLageTid;

    /** Trafikläget just nu, cachat en minut. Null när Trafikverket inte svarar. */
    public synchronized TrafikLage getTrafikLage() {
        long nu = System.currentTimeMillis();
        if (trafikLage != null && nu - trafikLageTid < TRAFIKLAGE_TTL_MS) return trafikLage;

        ZonedDateTime tid = ZonedDateTime.now(ZoneId.of("Europe/Stockholm"));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="20000">
                <FILTER>
                  <AND>
                    <EQ name="ActivityType" value="Avgang"/>
                    <EQ name="Advertised" value="true"/>
                    <GT name="AdvertisedTimeAtLocation" value="%s"/>
                    <LT name="AdvertisedTimeAtLocation" value="%s"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>TimeAtLocation</INCLUDE>
                <INCLUDE>TrainOwner</INCLUDE>
                <INCLUDE>Canceled</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, tid.minusMinutes(30).format(f), tid.plusMinutes(60).format(f));
        try {
            JsonNode anns = callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement");
            if (!anns.isArray()) return trafikLage;
            trafikLage = summeraTrafik(anns, tid);
            trafikLageTid = nu;
        } catch (Exception e) {
            // Gammalt läge (eller null) hellre än ett fel — splashen visar raden utan siffror.
        }
        return trafikLage;
    }

    static TrafikLage summeraTrafik(JsonNode anns, ZonedDateTime nu) {
        java.util.Set<String> tag = new java.util.HashSet<>();
        java.util.Set<String> bolag = new java.util.HashSet<>();
        int kommande = 0, avgangna = 0, iTid = 0, installda = 0;
        for (JsonNode a : anns) {
            ZonedDateTime plan = parseTid(a.path("AdvertisedTimeAtLocation").asString(""));
            if (plan == null) continue;
            if (a.path("Canceled").asBoolean(false)) { installda++; continue; }
            String id = a.path("AdvertisedTrainIdent").asString("");
            String agare = a.path("TrainOwner").asString("");
            // "I trafik" = har en avgång inom en halvtimme åt något håll från nu.
            if (!id.isBlank() && Math.abs(java.time.Duration.between(nu, plan).toMinutes()) <= 30) {
                tag.add(id);
                if (!agare.isBlank()) bolag.add(agare);
            }
            if (!plan.isBefore(nu)) kommande++;
            ZonedDateTime faktisk = parseTid(a.path("TimeAtLocation").asString(""));
            if (faktisk != null) {
                avgangna++;
                if (java.time.Duration.between(plan, faktisk).toSeconds() <= 5 * 60 + 59) iTid++;
            }
        }
        return new TrafikLage(tag.size(), kommande, bolag.size(), avgangna, iTid, installda);
    }

    private static ZonedDateTime parseTid(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return ZonedDateTime.parse(iso); } catch (Exception e) { return null; }
    }

    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        return haversineStatic(lat1, lon1, lat2, lon2);
    }

    static double haversineStatic(double lat1, double lon1, double lat2, double lon2) {
        double R    = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
