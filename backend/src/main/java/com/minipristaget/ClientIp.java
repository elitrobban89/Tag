package com.minipristaget;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Besökarens IP för rate limiting.
 *
 * <p>Läser {@code CF-Connecting-IP}, inte {@code X-Forwarded-For}. Render ligger bakom
 * Cloudflare, och Cloudflare skriver alltid över {@code CF-Connecting-IP} med den IP som
 * faktiskt anslöt. {@code X-Forwarded-For} släpps däremot igenom som klienten skickade den
 * (Render lägger bara till sin egen post på slutet), så den första posten var fritt vald av
 * anroparen, och en ny påhittad IP per anrop kringgick chattens och förslagens rate limit.
 *
 * <p>Saknas headern (lokal körning, tester) används anslutningens egen adress.
 *
 * @author Robert Andersson Kopler
 */
final class ClientIp {

    private ClientIp() {}

    static String of(HttpServletRequest request) {
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        return request.getRemoteAddr();
    }
}
