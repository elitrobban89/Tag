package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                String sig  = s.path("LocationSignature").asText();
                String name = s.path("AdvertisedLocationName").asText();
                String wgs  = s.path("Geometry").path("WGS84").asText();
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
    static Optional<TrainStation> bestMatch(List<TrainStation> stations, String name) {
        String lower = name.trim().toLowerCase();
        Optional<TrainStation> exakt = stations.stream()
            .filter(s -> s.getName().equalsIgnoreCase(lower)).findFirst();
        if (exakt.isPresent()) return exakt;
        Optional<TrainStation> central = stations.stream()
            .filter(s -> s.getName().equalsIgnoreCase(lower + " c")).findFirst();
        if (central.isPresent()) return central;
        return stations.stream()
            .filter(s -> s.getName().toLowerCase().contains(lower))
            .findFirst();
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
                <INCLUDE>TrainOwner</INCLUDE>
                <INCLUDE>Canceled</INCLUDE>
                <!-- Produktnamnet ("SJ Snabbtåg", "SJ Regional"…). Trafikverket anger ALDRIG
                     fordonstyp, så X2000/SJ 3000 går inte att läsa ut — men produktnamnet
                     skiljer i alla fall snabbtåg från regionaltåg. -->
                <INCLUDE>ProductInformation</INCLUDE>
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
        Map<String, List<Ankomst>> ankomster = toSt != null ? getArrivals(toSt.getSignature(), date) : Map.of();

        List<TrainDeparture> departures = new ArrayList<>();
        Map<TrainDeparture, Integer> riktigRestid = new HashMap<>();
        if (announcements.isArray()) {
            for (JsonNode ann : announcements) {
                if (!harMal) { departures.add(parseAnnouncement(ann)); continue; }
                if (!ankomster.isEmpty()) {
                    // Ankomsten är känd: tåget ska stanna vid målet EFTER avgången här.
                    String depIso = ann.path("AdvertisedTimeAtLocation").asText();
                    Ankomst a = firstArrivalAfter(depIso, ankomster.get(ann.path("AdvertisedTrainIdent").asText()));
                    if (a == null) continue;
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

            if (fromSt != null && dep.getDestinationSignature() != null && stationIndex != null) {
                // Pris och CO2 räknas till stationen man SÖKT, inte till tågets slutstation —
                // ett tåg som fortsätter förbi målet ska inte bli dyrare för det.
                TrainStation prisMal = toSt != null && riktigRestid.containsKey(dep)
                    ? toSt : stationIndex.get(dep.getDestinationSignature().toUpperCase());

                if (prisMal != null) {
                    double dist = haversine(fromSt.getLat(), fromSt.getLon(),
                                            prisMal.getLat(), prisMal.getLon());
                    dep.setPrice(trainModelService.calculatePrice(dist, dep.getTrainId()));
                    dep.setPriceLugn(trainModelService.calculatePriceLugn(dist, dep.getTrainId()));
                    dep.setPrice1klass(trainModelService.calculatePrice1Klass(dist, dep.getTrainId()));
                    dep.setPriceOriginal(trainModelService.calculateOrdinaryPrice(dist, dep.getTrainId()));
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
                                                ankomster, direkta));
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
        "Mjölby", "Nässjö C", "Alvesta", "Hässleholm C", "Lund C", "Södertälje Syd", "Örebro C",
        "Katrineholm C", "Skövde C", "Falköping C", "Herrljunga", "Jönköping C", "Växjö",
        "Kristianstad C", "Uppsala C", "Gävle C", "Sundsvall C", "Borlänge C", "Västerås C",
        "Karlstad C", "Halmstad C", "Kalmar C", "Karlskrona C");

    static final int MIN_BYTESTID = 6;
    static final int MAX_VANTETID = 120;

    /** Ett tåg mellan två stationer: avgång och ankomst som ISO-tider. */
    record Leg(String trainId, String depIso, String arrIso, JsonNode ann, Ankomst ankomst) {}

    /** En resa med byte: första och andra tåget. */
    record Connection(Leg first, Leg second) {}

    private List<TrainDeparture> findTransferTrips(TrainStation fromSt, TrainStation toSt, LocalDate date,
                                                   String fromTime, JsonNode fromAnnouncements,
                                                   Map<String, List<Ankomst>> ankomsterVidMal,
                                                   java.util.Set<String> direkta) {
        List<TrainStation> kandidater = new ArrayList<>();
        for (String namn : BYTESSTATIONER) {
            if (stationCache != null) bestMatch(stationCache, namn).ifPresent(kandidater::add);
        }
        List<TrainStation> hubs = chooseHubs(fromSt, toSt, kandidater, 4);

        // Bytesstationerna hämtas parallellt — sekventiellt blev sökningen märkbart seg
        List<java.util.concurrent.CompletableFuture<List<Connection>>> jobb = new ArrayList<>();
        for (TrainStation hub : hubs) {
            jobb.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                Map<String, List<Ankomst>> vidByte = getArrivals(hub.getSignature(), date);
                JsonNode franByte = getDepartureAnnouncements(hub.getSignature(), date, fromTime);
                return connect(legsTo(fromAnnouncements, vidByte), legsTo(franByte, ankomsterVidMal),
                               direkta);
            }));
        }
        List<Connection> alla = new ArrayList<>();
        List<TrainStation> hubForConn = new ArrayList<>();
        for (int i = 0; i < jobb.size(); i++) {
            try {
                for (Connection c : jobb.get(i).get(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    alla.add(c); hubForConn.add(hubs.get(i));
                }
            } catch (Exception e) { /* en bytesstation som inte svarar hoppas över */ }
        }

        List<Connection> basta = pareto(alla);
        List<TrainDeparture> ut = new ArrayList<>();
        double dist = haversine(fromSt.getLat(), fromSt.getLon(), toSt.getLat(), toSt.getLon());
        for (Connection c : basta) {
            TrainStation hub = hubForConn.get(alla.indexOf(c));
            TrainDeparture dep = parseAnnouncement(c.first().ann());
            TrainDeparture andra = parseAnnouncement(c.second().ann());
            TrainModelService.TrainModelInfo m1 = trainModelService.resolveModel(dep, fromSt.getName());
            TrainModelService.TrainModelInfo m2 = trainModelService.resolveModel(andra, hub.getName());
            String kombinerat = c.first().trainId() + "+" + c.second().trainId();

            dep.setDestination(toSt.getName());
            dep.setDestinationSignature(toSt.getSignature());
            dep.setTransfers(1);
            dep.setTransferStation(hub.getName());
            dep.setTransferArrival(formatTime(c.first().arrIso()));
            dep.setTransferDeparture(formatTime(c.second().depIso()));
            dep.setSecondTrainId(c.second().trainId());
            dep.setTrainModel(m1.name().equals(m2.name()) ? m1.name() : m1.name() + " + " + m2.name());
            dep.setTrainColor(m1.color());
            dep.setTrainImage(m1.imageUrl());
            dep.setHasSeatMap(false);      // platskartan gäller ett tåg — med byte väljs plats ombord
            dep.setSeatLayout("none");
            dep.setTravelMinutes(travelMinutes(c.first().depIso(), c.second().arrIso()));
            setEstimatedArrival(dep, c.second().ankomst());
            dep.setPrice(trainModelService.calculatePrice(dist, kombinerat));
            dep.setPriceLugn(trainModelService.calculatePriceLugn(dist, kombinerat));
            dep.setPrice1klass(trainModelService.calculatePrice1Klass(dist, kombinerat));
            dep.setPriceOriginal(trainModelService.calculateOrdinaryPrice(dist, kombinerat));
            dep.setSeatsLeft(trainModelService.calculateSeatsLeft(kombinerat));
            dep.setCo2SavedKg(Math.round((110.0 - 6.0) * dist / 1000.0 * 10.0) / 10.0);
            ut.add(dep);
        }
        return ut;
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
            String id  = ann.path("AdvertisedTrainIdent").asText("");
            String dep = ann.path("AdvertisedTimeAtLocation").asText("");
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
            Leg bast = null;
            for (Leg l2 : andra) {
                if (l2.trainId().equals(l1.trainId())) continue;
                Integer byte_ = travelMinutes(l1.arrIso(), l2.depIso());
                if (byte_ == null || byte_ < MIN_BYTESTID || byte_ > MAX_VANTETID) continue;
                if (bast == null || travelMinutes(l2.arrIso(), bast.arrIso()) != null) bast = l2;
            }
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
                long arrC = java.time.OffsetDateTime.parse(c.second().arrIso()).toEpochSecond();
                long arrO = java.time.OffsetDateTime.parse(o.second().arrIso()).toEpochSecond();
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
    // Fönstret går till 06:00 nästa dygn så att ett kvällståg som är framme efter midnatt
    // inte faller bort. Misslyckas anropet blir kartan tom, och sökningen faller tillbaka på
    // slutstationsmatchningen — en tom lista av ett nätverksfel vore värre än en gissad restid.
    private Map<String, List<Ankomst>> getArrivals(String toSignature, LocalDate date) {
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" namespace="rail.trafficinfo" schemaversion="2.0" limit="1000" orderby="AdvertisedTimeAtLocation">
                <FILTER>
                  <AND>
                    <EQ name="LocationSignature" value="%s"/>
                    <EQ name="ActivityType" value="Ankomst"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT00:00:00"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT06:00:00"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>EstimatedTimeAtLocation</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, toSignature, date, date.plusDays(1));
        try {
            return arrivalsByTrain(callApi(xml).path("RESPONSE").path("RESULT").get(0).path("TrainAnnouncement"));
        } catch (Exception e) {
            return Map.of();
        }
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
                String id  = a.path("AdvertisedTrainIdent").asText("");
                String t   = a.path("AdvertisedTimeAtLocation").asText("");
                String est = a.path("EstimatedTimeAtLocation").asText("");
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

    // ── Privata hjälpmetoder ──────────────────────────────────────
    private boolean matchesDestination(JsonNode ann, String toName) {
        JsonNode locs = ann.path("ToLocation");
        if (!locs.isArray() || locs.isEmpty()) return false;
        String lower = toName.toLowerCase();

        for (JsonNode loc : locs) {
            String sig = loc.path("LocationName").asText();

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
        dep.setTrainId(ann.path("AdvertisedTrainIdent").asText());

        String adv = ann.path("AdvertisedTimeAtLocation").asText();
        String est = ann.path("EstimatedTimeAtLocation").asText();
        dep.setDepartureTime(formatTime(adv));
        if (!est.isBlank() && !est.equals(adv)) dep.setEstimatedTime(formatTime(est));

        dep.setOperator(ann.path("TrainOwner").asText());
        dep.setCanceled(ann.path("Canceled").asBoolean(false));
        dep.setProductInformation(readProductInformation(ann));

        JsonNode locs = ann.path("ToLocation");
        if (locs.isArray() && locs.size() > 0) {
            String lastSig = locs.get(locs.size() - 1).path("LocationName").asText();
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
    private String readProductInformation(JsonNode ann) {
        JsonNode pi = ann.path("ProductInformation");
        if (pi.isMissingNode() || pi.isNull()) return "";
        if (pi.isTextual()) return pi.asText();
        if (pi.isArray() && pi.size() > 0) {
            JsonNode first = pi.get(0);
            if (first.isTextual()) return first.asText();
            String desc = first.path("Description").asText("");
            return desc.isBlank() ? first.path("Code").asText("") : desc;
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
