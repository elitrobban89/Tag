package com.minipristaget;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class WebController {

    @Autowired
    private TrafikverketService trafikverketService;

    // ── Startsida ─────────────────────────────────────────────────
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("form", new SearchFormRequest());
        return "index";
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

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }
}
