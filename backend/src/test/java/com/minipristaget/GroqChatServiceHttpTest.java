package com.minipristaget;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP-felvägstester för GroqChatService: 429/401/5xx och svar utan content.
 * Tjänsten pekas mot en lokal stubbserver via groq.api.url — inga externa anrop.
 */
class GroqChatServiceHttpTest {

    private HttpServer server;
    private GroqChatService service;

    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        server.start();

        service = new GroqChatService();
        ReflectionTestUtils.setField(service, "apiKey", "test-nyckel");
        ReflectionTestUtils.setField(service, "model", "openai/gpt-oss-120b");
        ReflectionTestUtils.setField(service, "groqUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String chat() throws Exception {
        return service.chat(List.of(Map.of("role", "user", "content", "hej")), null);
    }

    @Test
    void lyckatSvarPlockasUrChoices() throws Exception {
        status = 200;
        body = "{\"choices\":[{\"message\":{\"content\":\"X2000 gar 08:15.\"}}]}";
        assertThat(chat()).isEqualTo("X2000 gar 08:15.");
    }

    @Test
    void svarUtanContentGerStandardtext() throws Exception {
        status = 200;
        body = "{\"choices\":[]}";
        assertThat(chat()).isEqualTo("Inget svar.");
    }

    @Test
    void rateLimit429GerVantaMeddelande() {
        status = 429;
        body = "{\"error\":{\"message\":\"Rate limit reached\"}}";
        assertThatThrownBy(this::chat).hasMessageContaining("För många frågor");
    }

    @Test
    void felaktigNyckel401GerKonfigurationsfel() {
        status = 401;
        body = "{\"error\":{\"message\":\"Invalid API Key\"}}";
        assertThatThrownBy(this::chat).hasMessageContaining("inte korrekt konfigurerad");
    }

    @Test
    void serverfel503GerFelmeddelandeMedStatuskod() {
        status = 503;
        body = "";
        assertThatThrownBy(this::chat).hasMessageContaining("fel 503");
    }
}
