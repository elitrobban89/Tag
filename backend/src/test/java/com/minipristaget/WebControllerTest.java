package com.minipristaget;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-lagertester för WebController: autocomplete-filtrering, valideringsfel,
 * felformat och chattens rate limit. Tjänsterna mockas — inga externa anrop.
 */
@WebMvcTest(WebController.class)
// Vagnsskissen är ren logik utan externa anrop — kör den skarpt i stället för mockad,
// så att /api/train-layout och skisstexten i chattkontexten testas på riktigt.
@org.springframework.context.annotation.Import(TrainLayoutService.class)
class WebControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TrafikverketService trafikverketService;

    @MockBean
    private GroqChatService groqChatService;

    private static TrainDeparture departure(String dest, String time) {
        TrainDeparture d = new TrainDeparture();
        d.setDestination(dest);
        d.setDepartureTime(time);
        d.setOperator("SJ");
        return d;
    }

    // --- /api/stations ---

    @Test
    void forKortSokfragaGerTomLista() throws Exception {
        mvc.perform(get("/api/stations").param("q", "s"))
           .andExpect(status().isOk())
           .andExpect(content().json("[]"));
    }

    @Test
    void prefixtraffarSorterasForeInnehallstraffar() throws Exception {
        when(trafikverketService.getAllStations()).thenReturn(List.of(
                new TrainStation("Cst", "Stockholm Central", 59.33, 18.06),
                new TrainStation("Hms", "Holmsund", 63.70, 20.35),
                new TrainStation("G",   "Göteborg Central", 57.71, 11.97)));

        mvc.perform(get("/api/stations").param("q", "holm"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].name").value("Holmsund"))
           .andExpect(jsonPath("$[1].name").value("Stockholm Central"));
    }

    // --- /api/search ---

    @Test
    void sokningUtanStartstationGer400() throws Exception {
        mvc.perform(post("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Ange en startstation."));
    }

    @Test
    void okandStartstationGer400MedStationsnamnet() throws Exception {
        when(trafikverketService.findStationByName("Ankeborg")).thenReturn(Optional.empty());

        mvc.perform(post("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"Ankeborg\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Hittade ingen station för: Ankeborg"));
    }

    @Test
    void lyckadSokningReturnerarAvgangarOchStationsnamn() throws Exception {
        when(trafikverketService.findStationByName("Stockholm"))
                .thenReturn(Optional.of(new TrainStation("Cst", "Stockholm Central", 59.33, 18.06)));
        when(trafikverketService.getDepartures(eq("Cst"), anyString(), any()))
                .thenReturn(List.of(departure("Göteborg C", "08:15")));

        mvc.perform(post("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"Stockholm\",\"to\":\"\",\"date\":\"2026-07-08\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.fromName").value("Stockholm Central"))
           .andExpect(jsonPath("$.toName").value("Alla destinationer"))
           .andExpect(jsonPath("$.autoTomorrow").value(false))
           .andExpect(jsonPath("$.departures[0].destination").value("Göteborg C"))
           .andExpect(jsonPath("$.departures[0].departureTime").value("08:15"));
    }

    @Test
    void tomDagsokningIdagVisarMorgondagensAvgangarAutomatiskt() throws Exception {
        when(trafikverketService.findStationByName("Stockholm"))
                .thenReturn(Optional.of(new TrainStation("Cst", "Stockholm Central", 59.33, 18.06)));
        // Idag: tomt. Imorgon: en avgång.
        when(trafikverketService.getDepartures(eq("Cst"), anyString(), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(departure("Malmö C", "06:02")));

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Stockholm")).toString();
        mvc.perform(post("/api/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"Stockholm\",\"date\":\"" + today + "\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.autoTomorrow").value(true))
           .andExpect(jsonPath("$.departures[0].destination").value("Malmö C"));
    }

    // --- /api/chat ---

    @Test
    void okonfigureradChattGerVanligtSvar() throws Exception {
        when(groqChatService.isConfigured()).thenReturn(false);

        mvc.perform(post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hej\"}]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reply").value("AI-assistenten är inte konfigurerad ännu."));
    }

    @Test
    void chattSvararMedTjanstensSvar() throws Exception {
        when(groqChatService.isConfigured()).thenReturn(true);
        when(groqChatService.chat(any(), any())).thenReturn("X2000 går 08:15.");

        mvc.perform(post("/api/chat")
                .header("X-Forwarded-For", "10.1.1.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"när går tåget?\"}]}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.reply").value("X2000 går 08:15."));
    }

    @Test
    void chattensRateLimitGer429EfterTioAnrop() throws Exception {
        when(groqChatService.isConfigured()).thenReturn(true);
        when(groqChatService.chat(any(), any())).thenReturn("svar");

        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hej\"}]}";
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/chat")
                    .header("X-Forwarded-For", "10.2.2.2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
               .andExpect(status().isOk());
        }
        mvc.perform(post("/api/chat")
                .header("X-Forwarded-For", "10.2.2.2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isTooManyRequests())
           .andExpect(jsonPath("$.error").exists());
    }

    // --- vagnsskissen ---

    @Test
    void vagnsskissenGerVagnarOchFaciliteter() throws Exception {
        mvc.perform(get("/api/train-layout").param("layout", "sj3000"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.trainName").value("SJ 3000 (X55)"))
           .andExpect(jsonPath("$.wagons.length()").value(4))
           .andExpect(jsonPath("$.facilities[?(@.type=='BISTRO')].wagon").value(3));
    }

    @Test
    void okandVagnsskissGer404OchListningUtanParameter() throws Exception {
        mvc.perform(get("/api/train-layout").param("layout", "finns-inte"))
           .andExpect(status().isNotFound())
           .andExpect(jsonPath("$.error").exists());

        mvc.perform(get("/api/train-layout"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.layouts").isArray());
    }

    @Test
    void chattenFarSkissOchPlatsfaktaIKontexten() throws Exception {
        when(groqChatService.isConfigured()).thenReturn(true);
        when(groqChatService.chat(any(), anyString())).thenReturn("svar");

        mvc.perform(post("/api/chat")
                .header("X-Forwarded-For", "10.3.3.3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"var finns toaletten?\"}],"
                       + "\"context\":\"Avgång 15:19\",\"layout\":\"sj3000\",\"seat\":\"3-2C\"}"))
           .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<String> ctx = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(groqChatService).chat(any(), ctx.capture());
        assertThat(ctx.getValue())
                .contains("Avgång 15:19")        // ursprungskontexten är kvar
                .contains("Vagnsskiss SJ 3000")  // skissen tillagd
                .contains("Vald plats: vagn 3, rad 2C")
                .contains("Närmaste toalett");
    }
}
