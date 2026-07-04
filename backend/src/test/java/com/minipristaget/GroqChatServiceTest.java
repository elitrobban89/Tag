package com.minipristaget;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tester för GroqChatServices rena logik: meddelandelistan (systemprompt,
 * avgångskontext, historiktrimning) och konfigurationskollen. Inga HTTP-anrop.
 */
class GroqChatServiceTest {

    private final GroqChatService service = new GroqChatService();

    private static Map<String, String> msg(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    @Test
    void systempromptenLiggerForstOchInnehallerTagreglerna() {
        List<Map<String, String>> msgs = service.buildMsgList(List.of(msg("user", "hej")), null);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).get("role")).isEqualTo("system");
        assertThat(msgs.get(0).get("content"))
                .contains("MiniPrisTåget")
                .contains("X2000")
                .doesNotContain("Aktuell sökning");
        assertThat(msgs.get(1).get("content")).isEqualTo("hej");
    }

    @Test
    void avgangskontextBifogasISystemprompten() {
        List<Map<String, String>> msgs = service.buildMsgList(
                List.of(msg("user", "vilken avgång är billigast?")),
                "Stockholm → Göteborg 08:15, från 199 kr, 3 platser kvar");
        assertThat(msgs.get(0).get("content"))
                .contains("Aktuell sökning")
                .contains("08:15");
    }

    @Test
    void blankKontextUtelamnas() {
        List<Map<String, String>> msgs = service.buildMsgList(List.of(msg("user", "hej")), "   ");
        assertThat(msgs.get(0).get("content")).doesNotContain("Aktuell sökning");
    }

    @Test
    void historikTrimmasTillSenaste8Meddelanden() {
        List<Map<String, String>> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) history.add(msg("user", "fråga " + i));
        List<Map<String, String>> msgs = service.buildMsgList(history, null);
        assertThat(msgs).hasSize(9); // system + 8 senaste
        assertThat(msgs.get(1).get("content")).isEqualTo("fråga 5");   // äldsta kvar
        assertThat(msgs.get(8).get("content")).isEqualTo("fråga 12");  // senaste sist
    }

    @Test
    void utanApiNyckelArTjanstenOkonfigurerad() {
        assertThat(service.isConfigured()).isFalse(); // @Value injiceras inte utan Spring
    }
}
