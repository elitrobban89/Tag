package com.minipristaget;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Vagnsskisser: var toaletter, bistro och entréer sitter i förhållande till platsnumren.
 *
 * Detta är appens ENDA källa för vagnsuppsättningen — platskartan, skissen i chatten och
 * AI:ns systemprompt läser alla härifrån, så en ändring slår igenom överallt samtidigt.
 * Skisserna är förenklade (rätt ordning och rätt ände, inte millimeterexakta), och det
 * står uttryckligen i texten som går till AI:n så att den inte utger dem för exakta.
 *
 * Positionsmodell: varje vagn har ett radintervall och raderna räknas i tågets riktning
 * (låga radnummer mot vagn 1). Varje plats får en global "radkoordinat" = alla föregående
 * vagnars längd + radens plats i vagnen, med {@link #WAGON_GAP} rader som övergång mellan
 * vagnar. Avstånd till en facilitet är skillnaden i den koordinaten — det gör att frågor
 * som "ligger 28C närmare bistron än 12A?" kan besvaras med räkning i stället för gissning.
 */
@Service
public class TrainLayoutService {

    /** Rader som räknas för övergången mellan två vagnar (dörrar, vestibul). */
    private static final double WAGON_GAP = 3;

    public record Facility(String type, String label, int wagon, double row, String note) {}

    public record Wagon(int number, String label, String seatClass, int rowFrom, int rowTo,
                        List<String> cols) {
        public int rowCount() { return rowTo - rowFrom + 1; }
        public int seats() {
            long realCols = cols.stream().filter(java.util.Objects::nonNull).count();
            return (int) (rowCount() * realCols);
        }
    }

    public record Layout(String id, String trainName, String note,
                         List<Wagon> wagons, List<Facility> facilities) {}

    private static List<String> cols(String... c) { return Arrays.asList(c); }

    private static final Map<String, Layout> LAYOUTS = new LinkedHashMap<>();

    static {
        // ── SJ X2000: 2 klass, 2 klass Lugn, bistro, 1 klass ───────────────────────────
        LAYOUTS.put("x2000", new Layout("x2000", "SJ X2000",
            "Bistron ligger mitt i tåget, mellan vagn 2 och vagn 3.",
            List.of(
                new Wagon(1, "Vagn 1 · 2 klass",      "2 klass",      11, 34, cols("A","B",null,"C","D")),
                new Wagon(2, "Vagn 2 · 2 klass Lugn", "2 klass Lugn", 11, 30, cols("A","B",null,"C","D")),
                new Wagon(3, "Vagn 3 · 1 klass",      "1 klass",       1, 14, cols("A",null,"B","C"))
            ),
            List.of(
                new Facility("TOALETT", "Toalett", 1, 11, "vid vagnens främre ände"),
                new Facility("TOALETT", "Toalett", 1, 34, "vid övergången till vagn 2"),
                new Facility("TOALETT", "Toalett", 2, 30, "närmast bistron"),
                new Facility("TOALETT", "Toalett", 3,  1, "rullstolsanpassad"),
                new Facility("BISTRO",  "Bistro",  2, 31, "mellan vagn 2 och vagn 3"),
                new Facility("ENTRE",   "Entré",   1, 11, "påstigning"),
                new Facility("ENTRE",   "Entré",   2, 30, "påstigning"),
                new Facility("ENTRE",   "Entré",   3, 14, "påstigning")
            )));

        // ── SJ 3000 (X55): fyra vagnar, bistrovagn näst sist ──────────────────────────
        LAYOUTS.put("sj3000", new Layout("sj3000", "SJ 3000 (X55)",
            "Fyra vagnar. Bistron ligger i vagn 3, i änden mot vagn 2.",
            List.of(
                new Wagon(1, "Vagn 1 · 1 klass",        "1 klass",       1, 20, cols("A",null,"B","C")),
                new Wagon(2, "Vagn 2 · 2 klass",        "2 klass",       1, 22, cols("A","B",null,"C","D")),
                new Wagon(3, "Vagn 3 · 2 klass + bistro","2 klass",      1,  7, cols("A","B",null,"C","D")),
                new Wagon(4, "Vagn 4 · 2 klass Lugn",   "2 klass Lugn",  1, 17, cols("A","B",null,"C","D"))
            ),
            List.of(
                new Facility("TOALETT",  "Toalett",     1,  1, "rullstolsanpassad, låginsteg"),
                new Facility("TOALETT",  "Toalett",     2, 22, "vid övergången till vagn 3"),
                new Facility("TOALETT",  "Toalett",     3,  7, "vid övergången till vagn 4"),
                new Facility("TOALETT",  "Toalett",     4,  1, "vid vagnens ände"),
                new Facility("BISTRO",   "Bistro",      3,  1, "i vagn 3, änden mot vagn 2"),
                new Facility("RULLSTOL", "Rullstolslift",1, 1, "i ändvagnen"),
                new Facility("ENTRE",    "Entré",       2,  1, "påstigning"),
                new Facility("ENTRE",    "Entré",       4, 17, "påstigning")
            )));

        // ── MTRX / X74 ────────────────────────────────────────────────────────────────
        LAYOUTS.put("x74", new Layout("x74", "X74",
            "Serveringen ligger i vagn 1, i änden mot vagn 2.",
            List.of(
                new Wagon(1, "Vagn 1 · 2 klass",      "2 klass",      1, 26, cols("1","2",null,"3","4")),
                new Wagon(2, "Vagn 2 · 2 klass Lugn", "2 klass Lugn", 1, 22, cols("1","2",null,"3","4"))
            ),
            List.of(
                new Facility("TOALETT", "Toalett",  1,  1, "vid vagnens främre ände"),
                new Facility("TOALETT", "Toalett",  1, 26, "vid övergången till vagn 2"),
                new Facility("TOALETT", "Toalett",  2, 22, "rullstolsanpassad"),
                new Facility("BISTRO",  "Servering",1, 26, "kiosk/servering"),
                new Facility("ENTRE",   "Entré",    1,  1, "påstigning"),
                new Facility("ENTRE",   "Entré",    2, 22, "påstigning")
            )));

        // ── Snälltåget ────────────────────────────────────────────────────────────────
        LAYOUTS.put("snalltaget", new Layout("snalltaget", "Snälltåget",
            "Restaurangvagnen ligger mellan vagn 1 och vagn 2.",
            List.of(
                new Wagon(1, "Vagn 1 · 2 klass", "2 klass", 1, 24, cols("A","B",null,"C","D")),
                new Wagon(2, "Vagn 2 · 2 klass", "2 klass", 1, 24, cols("A","B",null,"C","D"))
            ),
            List.of(
                new Facility("TOALETT", "Toalett",       1,  1, "vid vagnens ände"),
                new Facility("TOALETT", "Toalett",       1, 24, "vid restaurangvagnen"),
                new Facility("TOALETT", "Toalett",       2, 24, "vid vagnens ände"),
                new Facility("BISTRO",  "Restaurangvagn",1, 25, "mellan vagn 1 och vagn 2"),
                new Facility("ENTRE",   "Entré",         1,  1, "påstigning"),
                new Facility("ENTRE",   "Entré",         2, 24, "påstigning")
            )));

        // ── MTR Express ───────────────────────────────────────────────────────────────
        LAYOUTS.put("mtr", new Layout("mtr", "MTR Express",
            "Serveringen ligger mellan vagn 1 och vagn 2.",
            List.of(
                new Wagon(1, "Vagn 1 · 2 klass", "2 klass", 1, 28, cols("A","B",null,"C","D")),
                new Wagon(2, "Vagn 2 · 1 klass", "1 klass", 1, 12, cols("A",null,"B","C"))
            ),
            List.of(
                new Facility("TOALETT", "Toalett",   1,  1, "vid vagnens främre ände"),
                new Facility("TOALETT", "Toalett",   1, 28, "vid serveringen"),
                new Facility("TOALETT", "Toalett",   2, 12, "rullstolsanpassad"),
                new Facility("BISTRO",  "Servering", 1, 29, "mellan vagn 1 och vagn 2"),
                new Facility("ENTRE",   "Entré",     1,  1, "påstigning"),
                new Facility("ENTRE",   "Entré",     2, 12, "påstigning")
            )));
    }

    public Layout getLayout(String id) {
        return id == null ? null : LAYOUTS.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public boolean hasLayout(String id) { return getLayout(id) != null; }

    public List<String> allIds() { return List.copyOf(LAYOUTS.keySet()); }

    /** Platskod från platskartan: "2-28C" = vagn 2, rad 28, plats C. Tom sträng om okänd. */
    public String seatFactsFromCode(String layoutId, String seatCode) {
        if (seatCode == null || seatCode.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(\\d+)\\s*-\\s*(\\d+)\\s*([A-Za-z0-9]?)\\s*$").matcher(seatCode);
        if (!m.matches()) return "";
        try {
            return seatFacts(layoutId, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                             m.group(3).isBlank() ? null : m.group(3).toUpperCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** Global radkoordinat för (vagn, rad) — jämförbar mellan vagnar. */
    double position(Layout layout, int wagon, double row) {
        double pos = 0;
        for (Wagon w : layout.wagons()) {
            if (w.number() == wagon) return pos + (row - w.rowFrom());
            pos += w.rowCount() + WAGON_GAP;
        }
        return pos;
    }

    /** Avstånd i rader mellan en plats och en facilitet (över vagnsgränser). */
    double distance(Layout layout, int wagon, double row, Facility f) {
        return Math.abs(position(layout, wagon, row) - position(layout, f.wagon(), f.row()));
    }

    public Facility nearest(Layout layout, String type, int wagon, double row) {
        Facility best = null;
        double bestDist = Double.MAX_VALUE;
        for (Facility f : layout.facilities()) {
            if (!f.type().equalsIgnoreCase(type)) continue;
            double d = distance(layout, wagon, row, f);
            if (d < bestDist) { bestDist = d; best = f; }
        }
        return best;
    }

    /**
     * Konkreta fakta om en vald plats — matas in i chattkontexten så att AI:n kan svara
     * "din plats ligger 3 rader från toaletten" utan att räkna själv.
     */
    public String seatFacts(String layoutId, int wagon, int row, String col) {
        Layout layout = getLayout(layoutId);
        if (layout == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Vald plats: vagn ").append(wagon).append(", rad ").append(row)
          .append(col == null ? "" : col).append(".\n");

        Facility wc = nearest(layout, "TOALETT", wagon, row);
        if (wc != null)
            sb.append("- Närmaste toalett: vagn ").append(wc.wagon())
              .append(" (").append(wc.note()).append("), ca ")
              .append(rowsText(distance(layout, wagon, row, wc))).append(" bort.\n");

        Facility bistro = nearest(layout, "BISTRO", wagon, row);
        if (bistro != null)
            sb.append("- ").append(bistro.label()).append(": ").append(bistro.note())
              .append(", ca ").append(rowsText(distance(layout, wagon, row, bistro))).append(" bort.\n");

        return sb.toString();
    }

    private static String rowsText(double rows) {
        int r = (int) Math.round(rows);
        return r <= 1 ? "1 rad" : r + " rader";
    }

    /**
     * Vagnsskissen som text till systemprompten, inklusive vilka platser som ligger
     * närmast bistron — det är den vanligaste frågan och den AI:n annars gissar på.
     */
    public String describeForPrompt(String layoutId) {
        Layout layout = getLayout(layoutId);
        if (layout == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Vagnsskiss ").append(layout.trainName())
          .append(" (appens förenklade skiss — rätt ordning och rätt ände, inte exakta mått)\n");

        List<String> strip = new ArrayList<>();
        for (Wagon w : layout.wagons()) {
            strip.add("[" + w.label() + " · rad " + w.rowFrom() + "–" + w.rowTo() + "]");
        }
        sb.append(String.join(" – ", strip)).append("\n");
        sb.append(layout.note()).append("\n");

        for (Facility f : layout.facilities()) {
            if (f.type().equals("ENTRE")) continue;
            sb.append("- ").append(f.label()).append(": vagn ").append(f.wagon())
              .append(", vid rad ").append((int) f.row())
              .append(" (").append(f.note()).append(")\n");
        }

        Facility bistro = layout.facilities().stream()
                .filter(f -> f.type().equals("BISTRO")).findFirst().orElse(null);
        if (bistro != null) {
            sb.append("- Närmast ").append(bistro.label().toLowerCase(Locale.ROOT)).append(": ");
            List<String> nearBits = new ArrayList<>();
            for (Wagon w : layout.wagons()) {
                int lowDist  = (int) Math.round(distance(layout, w.number(), w.rowFrom(), bistro));
                int highDist = (int) Math.round(distance(layout, w.number(), w.rowTo(),   bistro));
                int best = Math.min(lowDist, highDist);
                if (best > 12) continue;
                nearBits.add("vagn " + w.number() + " rad "
                        + (lowDist <= highDist ? w.rowFrom() + "–" + Math.min(w.rowFrom() + 3, w.rowTo())
                                               : Math.max(w.rowTo() - 3, w.rowFrom()) + "–" + w.rowTo()));
            }
            sb.append(String.join(", ", nearBits)).append("\n");
        }
        return sb.toString();
    }
}
