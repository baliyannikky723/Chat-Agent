package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.store.Store.UserRow;
import app.orrery.core.time.AgentClock;
import app.orrery.core.util.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Anonymous identity without a login: a random id in an HttpOnly cookie. All memory is
 * scoped to it, so two browsers never see each other's history. The browser's IANA
 * timezone arrives in X-Timezone on every request; "last Tuesday" is computed in it.
 */
@Component
public class UserIdentityFilter extends OncePerRequestFilter {

    static final String COOKIE = "orrery_uid";

    private final AgentRuntime runtime;

    public UserIdentityFilter(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/") || uri.equals("/api/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE.equals(c.getName()) && Ids.isUuid(c.getValue())) id = c.getValue();
            }
        }
        if (id == null) {
            id = Ids.uuidv7();
            response.addHeader("Set-Cookie", COOKIE + "=" + id + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax");
        }

        String tz = request.getHeader("X-Timezone");
        String locale = sanitizeLocale(request.getHeader("X-Locale"), request.getHeader("Accept-Language"));
        if (!AgentClock.isValidZone(tz)) {
            UserRow existing = runtime.store().user(id);
            tz = existing != null ? existing.timezone() : "UTC";
        }
        UserRow user = runtime.store().ensureUser(id, tz, locale);
        request.setAttribute(UserContext.ATTRIBUTE, new UserContext(user.id(), user.timezone(), user.locale()));
        chain.doFilter(request, response);
    }

    static String sanitizeLocale(String explicit, String acceptLanguage) {
        String raw = explicit != null && !explicit.isBlank() ? explicit : acceptLanguage;
        if (raw == null || raw.isBlank()) return "en-GB";
        String first = raw.split(",")[0].split(";")[0].strip();
        return first.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*") ? first : "en-GB";
    }
}
