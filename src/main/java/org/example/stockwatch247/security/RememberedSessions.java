package org.example.stockwatch247.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;

import java.time.Duration;

/**
 * "Keep me signed in on this device": the session lasts {@link #LIFETIME} instead of ending after 30 idle minutes
 * and 12 hours, and its cookie outlives the browser. Signing out, a password change and "sign out everywhere"
 * still end it, because those checks read the session's account version, not its age.
 */
public final class RememberedSessions {
    public static final String PARAMETER = "remember-me";
    public static final Duration LIFETIME = Duration.ofDays(30);

    private RememberedSessions() {}

    /** True when the sign-in form's "Keep me signed in" box was ticked. */
    public static boolean requested(HttpServletRequest request) {
        String value = request.getParameter(PARAMETER);
        return value != null && (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on")
                || value.equals("1") || value.equalsIgnoreCase("yes"));
    }

    /**
     * Makes the current session a remembered one. Call it before the response is committed: the persistent cookie
     * is written with the response that changes the session id.
     */
    public static void apply(HttpServletRequest request) {
        HttpSession session = request.getSession();
        session.setMaxInactiveInterval((int) LIFETIME.toSeconds());
        session.setAttribute(AccountSession.REMEMBERED, Boolean.TRUE);
        // Spring Session's cookie serializer makes the session cookie persistent when it sees this attribute.
        request.setAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR, Boolean.TRUE);
    }

    /** True for a session that {@link #apply} made a remembered one. */
    public static boolean remembered(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(AccountSession.REMEMBERED));
    }
}
