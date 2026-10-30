package org.example.stockwatch247.security;

public final class AccountSession {
    public static final String SECURITY_VERSION = "ACCOUNT_SECURITY_VERSION";
    public static final String MFA_PENDING_USER_ID = "MFA_PENDING_USER_ID";
    public static final String MFA_PENDING_AT = "MFA_PENDING_AT";
    public static final String MFA_SETUP_SECRET = "MFA_SETUP_SECRET";
    public static final String MFA_SETUP_AT = "MFA_SETUP_AT";
    public static final String MFA_PENDING_VERSION = "MFA_PENDING_VERSION";
    public static final String MFA_PENDING_FAILURES = "MFA_PENDING_FAILURES";
    /** The page to open once the authenticator step succeeds (see {@link PostLoginRedirect}). */
    public static final String MFA_PENDING_TARGET = "MFA_PENDING_TARGET";
    /** "Keep me signed in" was ticked; applied once the authenticator step succeeds (see {@link RememberedSessions}). */
    public static final String MFA_PENDING_REMEMBER = "MFA_PENDING_REMEMBER";
    /** A session that lasts {@link RememberedSessions#LIFETIME} on this device. */
    public static final String REMEMBERED = "ACCOUNT_SESSION_REMEMBERED";
    public static final String REQUEST_USER = AccountSession.class.getName() + ".user";
    private AccountSession() {}
}
