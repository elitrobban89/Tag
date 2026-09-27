package com.minipristaget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester för avgångskvaliteten: restid ur riktig ankomst, ihopparning på tågnummer och
 * stationsuppslaget. Tidsformatet är Trafikverkets eget (ISO med offset).
 *
 * @author Robert Andersson Kopler
 */
class TrafikverketServiceTest {

    // --- travelMinutes ---

    @Test
    void restidenRaknasUrRiktigAnkomst() {
        // SJ Regional 163 Stockholm C 07:14 → Göteborg C: ingen snabbtågsgissning
        assertThat(TrafikverketService.travelMinutes(
            "2026-09-28T07:14:00.000+02:00", "2026-09-28T11:52:00.000+02:00")).isEqualTo(278);
    }

    @Test
    void ankomstEfterMidnattGerRattRestid() {
        assertThat(TrafikverketService.travelMinutes(
            "2026-09-28T22:30:00.000+02:00", "2026-09-29T01:10:00.000+02:00")).isEqualTo(160);
    }

    @Test
    void ankomstForeAvgangenBetyderAttTagetInteGarDit() {
        // Samma tågnummer var vid målet INNAN det gick härifrån — fel riktning
        assertThat(TrafikverketService.travelMinutes(
            "2026-09-28T09:00:00.000+02:00", "2026-09-28T08:00:00.000+02:00")).isNull();
    }

    @Test
    void saknadAnkomstBetyderAttTagetInteStannarVidMalet() {
        assertThat(TrafikverketService.travelMinutes("2026-09-28T09:00:00.000+02:00", null)).isNull();
        assertThat(TrafikverketService.travelMinutes("2026-09-28T09:00:00.000+02:00", "")).isNull();
    }

    // --- arrivalsByTrain ---

    @Test
    void allaAnkomsterPerTagnummerSparas() throws Exception {
        JsonNode json = new ObjectMapper().readTree("""
            [ {"AdvertisedTrainIdent":"2027","AdvertisedTimeAtLocation":"2026-09-28T12:55:00.000+02:00"},
              {"AdvertisedTrainIdent":"427", "AdvertisedTimeAtLocation":"2026-09-28T12:20:00.000+02:00"},
              {"AdvertisedTrainIdent":"2027","AdvertisedTimeAtLocation":"2026-09-29T02:00:00.000+02:00"},
              {"AdvertisedTrainIdent":"",    "AdvertisedTimeAtLocation":"2026-09-28T13:00:00.000+02:00"} ]
            """);
        Map<String, List<String>> map = TrafikverketService.arrivalsByTrain(json);
        assertThat(map).hasSize(2);
        assertThat(map.get("2027")).hasSize(2);
    }

    @Test
    void gardagensTurForeAvgangenKastarInteTaget() {
        // Uppmätt 2026-09-28, tåg 451 Stockholm C 21:22: framme i Göteborg C 01:47 natten FÖRE
        // (gårdagens tur) och 00:47 natten efter. Bara den senare hör till avgången.
        List<String> ankomster = List.of("2026-09-28T01:47:00.000+02:00", "2026-09-29T00:47:00.000+02:00");
        assertThat(TrafikverketService.firstTravelMinutes("2026-09-28T21:22:00.000+02:00", ankomster))
            .isEqualTo(205);
    }

    @Test
    void ingenAnkomstEfterAvgangenGerNull() {
        assertThat(TrafikverketService.firstTravelMinutes("2026-09-28T21:22:00.000+02:00",
            List.of("2026-09-28T01:47:00.000+02:00"))).isNull();
        assertThat(TrafikverketService.firstTravelMinutes("2026-09-28T21:22:00.000+02:00", null)).isNull();
    }

    @Test
    void tomtSvarGerTomKarta() {
        assertThat(TrafikverketService.arrivalsByTrain(null)).isEmpty();
    }

    // --- bestMatch ---

    private static final List<TrainStation> STATIONER = List.of(
        new TrainStation("Or",  "Göteborg Olskroken", 57.7, 12.0),
        new TrainStation("Sän", "Göteborg Sävenäs",   57.7, 12.0),
        new TrainStation("G",   "Göteborg C",         57.7, 11.97),
        new TrainStation("Cst", "Stockholm C",        59.33, 18.06));

    @Test
    void stadensNamnGerCentralstationen() {
        assertThat(TrafikverketService.bestMatch(STATIONER, "Göteborg").map(TrainStation::getSignature))
            .contains("G");
    }

    @Test
    void exaktNamnVinnerOavsettOrdning() {
        assertThat(TrafikverketService.bestMatch(STATIONER, "göteborg sävenäs").map(TrainStation::getSignature))
            .contains("Sän");
    }

    @Test
    void delAvNamnFallerTillbakaPaInnehaller() {
        assertThat(TrafikverketService.bestMatch(STATIONER, "stockh").map(TrainStation::getSignature))
            .contains("Cst");
    }
}
