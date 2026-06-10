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
                                    "Stockholm–Göteborg",           "/images/train-sj-fast.png",   true,  "mtr"))
    );

    public TrainModelInfo getModel(String operator) {
        if (operator == null || operator.isBlank()) return DEFAULT;
        return MODELS.getOrDefault(operator.trim().toUpperCase(), DEFAULT);
    }

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
