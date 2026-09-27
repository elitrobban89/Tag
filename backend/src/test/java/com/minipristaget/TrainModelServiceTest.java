package com.minipristaget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester för TrainModelServices rena logik: operatörsmappning, prisberäkning
 * (MiniPris/Lugn/1 klass/ordinarie), platser kvar och restidsuppskattning.
 *
 * @author Robert Andersson Kopler
 */
class TrainModelServiceTest {

    private final TrainModelService service = new TrainModelService();

    // --- getModel ---

    @Test
    void kandOperatorMappasSkiftlagesokansligt() {
        assertThat(service.getModel("SJ").name()).isEqualTo("SJ X2000");
        assertThat(service.getModel("sj").name()).isEqualTo("SJ X2000");
        assertThat(service.getModel("  mtrx  ").name()).isEqualTo("VR Snabbtåg X74");
    }

    @Test
    void okandEllerSaknadOperatorFarDefault() {
        assertThat(service.getModel("PENDELTÅG-X").name()).isEqualTo("Regionaltåg");
        assertThat(service.getModel(null).name()).isEqualTo("Regionaltåg");
        assertThat(service.getModel("").name()).isEqualTo("Regionaltåg");
    }

    // --- SJ 3000 (X55): Trafikverket anger aldrig fordonstyp, så destination + produktnamn styr ---

    @Test
    void sjTillNorrOchOsloBlirSj3000() {
        assertThat(service.getModel("SJ", "Sundsvall C").name()).isEqualTo("SJ 3000");
        assertThat(service.getModel("SJ", "Östersund C").name()).isEqualTo("SJ 3000");
        assertThat(service.getModel("SJ", "Oslo S").name()).isEqualTo("SJ 3000");
        assertThat(service.getModel("SJ", "Sundsvall C").seatLayout()).isEqualTo("sj3000");
    }

    @Test
    void ovrigaSjStrackorFortsatterVaraX2000() {
        assertThat(service.getModel("SJ", "Göteborg C").name()).isEqualTo("SJ X2000");
        assertThat(service.getModel("SJ", null).name()).isEqualTo("SJ X2000");
    }

    @Test
    void bekraftatTagnummerVinnerOverDestinationen() {
        // Tåg 442 Göteborg → Stockholm är SJ 3000 trots att destinationen annars ger X2000
        assertThat(service.getModel("SJ", "Stockholm C", "SJ Snabbtåg", "442").name())
                .isEqualTo("SJ 3000");
        assertThat(service.getModel("SJ", "Stockholm C", "SJ Snabbtåg", "442").seatLayout())
                .isEqualTo("sj3000");
        // Okänt nummer faller tillbaka på destinationsregeln
        assertThat(service.getModel("SJ", "Stockholm C", "SJ Snabbtåg", "416").name())
                .isEqualTo("SJ X2000");
        assertThat(service.getModel("SJ", "Stockholm C", "SJ Snabbtåg", null).name())
                .isEqualTo("SJ X2000");
    }

    @Test
    void badeSj3000TagenPaGoteborgStockholmKannsIgen() {
        // Fredag 31 juli: 442 och 452 är SJ 3000, alla andra direkttåg X2000
        for (String nr : new String[]{"442", "452"}) {
            TrainDeparture dep = new TrainDeparture();
            dep.setOperator("SJ");
            dep.setDestination("Stockholm C");
            dep.setTrainId(nr);
            assertThat(service.resolveModel(dep, "Göteborg C").name())
                    .as("tåg %s", nr).isEqualTo("SJ 3000");
        }
        for (String nr : new String[]{"440", "444", "450", "454"}) {
            TrainDeparture dep = new TrainDeparture();
            dep.setOperator("SJ");
            dep.setDestination("Stockholm C");
            dep.setTrainId(nr);
            assertThat(service.resolveModel(dep, "Göteborg C").name())
                    .as("tåg %s", nr).isEqualTo("SJ X2000");
        }
    }

    @Test
    void resolveModelLaterTagnumretVinna() {
        TrainDeparture dep = new TrainDeparture();
        dep.setOperator("SJ");
        dep.setDestination("Stockholm C");
        dep.setDepartureTime("07:00");   // ingen känd avgång
        dep.setTrainId("442");
        assertThat(service.resolveModel(dep, "Göteborg C").name()).isEqualTo("SJ 3000");
    }

    @Test
    void tagnummerserienAnvandsNarOperatorSaknas() {
        // Serier avlästa ur Trafikverkets data 31 juli 2026
        assertThat(modelForNumber("62024").name()).isEqualTo("SJ Regional");   // SJ Regional
        assertThat(modelForNumber("1026").name()).contains("Öresundståg");     // Öresundståg fjärr
        assertThat(modelForNumber("20150").name()).contains("Öresundståg");    // rusningsförstärkning
        assertThat(modelForNumber("3040").name()).contains("Västtåg");         // Västtrafik
        assertThat(modelForNumber("13120").name()).contains("Västtåg");
        assertThat(modelForNumber("440").name()).isEqualTo("SJ X2000");        // SJ snabbtåg

        // Okänd serie faller tillbaka på default
        assertThat(modelForNumber("99999").name()).isEqualTo("Regionaltåg");
    }

    @Test
    void riktigOperatorVinnerOverTagnummerserien() {
        TrainDeparture dep = new TrainDeparture();
        dep.setOperator("VASTTRAF");
        dep.setTrainId("1026");   // Öresundstågsserie, men operatören säger Västtrafik
        assertThat(service.resolveModel(dep, "Kungsbacka").name()).isEqualTo("X61 Västtåg");
    }

    private TrainModelService.TrainModelInfo modelForNumber(String trainId) {
        TrainDeparture dep = new TrainDeparture();
        dep.setTrainId(trainId);           // ingen operatör — som i Trafikverkets svar
        return service.resolveModel(dep, "Göteborg C");
    }

    @Test
    void destinationPaverkarBaraSj() {
        assertThat(service.getModel("MTRX", "Sundsvall C").name()).isEqualTo("VR Snabbtåg X74");
    }

    @Test
    void produktnamnetSkiljerRegionaltagFranSnabbtag() {
        assertThat(service.getModel("SJ", "Uppsala C", "SJ Regional").name()).isEqualTo("SJ Regional");
        assertThat(service.getModel("SJ", "Uppsala C", "SJ Regional").hasSeatMap()).isFalse();
        assertThat(service.getModel("SJ", "Göteborg C", "SJ Snabbtåg").name()).isEqualTo("SJ X2000");
        // Regionalt vinner även på en SJ 3000-sträcka — produktnamnet är mer specifikt
        assertThat(service.getModel("SJ", "Sundsvall C", "SJ Regional").name()).isEqualTo("SJ Regional");
    }

    // --- prisberäkning ---

    @Test
    void minirisSlutarAlltidPa9() {
        for (String id : new String[]{"tåg-1", "tåg-2", "abc123", "xyz"}) {
            String pris = service.calculatePrice(455, id); // Sthlm–Gbg
            int kr = Integer.parseInt(pris.replaceAll("\\D", ""));
            assertThat(kr % 10).as("pris %s för %s", pris, id).isEqualTo(9);
            assertThat(kr).isBetween(19, 2499);
        }
    }

    @Test
    void prisstegenLugnOch1KlassArDyrareAnMiniPris() {
        double dist = 455;
        String id = "tåg-42";
        int mini  = Integer.parseInt(service.calculatePrice(dist, id).replaceAll("\\D", ""));
        int lugn  = Integer.parseInt(service.calculatePriceLugn(dist, id).replaceAll("\\D", ""));
        int first = Integer.parseInt(service.calculatePrice1Klass(dist, id).replaceAll("\\D", ""));
        int full  = service.calculateOrdinaryPrice(dist, id);
        assertThat(lugn).isGreaterThan(mini);
        assertThat(first).isGreaterThan(lugn);
        assertThat(full).isGreaterThan(first);
    }

    @Test
    void nollDistansGerIngetPris() {
        assertThat(service.calculatePrice(0, "x")).isEmpty();
        assertThat(service.calculateOrdinaryPrice(0, "x")).isZero();
    }

    @Test
    void prisetArDeterministisktPerTag() {
        assertThat(service.calculatePrice(455, "tåg-1"))
                .isEqualTo(service.calculatePrice(455, "tåg-1"));
    }

    // --- platser kvar ---

    @Test
    void platserKvarArMellan1Och5OchDeterministiskt() {
        for (String id : new String[]{"a", "b", "c", "längre-tåg-id-123", null}) {
            int seats = service.calculateSeatsLeft(id);
            assertThat(seats).isBetween(1, 5);
            assertThat(seats).isEqualTo(service.calculateSeatsLeft(id));
        }
    }

    // --- restid ---

    @Test
    void restidAvrundasTillNarmasteFemMinuter() {
        int min = service.estimateTravelMinutes(455, 160); // ~171+10 → 185
        assertThat(min % 5).isZero();
        assertThat(min).isBetween(175, 195);
    }

    @Test
    void ogiltigDistansEllerHastighetGerNoll() {
        assertThat(service.estimateTravelMinutes(0, 160)).isZero();
        assertThat(service.estimateTravelMinutes(455, 0)).isZero();
    }

    // --- alla bolag och produkter i Trafikverkets data (inventerat 2026-09-27) ---

    private TrainModelService.TrainModelInfo modell(String owner, String produkt, String trafiktyp) {
        TrainDeparture d = new TrainDeparture();
        d.setTrainId("9999");
        d.setOperator(owner);
        d.setProductInformation(produkt);
        d.setTypeOfTraffic(trafiktyp);
        d.setDestination("Någonstans");
        return service.resolveModel(d, "Stockholm C");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        // owner   | produkt            | trafiktyp  | förväntat namn         | bolag
        "MÄLAB     | Mälartåg           | Tåg        | Mälartåg               | Mälartåg",
        "SLL       | SL Pendeltåg/40    | Pendeltåg  | SL Pendeltåg X60       | SL",
        "SKANE     | Pågatågen          | Tåg        | Pågatåg X61            | Pågatåg",
        "SKANE     | Pågatågen Exp      | Tåg        | Pågatåg Express X61    | Pågatåg",
        "JLT       | Krösatågen         | Tåg        | Krösatåg               | Krösatåg",
        "SJ        | SJ Regional        | Tåg        | SJ Regional            | SJ",
        "SJ        | SJ InterCity       | Tåg        | SJ InterCity           | SJ",
        "SJ        | SJ Nattåg          | Tåg        | SJ Nattåg              | SJ",
        "VASTTRAF  | Västtågen          | Tåg        | Västtåg                | Västtåg",
        "VASTTRAF  | Västtågen          | Pendeltåg  | Västtågen pendeltåg    | Västtåg",
        "ÖTRAF     | Östgötapendel      | Tåg        | Östgötapendeln         | Östgötapendeln",
        "ATRAIN    |                    | Tåg        | Arlanda Express X3     | Arlanda Express",
        "TIB       | TiB                | Tåg        | Tåg i Bergslagen       | Tåg i Bergslagen",
        "NORRT     | Norrtåg            | Tåg        | Norrtåg X62            | Norrtåg",
        "XTRAFIK   | X-Tåget            | Tåg        | X-Tåget                | X-Tåget",
        "VY        | Vy Snabbtåg        | Tåg        | Vy Snabbtåg            | Vy",
        "SNÄLL     | Snälltåget         | Tåg        | Snälltåget             | Snälltåget",
        "Ö-TÅG     | Öresundståg        | Tåg        | Öresundståg X31K       | Öresundståg",
        "MTRX      | VR Snabbtåg        | Tåg        | VR Snabbtåg X74        | VR",
        "SJ        | SJ Snabbtåg        | Tåg        | SJ X2000               | SJ",
        "VASTTRAF  | Västtågen          | Buss       | Ersättningsbuss        | Buss",
    })
    void varjeBolagIDatanKannsIgen(String owner, String produkt, String trafiktyp, String namn, String bolag) {
        assertThat(modell(owner, produkt, trafiktyp).name()).isEqualTo(namn);
        assertThat(TrainModelService.operatorName(owner, produkt, trafiktyp)).isEqualTo(bolag);
    }

    @Test
    void snalltagetsRiktigaOperatorskodKannsIgen() {
        // Trafikverket skriver "SNÄLL" — tabellen hade bara "SNALLTAGET" och träffade aldrig
        assertThat(service.getModel("SNÄLL").name()).isEqualTo("Snälltåget");
    }

    // --- priser som liknar de riktiga ---

    @Test
    void oresundstagKungsbackaTillKastrupKostarSomRiktigt() {
        // Kungsbacka → Copenhagen Airport ≈ 210 km fågelväg; riktigt pris 508 kr i 2 klass
        int pris = service.basePrice(209.5, "1057", TrainModelService.Priskategori.REGIONAL);
        assertThat(pris).isBetween(490, 530);
        // Regionaltåg har fast pris per sträcka — samma för alla avgångar
        assertThat(service.basePrice(209.5, "1099", TrainModelService.Priskategori.REGIONAL)).isEqualTo(pris);
    }

    @Test
    void slOchArlandaExpressHarFastaPriserUtanOverstrykning() {
        assertThat(service.basePrice(40, "2280", TrainModelService.Priskategori.SL)).isEqualTo(45);
        assertThat(service.calculateOrdinaryPrice(40, "2280", TrainModelService.Priskategori.SL)).isZero();
        assertThat(service.basePrice(37, "7878", TrainModelService.Priskategori.ARLANDA)).isEqualTo(339);
    }

    @Test
    void prisKategoriFoljerTagtypen() {
        assertThat(TrainModelService.kategori(TrainModelService.PAGATAG)).isEqualTo(TrainModelService.Priskategori.REGIONAL);
        assertThat(TrainModelService.kategori(TrainModelService.NATTAG)).isEqualTo(TrainModelService.Priskategori.NATT);
        assertThat(TrainModelService.kategori(TrainModelService.SL_PENDEL)).isEqualTo(TrainModelService.Priskategori.SL);
        assertThat(TrainModelService.kategori(TrainModelService.INTERCITY)).isEqualTo(TrainModelService.Priskategori.SNABB);
    }
}
