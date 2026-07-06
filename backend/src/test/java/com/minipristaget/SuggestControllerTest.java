package com.minipristaget;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-lagertester för SuggestController: kategorivalidering, health och
 * rate limit. Groq-lyckoflödet testas inte här — det kräver riktigt HTTP-anrop.
 */
@WebMvcTest(SuggestController.class)
class SuggestControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private TrafikverketService trafikverketService;

    @Test
    void healthSvararOk() throws Exception {
        mvc.perform(get("/health"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void ogiltigKategoriGer400() throws Exception {
        mvc.perform(post("/suggest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"Fjäll\",\"from\":\"Stockholm\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Ogiltig kategori"));
    }

    @Test
    void saknadKategoriGer400() throws Exception {
        mvc.perform(post("/suggest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"Stockholm\"}"))
           .andExpect(status().isBadRequest())
           .andExpect(jsonPath("$.error").value("Ogiltig kategori"));
    }

    @Test
    void rateLimitGer429EfterTioAnrop() throws Exception {
        String body = "{\"category\":\"Fjäll\"}"; // ogiltig kategori räcker — spärren ligger först
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/suggest")
                    .header("X-Forwarded-For", "10.3.3.3")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
               .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/suggest")
                .header("X-Forwarded-For", "10.3.3.3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
           .andExpect(status().isTooManyRequests())
           .andExpect(jsonPath("$.error").exists());
    }
}
