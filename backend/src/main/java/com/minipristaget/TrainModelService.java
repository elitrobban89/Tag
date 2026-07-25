package com.minipristaget;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class TrainModelService {

    public record TrainModelInfo(
        String name, String color, int avgSpeedKmh, String description,
        String imageUrl, boolean hasSeatMap, String seatLayout) {}

    private static final TrainModelInfo DEFAULT =
        new TrainModelInfo("Regionaltåg", "#6b7280", 100, "Regionaltåg",
                           "/images/train-sj-regional.png", false, "none");

    private static final Map<String, TrainModelInfo> MODELS = Map.ofEntries(
        Map.entry("SJ",         new TrainModelInfo("SJ X2000",         "#CC0000", 160,
                                    "Snabbtåg · 200 km/h max",     "/images/train-sj-x2000.jpg",  true,  "x2000")),
        Map.entry("MTRX",       new TrainModelInfo("X74",             "#1a5e35", 175,
                                    "X74 · 200 km/h, nordisk design","/images/train-vy.jpg",        true,  "x74")),
        Map.entry("VR",         new TrainModelInfo("X74",             "#1a5e35", 175,
                                    "X74 (VR) · 200 km/h max",     "/images/train-vy.jpg",        true,  "x74")),
        Map.entry("MTRXEX",     new TrainModelInfo("X74",             "#1a5e35", 175,
                                    "X74 · 200 km/h max",           "/images/train-vy.jpg",        true,  "x74")),
        Map.entry("VASTTRAF",   new TrainModelInfo("X61 Västtåg",     "#0055a5", 110,
                                    "Regionaltåg västkusten",       "/images/train-sj-regional.png", false, "none")),
        Map.entry("Ö-TÅG",      new TrainModelInfo("Öresundståg X31","#004EA8", 120,
                                    "Regionaltåg Skåne",            "/images/train-oresundstag.jpg", false, "none")),
        Map.entry("SKANE",      new TrainModelInfo("Öresundståg X31","#004EA8", 120,
                                    "Regionaltåg Skåne",            "/images/train-oresundstag.jpg", false, "none")),
        Map.entry("SNALLTAGET", new TrainModelInfo("Snälltåget",      "#1a1a2e", 150,
                                    "Fjärrtåg & nattåg",            "/images/train-sj-fast.png",   true,  "snalltaget")),
        Map.entry("MTR",        new TrainModelInfo("MTR Express",     "#e85d00", 155,
                                    "Stockholm–Göteborg",           "/images/train-sj-fast.png",   true,  "mtr")),
        // Bild: SJ:s egen pressbild av X55 från Wikimedia Commons (CC BY 3.0, foto SJ AB)
        Map.entry("SJ3000",     new TrainModelInfo("SJ 3000",         "#CC0000", 165,
                                    "X55 · 200 km/h, bistro & plant insteg",
                                    "/images/train-sj-3000.jpg",   true,  "sj3000"))
    );

    /**
     * Destinationer där SJ kör SJ 3000 (X55) i stället för X2000. Trafikverkets öppna data
     * anger bara operatör ("SJ"), aldrig tågtyp, så modellen väljs på destinationen —
     * grundat på X55:ns faktiska linjer (Stockholm–Sundsvall/Östersund, Stockholm–Oslo,
     * Göteborg–Malmö). Enskilda avgångar kan avvika; X2000 är fortsatt default för SJ.
     */
    private static final java.util.List<String> SJ3000_DESTINATIONS = java.util.List.of(
        "sundsvall", "östersund", "ostersund", "oslo", "umeå", "umea",
        "härnösand", "harnosand", "hudiksvall");

    public TrainModelInfo getModel(String operator) {
        if (operator == null || operator.isBlank()) return DEFAULT;
        return MODELS.getOrDefault(operator.trim().toUpperCase(), DEFAULT);
    }

    /**
     * Kända tågnummer → fordonstyp. Trafikverket säger aldrig vilken tågtyp som går, och
     * destinationen räcker inte: Göteborg–Stockholm körs med BÅDE X2000 och SJ 3000. Det enda
     * som identifierar en enskild avgång är tågnumret, så bekräftade nummer läggs in här.
     *
     * Källa: SJ:s egen tidtabell (tåg 442 Göteborg C 15:19 → Stockholm C 19:46 = SJ 3000).
     * OBS: tågnummer byter fordonstyp mellan tidtabellsperioder — det här är en kurerad lista
     * som behöver ses över vid tidtabellsskifte, inte en evig sanning.
     */
    private static final Map<String, String> TRAIN_NUMBER_MODELS = Map.of(
        // Göteborg C → Stockholm C: tåg 442 och 452 körs med SJ 3000, övriga direkttåg med X2000
        "442", "SJ3000",
        "452", "SJ3000"
    );

    /** Som {@link #getModel(String)}, men väljer SJ 3000 på de sträckor X55 trafikerar. */
    public TrainModelInfo getModel(String operator, String destination) {
        return getModel(operator, destination, null);
    }

    /** Full upplösning: bekräftat tågnummer vinner över produktnamn som vinner över destination. */
    public TrainModelInfo getModel(String operator, String destination, String productInformation,
                                   String trainId) {
        if (trainId != null) {
            String known = TRAIN_NUMBER_MODELS.get(trainId.trim());
            if (known != null) return MODELS.get(known);
        }
        return getModel(operator, destination, productInformation);
    }

    /**
     * Bekräftade avgångar där tågnumret inte är känt men användaren sett vilken tågtyp som går.
     * Matchar på från-station + destination + avgångstid.
     */
    private record KnownDeparture(String fromContains, String toContains, List<String> times,
                                  String modelKey) {}

    // Tom just nu: Göteborg–Stockholm täcks av tågnumren 442/452 ovan, vilket är stabilare
    // än avgångstider. Lägg till entries här bara när tågnumret INTE är känt.
    private static final List<KnownDeparture> KNOWN_DEPARTURES = List.of();

    /**
     * Tågnummerserier per trafiktyp, avlästa ur Trafikverkets egen data 2026-07-31
     * (Kungsbacka–Göteborg och Göteborg–Stockholm):
     *   1000–1999   Öresundståg, fjärr (1010, 1018, 1026 … +8 per timme)
     *   20000–20999 Öresundståg, rusningsförstärkning (20150, 20152 … +2)
     *   3000–3999   Västtrafiks regionaltåg (3004, 3018, 3020 … +2 per halvtimme)
     *   13000–13999 Västtrafik, glesare turer (13120, 13128 …)
     *   60000–69999 SJ Regional (62024, 62028 …)
     *   400–499     SJ snabbtåg Göteborg–Stockholm (400, 424 … +2 per timme)
     * Används BARA när Trafikverket saknar operatör — annars vinner alltid det riktiga
     * operatörsfältet. Serierna är observerade, inte officiellt dokumenterade.
     */
    private static TrainModelInfo modelFromTrainNumber(String trainId) {
        int n;
        try { n = Integer.parseInt(trainId.trim()); } catch (Exception e) { return null; }
        if (n >= 1000 && n <= 1999)   return MODELS.get("Ö-TÅG");
        if (n >= 20000 && n <= 20999) return MODELS.get("Ö-TÅG");
        if (n >= 3000 && n <= 3999)   return MODELS.get("VASTTRAF");
        if (n >= 13000 && n <= 13999) return MODELS.get("VASTTRAF");
        if (n >= 60000 && n <= 69999) return REGIONAL_SJ;
        if (n >= 400 && n <= 499)     return MODELS.get("SJ");
        return null;
    }

    /**
     * Fordonstyp för en avgång. Ordning: bekräftat tågnummer → bekräftad avgång (från/till/tid)
     * → produktnamn → destination → operatörstabellen → tågnummerserie (om operatör saknas).
     * Trafikverket anger aldrig fordonstyp, så de första lagren är kurerad kunskap som behöver
     * ses över vid tidtabellsskifte.
     */
    public TrainModelInfo resolveModel(TrainDeparture dep, String fromName) {
        if (dep == null) return DEFAULT;

        if (dep.getTrainId() != null) {
            String known = TRAIN_NUMBER_MODELS.get(dep.getTrainId().trim());
            if (known != null) return MODELS.get(known);
        }

        String from = fromName == null ? "" : fromName.toLowerCase(java.util.Locale.ROOT);
        String to   = dep.getDestination() == null ? "" : dep.getDestination().toLowerCase(java.util.Locale.ROOT);
        String time = dep.getDepartureTime() == null ? "" : dep.getDepartureTime().trim();
        for (KnownDeparture k : KNOWN_DEPARTURES) {
            if (from.contains(k.fromContains()) && to.contains(k.toContains()) && k.times().contains(time))
                return MODELS.get(k.modelKey());
        }

        // Saknas operatör i datan (händer t.ex. för SJ Regional 62xxx) — läs trafiktypen
        // ur tågnummerserien i stället för att falla tillbaka på "Regionaltåg" för allt.
        if ((dep.getOperator() == null || dep.getOperator().isBlank()) && dep.getTrainId() != null) {
            TrainModelInfo bySeries = modelFromTrainNumber(dep.getTrainId());
            if (bySeries != null) return bySeries;
        }

        return getModel(dep.getOperator(), dep.getDestination(), dep.getProductInformation());
    }

    /**
     * Bästa gissning på fordonstyp. Trafikverkets API avslöjar aldrig vilken tågtyp som
     * går — bara operatör, tågnummer och (om man ber om det) produktnamnet. Därför:
     * produktnamnet skiljer snabbtåg från regionaltåg, och destinationen avgör om SJ:s
     * snabbtåg är X2000 eller SJ 3000. Allt annat faller tillbaka på operatörstabellen.
     */
    public TrainModelInfo getModel(String operator, String destination, String productInformation) {
        TrainModelInfo base = getModel(operator);
        boolean isSJ = operator != null && "SJ".equalsIgnoreCase(operator.trim());
        if (!isSJ) return base;

        String product = productInformation == null ? "" : productInformation.toLowerCase(java.util.Locale.ROOT);
        if (product.contains("regional") || product.contains("pendel") || product.contains("intercity"))
            return REGIONAL_SJ;

        if (destination != null) {
            String dest = destination.toLowerCase(java.util.Locale.ROOT);
            for (String route : SJ3000_DESTINATIONS) {
                if (dest.contains(route)) return MODELS.get("SJ3000");
            }
        }
        return base;
    }

    private static final TrainModelInfo REGIONAL_SJ =
        new TrainModelInfo("SJ Regional", "#CC0000", 120, "Regionaltåg",
                           "/images/train-sj-regional.png", false, "none");

    private static int roundToX9(double v) {
        int p = Math.max(19, Math.min(2499, (int) v));
        return ((p / 10) * 10) + 9;
    }

    /** MiniPris — deeply discounted base price for 2 klass. */
    private int basePrice(double distKm, String trainId) {
        if (distKm <= 0) return 0;
        // Sharp MiniPris flash-sale pricing: ~99–299 kr for typical distances
        double base = 19 + distKm * 0.38;
        int variation = (trainId != null ? Math.abs(trainId.hashCode()) % 31 : 0) - 15;
        return roundToX9(Math.max(19, base + variation));
    }

    /** Full ordinary price (shown as strikethrough). */
    public int calculateOrdinaryPrice(double distKm, String trainId) {
        if (distKm <= 0) return 0;
        return roundToX9(basePrice(distKm, trainId) * 2.8);
    }

    public String calculatePrice(double distKm, String trainId) {
        int p = basePrice(distKm, trainId);
        return p > 0 ? "från " + p + " kr" : "";
    }

    public String calculatePriceLugn(double distKm, String trainId) {
        int p = basePrice(distKm, trainId);
        return p > 0 ? "från " + roundToX9(p * 1.18) + " kr" : "";
    }

    public String calculatePrice1Klass(double distKm, String trainId) {
        int p = basePrice(distKm, trainId);
        return p > 0 ? "från " + roundToX9(p * 1.45) + " kr" : "";
    }

    /** Seats left: 1–5, deterministic per trainId. */
    public int calculateSeatsLeft(String trainId) {
        int hash = trainId != null ? Math.abs(trainId.hashCode()) : 0;
        return (hash % 5) + 1;
    }

    /** Estimated travel time in minutes, rounded to nearest 5. */
    public int estimateTravelMinutes(double distKm, int avgSpeedKmh) {
        if (distKm <= 0 || avgSpeedKmh <= 0) return 0;
        int minutes = (int) Math.ceil((distKm / avgSpeedKmh) * 60) + 10;
        return ((minutes + 4) / 5) * 5;
    }
}
