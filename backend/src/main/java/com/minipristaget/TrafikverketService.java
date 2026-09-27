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
        Map<String, List<String>> ankomster = toSt != null ? getArrivals(toSt.getSignature(), date) : Map.of();

        List<TrainDeparture> departures = new ArrayList<>();
        Map<TrainDeparture, Integer> riktigRestid = new HashMap<>();
        if (announcements.isArray()) {
            for (JsonNode ann : announcements) {
                if (!harMal) { departures.add(parseAnnouncement(ann)); continue; }
                if (!ankomster.isEmpty()) {
                    // Ankomsten är känd: tåget ska stanna vid målet EFTER avgången här.
                    Integer min = firstTravelMinutes(ann.path("AdvertisedTimeAtLocation").asText(),
                                                     ankomster.get(ann.path("AdvertisedTrainIdent").asText()));
                    if (min == null) continue;
                    TrainDeparture dep = parseAnnouncement(ann);
                    departures.add(dep);
                    riktigRestid.put(dep, min);
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

        departureCache.put(cacheKey, new DepartureCacheEntry(departures, System.currentTimeMillis()));
        return departures;
    }

    // ── Ankomster vid en station: tågnummer → annonserad ankomsttid (ISO) ──
    // Fönstret går till 06:00 nästa dygn så att ett kvällståg som är framme efter midnatt
    // inte faller bort. Misslyckas anropet blir kartan tom, och sökningen faller tillbaka på
    // slutstationsmatchningen — en tom lista av ett nätverksfel vore värre än en gissad restid.
    private Map<String, List<String>> getArrivals(String toSignature, LocalDate date) {
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
     * Restid till den FÖRSTA ankomsten efter avgången. Ett tågnummer kan ha två ankomster i
     * fönstret: tåg 451 (Stockholm 21:22) var framme i Göteborg 01:47 natten FÖRE — gårdagens
     * tur — och 00:47 natten efter. Att ta den första i listan kastade tåget helt.
     */
    static Integer firstTravelMinutes(String departureIso, List<String> arrivals) {
        if (arrivals == null) return null;
        Integer best = null;
        for (String arr : arrivals) {
            Integer min = travelMinutes(departureIso, arr);
            if (min != null && (best == null || min < best)) best = min;
        }
        return best;
    }

    /** Alla ankomster per tågnummer, i tidsordning. */
    static Map<String, List<String>> arrivalsByTrain(JsonNode announcements) {
        Map<String, List<String>> map = new HashMap<>();
        if (announcements != null && announcements.isArray()) {
            for (JsonNode a : announcements) {
                String id = a.path("AdvertisedTrainIdent").asText("");
                String t  = a.path("AdvertisedTimeAtLocation").asText("");
                if (!id.isBlank() && !t.isBlank()) map.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
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
        double R    = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a    = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
