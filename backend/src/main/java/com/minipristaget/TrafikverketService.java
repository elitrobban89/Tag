package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TrafikverketService {

    private static final String API_URL = "https://api.trafikinfo.trafikverket.se/v2/data.json";
    private static final Pattern WGS84_PATTERN = Pattern.compile("POINT \\(([\\d.]+) ([\\d.]+)\\)");

    @Value("${trafikverket.api.key:}")
    private String apiKey;

    private final HttpClient    httpClient = HttpClient.newHttpClient();
    private final ObjectMapper  mapper     = new ObjectMapper();

    private List<TrainStation> stationCache = null;

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

        JsonNode result  = callApi(xml);
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
        String xml = """
            <REQUEST>
              <LOGIN authenticationkey="%s"/>
              <QUERY objecttype="TrainAnnouncement" schemaversion="1.8" limit="30">
                <FILTER>
                  <AND>
                    <EQ name="LocationSignature" value="%s"/>
                    <EQ name="ActivityType" value="Avgang"/>
                    <GT name="AdvertisedTimeAtLocation" value="%sT00:00:00"/>
                    <LT name="AdvertisedTimeAtLocation" value="%sT23:59:59"/>
                  </AND>
                </FILTER>
                <INCLUDE>AdvertisedTrainIdent</INCLUDE>
                <INCLUDE>AdvertisedTimeAtLocation</INCLUDE>
                <INCLUDE>EstimatedTimeAtLocation</INCLUDE>
                <INCLUDE>ToLocation</INCLUDE>
                <INCLUDE>TrainOwner</INCLUDE>
                <INCLUDE>Canceled</INCLUDE>
              </QUERY>
            </REQUEST>
            """.formatted(apiKey, fromSignature, date, date);

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
        return departures;
    }

    // ── Privata hjälpmetoder ──────────────────────────────────────
    private boolean matchesDestination(JsonNode ann, String toName) {
        JsonNode locs = ann.path("ToLocation");
        if (!locs.isArray()) return false;
        String prefix = toName.toLowerCase().substring(0, Math.min(3, toName.length()));
        for (JsonNode loc : locs) {
            if (loc.path("LocationName").asText().toLowerCase().startsWith(prefix)) return true;
        }
        // Also check against full station cache name
        try {
            if (stationCache != null) {
                for (JsonNode loc : locs) {
                    String sig = loc.path("LocationName").asText();
                    boolean match = stationCache.stream()
                        .anyMatch(s -> s.getSignature().equalsIgnoreCase(sig) &&
                                       s.getName().toLowerCase().contains(toName.toLowerCase()));
                    if (match) return true;
                }
            }
        } catch (Exception ignored) {}
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

        JsonNode locs = ann.path("ToLocation");
        if (locs.isArray() && locs.size() > 0) {
            String lastSig = locs.get(locs.size() - 1).path("LocationName").asText();
            // Try to resolve signature → friendly name
            String friendlyName = lastSig;
            if (stationCache != null) {
                friendlyName = stationCache.stream()
                    .filter(s -> s.getSignature().equalsIgnoreCase(lastSig))
                    .map(TrainStation::getName)
                    .findFirst()
                    .orElse(lastSig);
            }
            dep.setDestination(friendlyName);
        }
        return dep;
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
        double R  = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a  = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                  + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                  * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
