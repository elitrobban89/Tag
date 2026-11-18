package com.minipristaget;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Webbsidor som får anropa chatten och resmålsförslagen från en besökares webbläsare.
 *
 * <p>Förr stod {@code @CrossOrigin} utan lista, alltså alla sajter: vem som helst kunde
 * bygga in tågassistenten på sin egen sida och låta sina besökare tömma Groq-kvoten.
 * Appens egen adress måste stå med fast anropen kommer från samma sida: Render avslutar
 * TLS före appen, så Tomcat ser {@code http} medan webbläsarens Origin är {@code https},
 * och Spring behandlar då anropet som cross-origin.
 *
 * <p>CORS stoppar bara webbläsare. Skript och curl skickar vad de vill, och mot dem är det
 * rate limiten som skyddar.
 *
 * @author Robert Andersson Kopler
 */
@Configuration
public class ChatCors implements WebMvcConfigurer {

    static final String[] ORIGINS = {
            "https://tag-k5we.onrender.com",
            "https://elitrobban.se",
            "https://www.elitrobban.se",
            "http://localhost:[*]"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        for (String path : new String[] {"/api/chat", "/api/chat/stream", "/suggest"}) {
            registry.addMapping(path).allowedOriginPatterns(ORIGINS)
                    .allowedMethods("POST", "OPTIONS").allowedHeaders("*");
        }
    }
}
