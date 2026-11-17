package app.orrery.web;

import jakarta.servlet.http.HttpServletRequest;

/** The anonymous user behind a request, set by {@link UserIdentityFilter}. */
public record UserContext(String id, String timezone, String locale) {

    public static final String ATTRIBUTE = "orrery.user";

    public static UserContext of(HttpServletRequest request) {
        Object u = request.getAttribute(ATTRIBUTE);
        if (u instanceof UserContext ctx) return ctx;
        throw new ApiException(401, "no_user", "Missing user context");
    }
}
