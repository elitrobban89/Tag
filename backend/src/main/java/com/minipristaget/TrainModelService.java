package com.minipristaget;

import org.springframework.stereotype.Service;
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
        // Bild: ingen egen SJ 3000-bild finns i repot — kör den generiska snabbtågsbilden
        // tills en licensierad X55-bild läggs i /images/train-sj-3000.jpg.
        Map.entry("SJ3000",     new TrainModelInfo("SJ 3000",         "#CC0000", 165,
                                    "X55 · 200 km/h, bistro & plant insteg",
                                    "/images/train-sj-fast.png",   true,  "sj3000"))
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

    /** Som {@link #getModel(String)}, men väljer SJ 3000 på de sträckor X55 trafikerar. */
    public TrainModelInfo getModel(String operator, String destination) {
        return getModel(operator, destination, null);
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
