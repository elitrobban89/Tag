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
            [ {"AdvertisedTrainIdent":"2027","AdvertisedTimeAtLocation":"2026-09-28T12:55:00.000+02:00",
               "EstimatedTimeAtLocation":"2026-09-28T13:05:00.000+02:00"},
              {"AdvertisedTrainIdent":"427", "AdvertisedTimeAtLocation":"2026-09-28T12:20:00.000+02:00"},
              {"AdvertisedTrainIdent":"2027","AdvertisedTimeAtLocation":"2026-09-29T02:00:00.000+02:00"},
              {"AdvertisedTrainIdent":"",    "AdvertisedTimeAtLocation":"2026-09-28T13:00:00.000+02:00"} ]
            """);
        Map<String, List<TrafikverketService.Ankomst>> map = TrafikverketService.arrivalsByTrain(json);
        assertThat(map).hasSize(2);
        assertThat(map.get("2027")).hasSize(2);
        // Den beräknade tiden följer med — den visar förseningen vid målet
        assertThat(map.get("2027").get(0).beraknad()).isEqualTo("2026-09-28T13:05:00.000+02:00");
    }

    private static TrafikverketService.Ankomst ank(String planerad) {
        return new TrafikverketService.Ankomst(planerad, "");
    }

    @Test
    void gardagensTurForeAvgangenKastarInteTaget() {
        // Uppmätt 2026-09-28, tåg 451 Stockholm C 21:22: framme i Göteborg C 01:47 natten FÖRE
        // (gårdagens tur) och 00:47 natten efter. Bara den senare hör till avgången.
        List<TrafikverketService.Ankomst> ankomster =
            List.of(ank("2026-09-28T01:47:00.000+02:00"), ank("2026-09-29T00:47:00.000+02:00"));
        assertThat(TrafikverketService.firstArrivalAfter("2026-09-28T21:22:00.000+02:00", ankomster).planerad())
            .isEqualTo("2026-09-29T00:47:00.000+02:00");
    }

    @Test
    void ingenAnkomstEfterAvgangenGerNull() {
        assertThat(TrafikverketService.firstArrivalAfter("2026-09-28T21:22:00.000+02:00",
            List.of(ank("2026-09-28T01:47:00.000+02:00")))).isNull();
        assertThat(TrafikverketService.firstArrivalAfter("2026-09-28T21:22:00.000+02:00", null)).isNull();
    }

    // --- byten ---

    private static TrafikverketService.Leg leg(String id, String dep, String arr) {
        return new TrafikverketService.Leg(id, "2026-09-28T" + dep + ":00.000+02:00",
            "2026-09-28T" + arr + ":00.000+02:00", null, ank("2026-09-28T" + arr + ":00.000+02:00"));
    }

    @Test
    void bytetValjerTagetSomArFrammeForst() {
        var forsta = List.of(leg("10", "08:00", "10:00"));
        var andra = List.of(
            leg("20", "10:03", "11:00"),   // för kort bytestid (3 min)
            leg("21", "10:15", "12:30"),
            leg("22", "10:40", "12:00"),   // senare avgång men FRAMME först
            leg("23", "13:00", "14:00"));  // för lång väntan (180 min)
        var c = TrafikverketService.connect(forsta, andra, java.util.Set.of());
        assertThat(c).hasSize(1);
        assertThat(c.get(0).second().trainId()).isEqualTo("22");
    }

    @Test
    void direkttagOchSammaTagnummerBlirInteByte() {
        var forsta = List.of(leg("10", "08:00", "10:00"), leg("11", "08:30", "10:20"));
        var andra = List.of(leg("11", "10:30", "12:00"), leg("30", "10:40", "12:10"));
        // 10 går redan direkt till målet; 11 får inte "byta" till sig själv
        var c = TrafikverketService.connect(forsta, andra, java.util.Set.of("10"));
        assertThat(c).hasSize(1);
        assertThat(c.get(0).first().trainId()).isEqualTo("11");
        assertThat(c.get(0).second().trainId()).isEqualTo("30");
    }

    @Test
    void paretoTarBortResorSomSlasAvEnSenareAvgang() {
        var a = new TrafikverketService.Connection(leg("1", "07:00", "09:00"), leg("9", "09:30", "12:00"));
        var b = new TrafikverketService.Connection(leg("2", "08:00", "09:20"), leg("9", "09:30", "12:00"));
        var c = new TrafikverketService.Connection(leg("3", "08:30", "10:00"), leg("8", "10:10", "13:00"));
        var kvar = TrafikverketService.pareto(List.of(a, b, c));
        // a avgår tidigare än b men är framme samtidigt → bort. c är framme senare men avgår senare → kvar.
        assertThat(kvar).containsExactly(b, c);
    }

    @Test
    void bytesstationMaste_liggaPaVagen() {
        TrainStation sthlm  = new TrainStation("Cst", "Stockholm C", 59.33, 18.06);
        TrainStation kalmar = new TrainStation("Kac", "Kalmar C",    56.66, 16.36);
        TrainStation nassjo = new TrainStation("N",   "Nässjö C",    57.65, 14.69);
        TrainStation linkop = new TrainStation("Lp",  "Linköping C", 58.42, 15.62);
        TrainStation umea   = new TrainStation("Umc", "Umeå C",      63.83, 20.26);
        var hubs = TrafikverketService.chooseHubs(sthlm, kalmar, List.of(nassjo, linkop, umea, sthlm), 3);
        assertThat(hubs).extracting(TrainStation::getSignature).containsExactly("Lp", "N");
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

    @Test
    void tvaBytenForlangerResanMedTagetSomArFrammeForst() {
        // Göteborg → Stockholm → Sundsvall → Umeå: tre tåg, två byten
        var forstaTva = List.of(new TrafikverketService.Connection(
            leg("420", "05:11", "08:20"), leg("570", "08:40", "12:10")));
        var sista = List.of(
            leg("570", "12:30", "15:00"),   // samma tåg som redan är med — inget byte
            leg("7442", "12:20", "15:40"),
            leg("7444", "12:50", "15:10"),  // senare avgång, men framme först
            leg("7446", "16:00", "18:00")); // över två timmars väntan
        var resor = TrafikverketService.extend(forstaTva, sista);
        assertThat(resor).hasSize(1);
        assertThat(resor.get(0).legs()).extracting(TrafikverketService.Leg::trainId)
            .containsExactly("420", "570", "7444");
        assertThat(resor.get(0).last().trainId()).isEqualTo("7444");
    }

    @Test
    void resaUtanAnslutningTasBort() {
        var forstaTva = List.of(new TrafikverketService.Connection(
            leg("1", "05:00", "07:00"), leg("2", "07:10", "09:00")));
        assertThat(TrafikverketService.extend(forstaTva, List.of(leg("3", "08:00", "10:00")))).isEmpty();
    }

    // --- alias och stationssuffix ---

    private static final List<TrainStation> MED_DANMARK = List.of(
        new TrainStation("Dk.kh", "Köpenhamn H", 55.67, 12.56),
        new TrainStation("Dk.kas", "Copenhagen Airport", 55.63, 12.65),
        new TrainStation("Köp", "Köping", 59.51, 15.99),
        new TrainStation("Thn", "Trollhättan", 58.28, 12.29));

    @Test
    void kastrupOchKobenhavnHittarRattStation() {
        assertThat(TrafikverketService.bestMatch(MED_DANMARK, "Kastrup").map(TrainStation::getName))
            .contains("Copenhagen Airport");
        assertThat(TrafikverketService.bestMatch(MED_DANMARK, "København").map(TrainStation::getName))
            .contains("Köpenhamn H");
        assertThat(TrafikverketService.bestMatch(MED_DANMARK, "Copenhagen").map(TrainStation::getName))
            .contains("Köpenhamn H");
    }

    @Test
    void stationssuffixForMycketProvasUtan() {
        // Trafikverket heter "Trollhättan" — "Trollhättan C" gav "Hittade ingen station"
        assertThat(TrafikverketService.bestMatch(MED_DANMARK, "Trollhättan C").map(TrainStation::getName))
            .contains("Trollhättan");
    }

    @Test
    void aliasForslagKraverTreTecken() {
        assertThat(TrafikverketService.aliasSuggestions("Ka")).isEmpty();
        assertThat(TrafikverketService.aliasSuggestions("Kas")).containsExactly("Copenhagen Airport");
    }

    // --- tågets stopp (chattens resekarta) ---

    private static final Map<String, TrainStation> INDEX = Map.of(
        "CST", new TrainStation("Cst", "Stockholm C", 59.33, 18.06),
        "VÅ",  new TrainStation("Vå", "Västerås C", 59.61, 16.55),
        "ÖR",  new TrainStation("Ör", "Örebro C", 59.27, 15.21),
        "G",   new TrainStation("G", "Göteborg C", 57.71, 11.97));

    @Test
    void ankomstOchAvgangPaSammaStationBlirEttStopp() throws Exception {
        JsonNode anns = new ObjectMapper().readTree("""
            [
              {"LocationSignature":"Cst","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T14:14:00.000+02:00"},
              {"LocationSignature":"Vå","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T15:08:00.000+02:00"},
              {"LocationSignature":"Vå","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T15:10:00.000+02:00"},
              {"LocationSignature":"Okänd","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T15:30:00.000+02:00"},
              {"LocationSignature":"Ör","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T16:05:00.000+02:00"},
              {"LocationSignature":"Ör","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T16:08:00.000+02:00"},
              {"LocationSignature":"G","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T19:30:00.000+02:00"}
            ]""");

        List<TrafikverketService.Stopp> stopp = TrafikverketService.byggStopp(anns, INDEX);

        assertThat(stopp).extracting(TrafikverketService.Stopp::namn)
            .containsExactly("Stockholm C", "Västerås C", "Örebro C", "Göteborg C");  // okänd signatur hoppas över
        assertThat(stopp.get(1).ankomst()).isEqualTo("15:08");
        assertThat(stopp.get(1).avgang()).isEqualTo("15:10");
        assertThat(stopp.get(0).ankomst()).isEmpty();
        assertThat(stopp.get(3).avgang()).isEmpty();
    }

    @Test
    void delstrackanKlipperUtResenarensDel() {
        var alla = List.of(
            new TrafikverketService.Stopp("Stockholm C", "Cst", 0, 0, "", "14:14"),
            new TrafikverketService.Stopp("Västerås C", "Vå", 0, 0, "15:08", "15:10"),
            new TrafikverketService.Stopp("Örebro C", "Ör", 0, 0, "16:05", "16:08"),
            new TrafikverketService.Stopp("Göteborg C", "G", 0, 0, "19:30", ""));
        assertThat(TrafikverketService.delstracka(alla, "Västerås C", "Örebro C"))
            .extracting(TrafikverketService.Stopp::namn).containsExactly("Västerås C", "Örebro C");
        // stadsnamn utan "C" matchar centralstationen
        assertThat(TrafikverketService.delstracka(alla, "Stockholm", "Göteborg")).hasSize(4);
        // okänt mål: hellre hela vägen än en tom karta
        assertThat(TrafikverketService.delstracka(alla, "Stockholm C", "Malmö C")).hasSize(4);
    }

    @Test
    void stoppenRaknasPerTagMellanResenarensStationer() throws Exception {
        // X2000 437 Stockholm → Örebro → Göteborg (1 stopp på vägen) och regionaltåget 177 som
        // stannar i Västerås och Örebro (2). Tåg 999 går inte till Göteborg: inget tal alls.
        JsonNode anns = new ObjectMapper().readTree("""
            [
              {"AdvertisedTrainIdent":"437","LocationSignature":"Cst","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T14:16:00.000+02:00"},
              {"AdvertisedTrainIdent":"177","LocationSignature":"Cst","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T14:14:00.000+02:00"},
              {"AdvertisedTrainIdent":"177","LocationSignature":"Vå","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T15:11:00.000+02:00"},
              {"AdvertisedTrainIdent":"437","LocationSignature":"Ör","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T15:40:00.000+02:00"},
              {"AdvertisedTrainIdent":"177","LocationSignature":"Ör","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T16:32:00.000+02:00"},
              {"AdvertisedTrainIdent":"437","LocationSignature":"G","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T17:27:00.000+02:00"},
              {"AdvertisedTrainIdent":"177","LocationSignature":"G","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T19:30:00.000+02:00"},
              {"AdvertisedTrainIdent":"999","LocationSignature":"Cst","ActivityType":"Avgang","AdvertisedTimeAtLocation":"2026-09-29T09:00:00.000+02:00"},
              {"AdvertisedTrainIdent":"999","LocationSignature":"Vå","ActivityType":"Ankomst","AdvertisedTimeAtLocation":"2026-09-29T10:00:00.000+02:00"}
            ]""");

        Map<String, Integer> antal = TrafikverketService.raknaStopp(anns, INDEX, "Stockholm C", "Göteborg C");

        assertThat(antal).containsEntry("437", 1).containsEntry("177", 2).doesNotContainKey("999");
    }

    // --- trafikläget till uppstartsskärmen ---

    @Test
    void trafiklagetRaknarTagBolagPunktlighetOchInstallda() throws Exception {
        java.time.ZonedDateTime nu = java.time.ZonedDateTime.parse("2026-09-29T10:00:00+02:00");
        JsonNode anns = new ObjectMapper().readTree("""
            [
              {"AdvertisedTrainIdent":"421","TrainOwner":"SJ","AdvertisedTimeAtLocation":"2026-09-29T09:50:00.000+02:00",
               "TimeAtLocation":"2026-09-29T09:53:00.000+02:00"},
              {"AdvertisedTrainIdent":"421","TrainOwner":"SJ","AdvertisedTimeAtLocation":"2026-09-29T10:20:00.000+02:00"},
              {"AdvertisedTrainIdent":"1055","TrainOwner":"SKANE","AdvertisedTimeAtLocation":"2026-09-29T09:40:00.000+02:00",
               "TimeAtLocation":"2026-09-29T09:52:00.000+02:00"},
              {"AdvertisedTrainIdent":"77","TrainOwner":"MTR","AdvertisedTimeAtLocation":"2026-09-29T10:50:00.000+02:00"},
              {"AdvertisedTrainIdent":"99","TrainOwner":"SJ","AdvertisedTimeAtLocation":"2026-09-29T10:05:00.000+02:00","Canceled":true}
            ]""");

        TrafikverketService.TrafikLage t = TrafikverketService.summeraTrafik(anns, nu);

        assertThat(t.tagITrafik()).isEqualTo(2);          // 421 och 1055; 77 går om 50 min, 99 inställt
        assertThat(t.bolag()).isEqualTo(2);               // SJ, SKANE
        assertThat(t.avgangarNastaTimme()).isEqualTo(2);  // 10:20 och 10:50
        assertThat(t.avgangnaSenaste()).isEqualTo(2);
        assertThat(t.iTid()).isEqualTo(1);                // +3 min i tid, +12 min försenad
        assertThat(t.punktlighetProcent()).isEqualTo(50);
        assertThat(t.installda()).isEqualTo(1);
    }
}
