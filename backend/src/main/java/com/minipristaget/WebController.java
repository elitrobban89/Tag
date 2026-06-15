package com.minipristaget;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Controller
public class WebController {

    @Autowired
    private TrafikverketService trafikverketService;

    @Autowired
    private GroqChatService groqChatService;

    private static final int CHAT_RATE_LIMIT = 10;
    private static final long CHAT_WINDOW_MS  = 60_000L;
    private final ConcurrentHashMap<String, Deque<Long>> chatTimestamps = new ConcurrentHashMap<>();

    // ── Startsida ─────────────────────────────────────────────────
    @GetMapping("/")
    public String index(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        model.addAttribute("form", new SearchFormRequest());
        return "index";
    }

    // ── Stationsautocomplete ──────────────────────────────────────
    @GetMapping("/api/stations")
    @ResponseBody
    public ResponseEntity<?> stations(@RequestParam(defaultValue = "") String q) {
        if (q.length() < 2) return ResponseEntity.ok(List.of());
        try {
            String lower = q.toLowerCase();
            List<Map<String,String>> matches = trafikverketService.getAllStations().stream()
                .filter(s -> s.getName().toLowerCase().contains(lower))
                .sorted((a, b) -> {
                    boolean aStarts = a.getName().toLowerCase().startsWith(lower);
                    boolean bStarts = b.getName().toLowerCase().startsWith(lower);
                    if (aStarts && !bStarts) return -1;
                    if (!aStarts && bStarts) return 1;
                    return a.getName().compareTo(b.getName());
                })
                .limit(8)
                .map(s -> Map.of("name", s.getName()))
                .collect(Collectors.toList());
            return ResponseEntity.ok(matches);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    // ── Närmaste station via GPS-koordinater (AJAX) ───────────────
    @GetMapping("/nearest-station")
    @ResponseBody
    public ResponseEntity<?> nearestStation(@RequestParam double lat, @RequestParam double lon) {
        try {
            Optional<TrainStation> station = trafikverketService.findNearestStation(lat, lon);
            if (station.isPresent()) {
                return ResponseEntity.ok(Map.of(
                    "name",      station.get().getName(),
                    "signature", station.get().getSignature()
                ));
            }
            return ResponseEntity.ok(Map.of("name", "", "signature", ""));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Sök avgångar ─────────────────────────────────────────────
    @PostMapping("/search")
    public String search(@ModelAttribute("form") SearchFormRequest form, Model model) {
        model.addAttribute("form", form);

        if (form.getFrom().isBlank()) {
            model.addAttribute("error", "Ange en startstation.");
            return "index";
        }

        try {
            Optional<TrainStation> fromStation = trafikverketService.findStationByName(form.getFrom());
            if (fromStation.isEmpty()) {
                model.addAttribute("error", "Hittade ingen station för: " + form.getFrom());
                return "index";
            }

            LocalDate date = parseDate(form.getDate());
            List<TrainDeparture> departures = trafikverketService.getDepartures(
                fromStation.get().getSignature(), form.getTo(), date);

            model.addAttribute("departures",   departures);
            model.addAttribute("fromName",     fromStation.get().getName());
            model.addAttribute("toName",       form.getTo().isBlank() ? "Alla destinationer" : form.getTo());
            model.addAttribute("date",         date.toString());

            if ("tur".equals(form.getTripType()) && !form.getTo().isBlank() && !form.getReturnDate().isBlank()) {
                Optional<TrainStation> toStation = trafikverketService.findStationByName(form.getTo());
                if (toStation.isPresent()) {
                    LocalDate returnDate = parseDate(form.getReturnDate());
                    List<TrainDeparture> returnDepartures = trafikverketService.getDepartures(
                        toStation.get().getSignature(), form.getFrom(), returnDate);
                    model.addAttribute("returnDepartures", returnDepartures);
                    model.addAttribute("returnDate",       returnDate.toString());
                    model.addAttribute("returnFromName",   toStation.get().getName());
                }
            }

        } catch (Exception e) {
            model.addAttribute("error", "Kunde inte hämta avgångar: " + e.getMessage());
            return "index";
        }

        return "results";
    }

    // ── JSON API för AJAX-sökning (inga sidnavigering) ───────────
    @PostMapping("/api/search")
    @ResponseBody
    public ResponseEntity<?> apiSearch(@RequestBody SearchFormRequest form) {
        if (form.getFrom() == null || form.getFrom().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ange en startstation."));
        }
        try {
            Optional<TrainStation> fromStation = trafikverketService.findStationByName(form.getFrom());
            if (fromStation.isEmpty()) {
                return ResponseEntity.badRequest().body(
                    Map.of("error", "Hittade ingen station för: " + form.getFrom()));
            }
            LocalDate date = parseDate(form.getDate());
            List<TrainDeparture> departures = trafikverketService.getDepartures(
                fromStation.get().getSignature(), form.getTo(), date);

            // If no trains left today, automatically show tomorrow's departures
            boolean autoTomorrow = false;
            if (departures.isEmpty() && date.equals(LocalDate.now(ZoneId.of("Europe/Stockholm")))) {
                date = date.plusDays(1);
                departures = trafikverketService.getDepartures(
                    fromStation.get().getSignature(), form.getTo(), date);
                autoTomorrow = true;
            }

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("fromName",     fromStation.get().getName());
            result.put("toName",       (form.getTo() == null || form.getTo().isBlank())
                                           ? "Alla destinationer" : form.getTo());
            result.put("date",         date.toString());
            result.put("autoTomorrow", autoTomorrow);
            result.put("departures",   departures);

            if ("tur".equals(form.getTripType())
                    && form.getTo() != null && !form.getTo().isBlank()
                    && form.getReturnDate() != null && !form.getReturnDate().isBlank()) {
                Optional<TrainStation> toStation = trafikverketService.findStationByName(form.getTo());
                if (toStation.isPresent()) {
                    LocalDate returnDate = parseDate(form.getReturnDate());
                    List<TrainDeparture> returnDeps = trafikverketService.getDepartures(
                        toStation.get().getSignature(), form.getFrom(), returnDate);
                    result.put("returnFromName",   toStation.get().getName());
                    result.put("returnDate",       returnDate.toString());
                    result.put("returnDepartures", returnDeps);
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Kunde inte hämta avgångar: " + e.getMessage()));
        }
    }

    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<?> chat(@RequestBody Map<String, Object> req, HttpServletRequest httpReq) {
        String ip = Optional.ofNullable(httpReq.getHeader("X-Forwarded-For"))
                .filter(h -> !h.isBlank()).map(h -> h.split(",")[0].trim())
                .orElse(httpReq.getRemoteAddr());

        long now = System.currentTimeMillis();
        Deque<Long> times = chatTimestamps.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > CHAT_WINDOW_MS) times.pollFirst();
            if (times.size() >= CHAT_RATE_LIMIT)
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body(Map.of("error", "För många frågor — vänta en minut och försök igen."));
            times.addLast(now);
        }

        if (!groqChatService.isConfigured())
            return ResponseEntity.ok(Map.of("reply", "AI-assistenten är inte konfigurerad ännu."));

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> messages = (List<Map<String, String>>) req.get("messages");
            String context = (String) req.get("context");
            if (messages == null || messages.isEmpty())
                return ResponseEntity.ok(Map.of("reply", "Inga meddelanden."));
            return ResponseEntity.ok(Map.of("reply", groqChatService.chat(messages, context)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
