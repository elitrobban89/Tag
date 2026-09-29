package com.minipristaget;

import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Siffrorna till uppstartsskärmen — samma källor som appen själv använder.
 *
 * <p>Raderna på splashen ska visa vad som FAKTISKT kör och hur det ser ut på spåret just nu,
 * inte avskrivna strängar. Java- och Spring Boot-versionen läses ur den körande JVM:en,
 * modellen ur Groq-tjänstens konfiguration och trafikläget ur Trafikverket (cachat en minut).
 *
 * <p>Varje del är fail-soft: svarar Trafikverket inte utelämnas trafiknycklarna, och
 * splashen står kvar med sin beskrivande text på just den raden i stället för att hitta på.
 *
 * @author Robert Andersson Kopler
 */
@RestController
public class SplashController {

    private final TrafikverketService trafikverket;
    private final GroqChatService groq;
    private final TrainLayoutService layouts;

    public SplashController(TrafikverketService trafikverket, GroqChatService groq, TrainLayoutService layouts) {
        this.trafikverket = trafikverket;
        this.groq = groq;
        this.layouts = layouts;
    }

    @CrossOrigin
    @GetMapping("/api/splash")
    public Map<String, Object> splash() {
        Map<String, Object> ut = new LinkedHashMap<>();
        ut.put("java", System.getProperty("java.version", ""));
        ut.put("springBoot", String.valueOf(SpringBootVersion.getVersion()));
        ut.put("groqModel", groq.modelName());
        ut.put("groqOnline", groq.isConfigured());
        ut.put("vagnsskisser", layouts.allIds().size());
        // Render sätter de här i varje deploy — splashens autodeploy-bricka visar vilken commit som kör.
        String commit = System.getenv("RENDER_GIT_COMMIT");
        if (commit != null && !commit.isBlank()) ut.put("deployCommit", commit.substring(0, Math.min(7, commit.length())));
        String branch = System.getenv("RENDER_GIT_BRANCH");
        if (branch != null && !branch.isBlank()) ut.put("deployBranch", branch);

        try {
            ut.put("stationer", trafikverket.getAllStations().size());
        } catch (Exception e) { /* stationsraden visar sin text utan tal */ }

        TrafikverketService.TrafikLage t = trafikverket.getTrafikLage();
        if (t != null) {
            ut.put("tagITrafik", t.tagITrafik());
            ut.put("avgangarNastaTimme", t.avgangarNastaTimme());
            ut.put("bolag", t.bolag());
            ut.put("installda", t.installda());
            if (t.punktlighetProcent() != null) ut.put("punktlighet", t.punktlighetProcent());
        }
        return ut;
    }
}
