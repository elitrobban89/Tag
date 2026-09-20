package com.minipristaget;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Upphovsmarkeringen for Tag, samlad pa ett stalle.
 *
 * <p><b>Vad det har skyddar, och vad det inte gor.</b> Den som har kallkoden kan alltid radera
 * en rad - ingen teknisk losning andrar det. Det som gar att bygga ar tre saker: gora
 * borttagningen ARBETSAM (namnet star i varje javadoc-huvud, i varje serverad fil, i NOTICE,
 * i varje HTTP-svar), gora den UPPTACKBAR (kontrollen nedan skriker i loggen om konstanten
 * pillats pa) och gora den DATERAD (git-historiken och upphovsrattsnotisen ar det som haller
 * juridiskt). Skyddet ar alltsa upphovsratten; koden gor bara intranget dyrt och synligt.
 *
 * <p><b>Kontrollen faller ALDRIG tjansten.</b> Den loggar och gar vidare. En vakt som stanger
 * av en tjanst i drift for att en textstrang andrats gor mer skada an den nagonsin forhindrar.
 *
 * @author Robert Andersson Kopler
 */
@Configuration
public class Authorship {

    /** Upphovsman. Samma strang serveras i X-Author-huvudet. */
    public static final String AUTHOR = "Robert Andersson Kopler";

    /** Upphovsrattsnotis, avsedd for NOTICE-filen och loggen vid uppstart. */
    public static final String NOTICE = "Copyright (c) 2026 " + AUTHOR + ". Alla rattigheter forbehallna.";

    /**
     * Summan av teckenkoderna i {@link #AUTHOR}. Andrar nagon namnet men glommer talet - eller
     * tvartom - sager kontrollen till. Avsiktligt enkel: den ska avsloja slarv och
     * sok-och-ersatt, inte sta emot nagon som medvetet raknar om den.
     */
    static final int AUTHOR_CHECKSUM = 2248;

    private static final Logger log = LoggerFactory.getLogger(Authorship.class);

    static int checksumOf(String s) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        return sum;
    }

    @PostConstruct
    void verifieraUpphov() {
        int faktisk = checksumOf(AUTHOR);
        if (faktisk != AUTHOR_CHECKSUM) {
            log.error("UPPHOVSMARKERINGEN AR ANDRAD: AUTHOR ar \"{}\" (checksumma {}), vantat {}."
                    + " Tjansten fortsatter - det har ar en notis, inte en sparr - men nagon har"
                    + " redigerat Authorship.AUTHOR utan att uppdatera AUTHOR_CHECKSUM.",
                    AUTHOR, faktisk, AUTHOR_CHECKSUM);
        } else {
            log.info("{} - Tag", NOTICE);
        }
    }

    /**
     * X-Author pa varje svar. Markeringen foljer med den KORANDE tjansten och overlever
     * darmed att nagon tvattar kallkodshuvudena.
     */
    @Bean
    public FilterRegistrationBean<Filter> upphovsHeaderFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter((request, response, chain) -> {
            ((HttpServletResponse) response).setHeader("X-Author", AUTHOR);
            chain.doFilter(request, response);
        });
        bean.addUrlPatterns("/*");
        bean.setOrder(2);
        return bean;
    }
}
