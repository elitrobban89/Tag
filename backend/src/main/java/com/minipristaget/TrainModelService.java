package com.minipristaget;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** @author Robert Andersson Kopler */
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
        Map.entry("MTRX",       new TrainModelInfo("VR Snabbtåg X74", "#1a5e35", 175,
                                    "Stadler FLIRT · 200 km/h (fd MTRX)","/images/train-vy.jpg",        true,  "x74")),
        Map.entry("VR",         new TrainModelInfo("VR Snabbtåg X74", "#1a5e35", 175,
                                    "X74 (VR) · 200 km/h max",     "/images/train-vy.jpg",        true,  "x74")),
        Map.entry("MTRXEX",     new TrainModelInfo("VR Snabbtåg X74", "#1a5e35", 175,
                                    "X74 · 200 km/h max",           "/images/train-vy.jpg",        true,  "x74")),
        Map.entry("VASTTRAF",   new TrainModelInfo("X61 Västtåg",     "#0055a5", 110,
                                    "Regionaltåg västkusten",       "/images/train-sj-regional.png", false, "none")),
        Map.entry("Ö-TÅG",      new TrainModelInfo("Öresundståg X31K","#004EA8", 120,
                                    "X31K Contessa · 180 km/h · 229 platser", "/images/train-oresundstag.jpg", false, "none")),
        // SKANE är Pågatågen (Öresundståg har egen operatörskod Ö-TÅG) — hette förut Öresundståg
        Map.entry("SKANE",      new TrainModelInfo("Pågatåg X61",     "#7c3aed", 105,
                                    "Regionaltåg i Skåne",          "/images/train-oresundstag.jpg", false, "none")),
        Map.entry("SNALLTAGET", new TrainModelInfo("Snälltåget",      "#1a1a2e", 150,
                                    "Fjärrtåg & nattåg",            "/images/train-sj-fast.png",   true,  "snalltaget")),
        // Trafikverkets kod är "SNÄLL" — "SNALLTAGET" ovan träffade aldrig
        Map.entry("SNÄLL",      new TrainModelInfo("Snälltåget",      "#1a1a2e", 150,
                                    "Fjärrtåg & nattåg",            "/images/train-sj-fast.png",   true,  "snalltaget")),
        Map.entry("ATRAIN",     new TrainModelInfo("Arlanda Express X3", "#eab308", 150,
                                    "Stockholm C – Arlanda på ca 20 min", "/images/train-sj-fast.png", false, "none")),
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

        // Ersättningsbuss: Trafikverket annonserar den som en avgång med trafiktyp "Buss" —
        // den visades förut som ett tåg.
        if (isBus(dep.getTypeOfTraffic())) return BUSS;

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

        TrainModelInfo produkt = fromProduct(dep.getProductInformation(), dep.getTypeOfTraffic());
        if (produkt != null) return produkt;

        return getModel(dep.getOperator(), dep.getDestination(), dep.getProductInformation());
    }

    // ── Alla bolag och produkter i Trafikverkets data ─────────────────────────
    //
    // Inventerat 2026-09-27 över 38 stationer ett helt dygn: 19 kombinationer av TrainOwner
    // och ProductInformation. Förut kändes ~5 igen och resten blev "Regionaltåg" — även
    // Mälartåg (474 avgångar/dygn), SL Pendeltåg, Pågatåg och Västtåg. Fordonsbeteckning
    // (X60, X61 …) anges BARA där bolaget kör en enda typ; annars bara produktnamnet, eftersom
    // Trafikverket aldrig säger vilket fordon som går.
    // Bild: SJ Rc6 1407 med regionaltågsvagnar i Göteborg, Wikimedia Commons, foto G och J, CC BY 4.0.
    static final TrainModelInfo INTERCITY = new TrainModelInfo("SJ InterCity", "#CC0000", 140,
        "Rc6-lok + vagnar, 160 km/h", "/images/train-sj-rc6-regional.jpg", false, "none");
    static final TrainModelInfo NATTAG = new TrainModelInfo("SJ Nattåg", "#7c3aed", 105,
        "Sovvagn och liggvagn", "/images/train-sj-fast.png", false, "none");
    static final TrainModelInfo MALARTAG = new TrainModelInfo("Mälartåg", "#0d9488", 125,
        "Regionaltåg i Mälardalen", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo SL_PENDEL = new TrainModelInfo("SL Pendeltåg X60", "#db2777", 65,
        "Pendeltåg i Stockholmsregionen", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo PAGATAG = new TrainModelInfo("Pågatåg X61", "#7c3aed", 105,
        "Regionaltåg i Skåne", "/images/train-oresundstag.jpg", false, "none");
    static final TrainModelInfo PAGATAG_EXP = new TrainModelInfo("Pågatåg Express X61", "#7c3aed", 120,
        "Snabbare Pågatåg med färre stopp", "/images/train-oresundstag.jpg", false, "none");
    static final TrainModelInfo KROSATAG = new TrainModelInfo("Krösatåg", "#f59e0b", 105,
        "Regionaltåg i Småland", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo VASTTAG = new TrainModelInfo("Västtåg", "#0ea5e9", 110,
        "Regionaltåg i Västra Götaland", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo GBG_PENDEL = new TrainModelInfo("Västtågen pendeltåg", "#0ea5e9", 70,
        "Pendeltåg runt Göteborg", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo OSTGOTA = new TrainModelInfo("Östgötapendeln", "#0284c7", 105,
        "Regionaltåg i Östergötland", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo ARLANDA = new TrainModelInfo("Arlanda Express X3", "#eab308", 150,
        "Stockholm C – Arlanda på ca 20 min", "/images/train-sj-fast.png", false, "none");
    static final TrainModelInfo TIB = new TrainModelInfo("Tåg i Bergslagen", "#ea580c", 110,
        "Regionaltåg i Bergslagen", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo NORRTAG = new TrainModelInfo("Norrtåg X62", "#2563eb", 120,
        "Regionaltåg i Norrland", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo XTAGET = new TrainModelInfo("X-Tåget", "#059669", 110,
        "Regionaltåg Gävleborg–Dalarna", "/images/train-sj-regional.png", false, "none");
    static final TrainModelInfo VY = new TrainModelInfo("Vy Snabbtåg", "#e11d48", 130,
        "Göteborg – Oslo", "/images/train-sj-fast.png", false, "none");
    static final TrainModelInfo BUSS = new TrainModelInfo("Ersättningsbuss", "#64748b", 60,
        "Buss ersätter tåget på sträckan", "", false, "none");

    static boolean isBus(String typeOfTraffic) {
        return typeOfTraffic != null && typeOfTraffic.toLowerCase(java.util.Locale.ROOT).contains("buss");
    }

    /**
     * Fordon ur Trafikverkets produktnamn. {@code null} för SJ Snabbtåg (X2000/SJ 3000 avgörs
     * av tågnummer och destination i {@link #getModel(String, String, String)}) och för okända
     * produkter.
     */
    static TrainModelInfo fromProduct(String productInformation, String typeOfTraffic) {
        String p = productInformation == null ? "" : productInformation.toLowerCase(java.util.Locale.ROOT);
        if (p.isBlank()) return null;
        if (p.startsWith("sj intercity"))  return INTERCITY;
        if (p.startsWith("sj nattåg"))     return NATTAG;
        if (p.startsWith("sj regional"))   return REGIONAL_SJ;
        if (p.startsWith("mälartåg"))      return MALARTAG;
        if (p.startsWith("sl pendeltåg"))  return SL_PENDEL;
        if (p.startsWith("pågatågen exp")) return PAGATAG_EXP;
        if (p.startsWith("pågatåg"))       return PAGATAG;
        if (p.startsWith("krösatåg"))      return KROSATAG;
        if (p.startsWith("västtåg"))
            return typeOfTraffic != null && typeOfTraffic.toLowerCase(java.util.Locale.ROOT).contains("pendel")
                ? GBG_PENDEL : VASTTAG;
        if (p.startsWith("östgötapendel")) return OSTGOTA;
        if (p.equals("tib") || p.startsWith("tåg i bergslagen")) return TIB;
        if (p.startsWith("norrtåg"))       return NORRTAG;
        if (p.startsWith("x-tåget"))       return XTAGET;
        if (p.startsWith("vy snabbtåg"))   return VY;
        return null;
    }

    /**
     * Bolaget som det står på märket i avgångslistan. Produktnamnet i första hand (det är
     * vad resenären känner igen), operatörskoden i andra.
     */
    public static String operatorName(String operator, String productInformation, String typeOfTraffic) {
        if (isBus(typeOfTraffic)) return "Buss";
        String p = productInformation == null ? "" : productInformation.toLowerCase(java.util.Locale.ROOT);
        String o = operator == null ? "" : operator.trim().toUpperCase(java.util.Locale.ROOT);
        if (p.startsWith("sj") || o.equals("SJ"))                  return "SJ";
        if (p.startsWith("vr ") || o.startsWith("MTRX") || o.equals("VR")) return "VR";
        if (p.startsWith("vy") || o.equals("VY"))                  return "Vy";
        if (p.startsWith("öresundståg") || o.equals("Ö-TÅG"))      return "Öresundståg";
        if (p.startsWith("pågatåg") || o.equals("SKANE"))          return "Pågatåg";
        if (p.startsWith("mälartåg") || o.equals("MÄLAB"))         return "Mälartåg";
        if (p.startsWith("sl ") || o.equals("SLL"))                return "SL";
        if (p.startsWith("västtåg") || o.equals("VASTTRAF"))       return "Västtåg";
        if (p.startsWith("krösatåg") || o.equals("JLT"))           return "Krösatåg";
        if (p.startsWith("östgöta") || o.equals("ÖTRAF"))          return "Östgötapendeln";
        if (o.equals("ATRAIN"))                                    return "Arlanda Express";
        if (p.equals("tib") || o.equals("TIB"))                    return "Tåg i Bergslagen";
        if (p.startsWith("norrtåg") || o.equals("NORRT"))          return "Norrtåg";
        if (p.startsWith("x-tåget") || o.equals("XTRAFIK"))        return "X-Tåget";
        if (p.startsWith("snälltåget") || o.startsWith("SNÄLL") || o.equals("SNALLTAGET")) return "Snälltåget";
        return !p.isBlank() ? productInformation.split(" ")[0] : (operator == null || operator.isBlank() ? "Tåg" : operator);
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

    // Bild (2026-09-29): SJ X40 på Örebro C, en station på SJ Regional Stockholm–Göteborg.
    // Wikimedia Commons, foto AleWi, CC BY-SA 4.0. Trafikverket anger aldrig fordonet, och SJ
    // kör linjen både med X40-dubbeldäckare och Rc6-lok med 1980-talsvagnar — därför står
    // båda i beskrivningen och ingen av dem i namnet.
    private static final TrainModelInfo REGIONAL_SJ =
        new TrainModelInfo("SJ Regional", "#CC0000", 120, "X40-dubbeldäckare eller Rc6-loktåg · 160 km/h",
                           "/images/train-sj-x40.jpg", false, "none");

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

    // ── Priser som liknar de riktiga (2026-09-27) ─────────────────────────────
    //
    // Den gamla formeln (19 kr + 0,38 kr/km) gav Kungsbacka → Kastrup med Öresundståg ~120 kr;
    // biljetten kostar 508 kr i 2 klass. Regionaltåg har FASTA priser per sträcka (ingen
    // variation per avgång), medan SJ/VR/Vy prissätter efter efterfrågan — där varierar
    // MiniPriset per tåg. Avståndet är fågelvägen, så konstanterna är kalibrerade mot den.
    public enum Priskategori { SNABB, REGIONAL, NATT, SL, ARLANDA }

    public static Priskategori kategori(TrainModelInfo m) {
        String n = m == null ? "" : m.name();
        if (n.startsWith("SJ Nattåg"))                         return Priskategori.NATT;
        if (n.startsWith("SL "))                               return Priskategori.SL;
        if (n.startsWith("Arlanda Express"))                   return Priskategori.ARLANDA;
        if (n.startsWith("SJ") || n.startsWith("VR") || n.startsWith("Vy") || n.startsWith("Snälltåget"))
                                                               return Priskategori.SNABB;
        return Priskategori.REGIONAL;
    }

    /** 2 klass-priset (MiniPris) för kategorin. */
    int basePrice(double distKm, String trainId, Priskategori k) {
        if (distKm <= 0) return 0;
        int variation = (trainId != null ? Math.abs(trainId.hashCode()) % 61 : 0) - 30;
        return switch (k) {
            // Kungsbacka → Copenhagen Airport ≈ 210 km fågelväg → 30 + 2,25·210 = 502 → 509 kr
            case REGIONAL -> roundToX9(30 + distKm * 2.25);
            // Stockholm → Göteborg ≈ 400 km → ~359 kr MiniPris, ±30 kr per avgång
            case SNABB    -> roundToX9(99 + distKm * 0.65 + variation);
            case NATT     -> roundToX9(399 + distKm * 0.5 + variation);
            case SL       -> 45;       // SL:s enkelbiljett, samma oavsett sträcka
            case ARLANDA  -> 339;      // Arlanda Express enkel
        };
    }

    /** Ordinarie pris (överstruket): MiniPris-rabatten finns bara på SJ/VR/Vy/nattåg. */
    public int calculateOrdinaryPrice(double distKm, String trainId, Priskategori k) {
        int p = basePrice(distKm, trainId, k);
        if (p <= 0) return 0;
        return switch (k) {
            case SNABB, NATT -> roundToX9(p * 2.4);
            case REGIONAL    -> roundToX9(p * 1.25);   // köpt ombord / flexbiljett
            case SL, ARLANDA -> 0;                     // fast pris — inget att stryka över
        };
    }

    public String calculatePrice(double distKm, String trainId, Priskategori k) {
        int p = basePrice(distKm, trainId, k);
        return p > 0 ? "från " + p + " kr" : "";
    }

    public String calculatePriceLugn(double distKm, String trainId, Priskategori k) {
        if (k == Priskategori.SL || k == Priskategori.ARLANDA) return "";   // ingen Lugn-avdelning
        int p = basePrice(distKm, trainId, k);
        return p > 0 ? "från " + roundToX9(p * 1.18) + " kr" : "";
    }

    public String calculatePrice1Klass(double distKm, String trainId, Priskategori k) {
        if (k == Priskategori.SL || k == Priskategori.ARLANDA) return "";   // ingen 1 klass
        int p = basePrice(distKm, trainId, k);
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
