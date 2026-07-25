package com.minipristaget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester för TrainModelServices rena logik: operatörsmappning, prisberäkning
 * (MiniPris/Lugn/1 klass/ordinarie), platser kvar och restidsuppskattning.
 */
class TrainModelServiceTest {

    private final TrainModelService service = new TrainModelService();

    // --- getModel ---

    @Test
    void kandOperatorMappasSkiftlagesokansligt() {
        assertThat(service.getModel("SJ").name()).isEqualTo("SJ X2000");
        assertThat(service.getModel("sj").name()).isEqualTo("SJ X2000");
        assertThat(service.getModel("  mtrx  ").name()).isEqualTo("X74");
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
    void destinationPaverkarBaraSj() {
        assertThat(service.getModel("MTRX", "Sundsvall C").name()).isEqualTo("X74");
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
}
