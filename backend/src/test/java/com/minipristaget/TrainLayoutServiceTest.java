package com.minipristaget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainLayoutServiceTest {

    private final TrainLayoutService service = new TrainLayoutService();

    @Test
    void allaLayouterHarVagnarOchToalett() {
        for (String id : service.allIds()) {
            TrainLayoutService.Layout l = service.getLayout(id);
            assertThat(l.wagons()).as("vagnar i %s", id).isNotEmpty();
            assertThat(l.facilities()).as("toalett i %s", id)
                    .anyMatch(f -> f.type().equals("TOALETT"));
            assertThat(l.facilities()).as("bistro i %s", id)
                    .anyMatch(f -> f.type().equals("BISTRO"));
        }
    }

    @Test
    void sj3000FinnsMedFyraVagnarOchBistroIVagnTre() {
        TrainLayoutService.Layout l = service.getLayout("sj3000");
        assertThat(l.trainName()).contains("SJ 3000");
        assertThat(l.wagons()).hasSize(4);
        assertThat(l.facilities())
                .filteredOn(f -> f.type().equals("BISTRO"))
                .singleElement()
                .satisfies(f -> assertThat(f.wagon()).isEqualTo(3));
    }

    @Test
    void platsNaraBistronLiggerNarmareAnPlatsLangtBort() {
        TrainLayoutService.Layout l = service.getLayout("x2000");
        TrainLayoutService.Facility bistro = l.facilities().stream()
                .filter(f -> f.type().equals("BISTRO")).findFirst().orElseThrow();

        double nara  = service.distance(l, 2, 30, bistro);   // sista raden i vagn 2, intill bistron
        double langt = service.distance(l, 1, 11, bistro);   // första raden i vagn 1, andra änden

        assertThat(nara).isLessThan(langt);
        assertThat(nara).isLessThan(3);
    }

    @Test
    void narmasteToalettValjsOverVagnsgrans() {
        TrainLayoutService.Layout l = service.getLayout("sj3000");
        // Vagn 2 rad 22 ligger vid övergången till vagn 3 — toaletten där ska vinna
        TrainLayoutService.Facility wc = service.nearest(l, "TOALETT", 2, 22);
        assertThat(wc.wagon()).isEqualTo(2);
        assertThat(wc.row()).isEqualTo(22);

        // Oavsett plats ska ingen annan toalett i tåget ligga närmare än den valda
        for (int wagon = 1; wagon <= 4; wagon++) {
            TrainLayoutService.Wagon w = l.wagons().get(wagon - 1);
            for (int row = w.rowFrom(); row <= w.rowTo(); row++) {
                TrainLayoutService.Facility picked = service.nearest(l, "TOALETT", wagon, row);
                double pickedDist = service.distance(l, wagon, row, picked);
                for (TrainLayoutService.Facility other : l.facilities()) {
                    if (!other.type().equals("TOALETT")) continue;
                    assertThat(pickedDist)
                            .as("vagn %d rad %d valde toalett i vagn %d", wagon, row, picked.wagon())
                            .isLessThanOrEqualTo(service.distance(l, wagon, row, other));
                }
            }
        }
    }

    @Test
    void seatFactsGerVagnRadOchAvstand() {
        String facts = service.seatFacts("x2000", 2, 28, "C");
        assertThat(facts)
                .contains("vagn 2")
                .contains("rad 28C")
                .contains("Närmaste toalett")
                .contains("Bistro");
    }

    @Test
    void seatFactsFromCodeTolkarPlatskod() {
        assertThat(service.seatFactsFromCode("x2000", "2-28C")).contains("rad 28C");
        assertThat(service.seatFactsFromCode("x2000", "skräp")).isEmpty();
        assertThat(service.seatFactsFromCode("x2000", null)).isEmpty();
        assertThat(service.seatFactsFromCode("finns-inte", "2-28C")).isEmpty();
    }

    @Test
    void promptbeskrivningenListarVagnarOchFaciliteter() {
        String text = service.describeForPrompt("sj3000");
        assertThat(text)
                .contains("Vagnsskiss SJ 3000")
                .contains("Vagn 1 · 1 klass")
                .contains("Toalett: vagn")
                .contains("Närmast bistro");
        // Skissen ska deklarera sig som förenklad så att AI:n inte utger den för exakt
        assertThat(text).contains("förenklade");
    }

    @Test
    void okandLayoutGerTommaSvarIStalletForKrasch() {
        assertThat(service.getLayout("finns-inte")).isNull();
        assertThat(service.hasLayout(null)).isFalse();
        assertThat(service.describeForPrompt("finns-inte")).isEmpty();
        assertThat(service.seatFacts("finns-inte", 1, 1, "A")).isEmpty();
    }
}
