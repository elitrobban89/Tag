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
        String lower = name.toLowerCase();
        return getAllStations().stream()
            .filter(s -> s.getName().toLowerCase().contains(lower))
            .findFirst();
    }

    // ── Hämta avgångar ────────────────────────────────────────────
    public List<TrainDeparture> getDepartures(String fromSignature, String toName, LocalDate date) throws Exception {
        String cacheKey = fromSignature + "|" + (toName != null ? toName.toLowerCase() : "") + "|" + date;
        DepartureCacheEntry hit = departureCache.get(cacheKey);
        if (hit != null && System.currentTimeMillis() - hit.timestamp() < DEPARTURE_CACHE_TTL_MS)
            return hit.departures();

        int limit = (toName == null || toName.isBlank()) ? 50 : 200;
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

        List<TrainDeparture> departures = new ArrayList<>();
        if (announcements.isArray()) {
            for (JsonNode ann : announcements) {
                if (toName == null || toName.isBlank() || matchesDestination(ann, toName)) {
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
                final String destSig = dep.getDestinationSignature();
                TrainStation toSt = stationIndex.get(destSig.toUpperCase());

                if (toSt != null) {
                    double dist = haversine(fromSt.getLat(), fromSt.getLon(),
                                            toSt.getLat(),   toSt.getLon());
                    dep.setPrice(trainModelService.calculatePrice(dist, dep.getTrainId()));
                    dep.setPriceLugn(trainModelService.calculatePriceLugn(dist, dep.getTrainId()));
                    dep.setPrice1klass(trainModelService.calculatePrice1Klass(dist, dep.getTrainId()));
                    dep.setPriceOriginal(trainModelService.calculateOrdinaryPrice(dist, dep.getTrainId()));
                    dep.setSeatsLeft(trainModelService.calculateSeatsLeft(dep.getTrainId()));
                    dep.setHasSeatMap(model.hasSeatMap());
                    dep.setSeatLayout(model.seatLayout());
                    dep.setTravelMinutes(trainModelService.estimateTravelMinutes(dist, model.avgSpeedKmh()));
                    // CO2 savings: car ~110 g/km vs Swedish train ~6 g/km
                    double co2 = Math.round((110.0 - 6.0) * dist / 1000.0 * 10.0) / 10.0;
                    dep.setCo2SavedKg(co2);
                }
            }
        }

        departureCache.put(cacheKey, new DepartureCacheEntry(departures, System.currentTimeMillis()));
        return departures;
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
