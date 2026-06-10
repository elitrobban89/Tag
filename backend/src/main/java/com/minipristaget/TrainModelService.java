package com.minipristaget;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class TrainModelService {

    public record TrainModelInfo(
        String name, String color, int avgSpeedKmh, String description, String imageUrl) {}

    private static final TrainModelInfo DEFAULT =
        new TrainModelInfo("Regionaltåg", "#6b7280", 100, "Regionaltåg",
                           "/images/train-sj-regional.png");

    private static final Map<String, TrainModelInfo> MODELS = Map.ofEntries(
        Map.entry("SJ",         new TrainModelInfo("SJ X2000",          "#CC0000", 160,
                                    "Snabbtåg · 200 km/h max",    "/images/train-sj-x2000.jpg")),
        Map.entry("MTRX",       new TrainModelInfo("X74",              "#1a5e35", 175,
                                    "X74 · 200 km/h, nordisk design", "/images/train-vy.jpg")),
        Map.entry("VR",         new TrainModelInfo("X74",              "#1a5e35", 175,
                                    "X74 (VR) · 200 km/h max",    "/images/train-vy.jpg")),
        Map.entry("MTRXEX",     new TrainModelInfo("X74",              "#1a5e35", 175,
                                    "X74 · 200 km/h max",          "/images/train-vy.jpg")),
        Map.entry("VASTTRAF",   new TrainModelInfo("Coradia Nordic",   "#0055a5", 110,
                                    "Regionaltåg västkusten",      "/images/train-sj-regional.png")),
        Map.entry("Ö-TÅG",      new TrainModelInfo("Öresundståg X31", "#004EA8", 120,
                                    "Regionaltåg Skåne",           "/images/train-sj-regional.png")),
        Map.entry("SKANE",      new TrainModelInfo("Öresundståg X31", "#004EA8", 120,
                                    "Regionaltåg Skåne",           "/images/train-sj-regional.png")),
        Map.entry("SNALLTAGET", new TrainModelInfo("Snälltåget",       "#1a1a2e", 150,
                                    "Fjärrtåg & nattåg",           "/images/train-sj-fast.png")),
        Map.entry("MTR",        new TrainModelInfo("MTR Express",      "#e85d00", 155,
                                    "Stockholm–Göteborg",          "/images/train-sj-fast.png"))
    );

    public TrainModelInfo getModel(String operator) {
        if (operator == null || operator.isBlank()) return DEFAULT;
        return MODELS.getOrDefault(operator.trim().toUpperCase(), DEFAULT);
    }

    /** Fake price based on distance + deterministic variation per trainId. */
    public String calculatePrice(double distKm, String trainId) {
        if (distKm <= 0) return "";
        double base = 39 + distKm * 1.15;
        int variation = (trainId != null ? Math.abs(trainId.hashCode()) % 41 : 0) - 20;
        int price = Math.max(39, Math.min(1299, (int)(base + variation)));
        price = ((price / 10) * 10) + 9;
        return "från " + price + " kr";
    }

    /** Estimated travel time in minutes, rounded to nearest 5. */
    public int estimateTravelMinutes(double distKm, int avgSpeedKmh) {
        if (distKm <= 0 || avgSpeedKmh <= 0) return 0;
        int minutes = (int) Math.ceil((distKm / avgSpeedKmh) * 60) + 10;
        return ((minutes + 4) / 5) * 5;
    }
}
