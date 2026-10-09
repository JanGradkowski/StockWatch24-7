package org.example.stockwatch247.service;

/** Whitelisted diagnostics only: never log recipient-bearing provider messages. */
enum MailFailureCategory {
    CONNECTION, NETWORK_TIMEOUT, TLS, AUTHENTICATION, SMTP_REJECTION, DATABASE_ACKNOWLEDGEMENT, OTHER;
    static MailFailureCategory of(Throwable failure) {
        if (InfrastructureRecovery.isDatabaseUnavailable(failure)) return DATABASE_ACKNOWLEDGEMENT;
        return inspect(failure, 0);
    }
    private static MailFailureCategory inspect(Throwable failure, int depth) {
        if (failure == null || depth > 20) return OTHER;
        if (failure instanceof org.springframework.mail.MailAuthenticationException) return AUTHENTICATION;
        if (failure instanceof javax.net.ssl.SSLException) return TLS;
        if (failure instanceof java.net.ConnectException || failure instanceof java.net.UnknownHostException
                || failure.getClass().getName().equals("org.eclipse.angus.mail.util.MailConnectException")) return CONNECTION;
        if (failure instanceof java.net.SocketTimeoutException) return NETWORK_TIMEOUT;
        MailFailureCategory cause = inspect(failure.getCause(), depth + 1);
        if (cause != OTHER) return cause;
        if (failure instanceof org.springframework.mail.MailSendException mail) {
            for (Exception nested : mail.getMessageExceptions()) {
                MailFailureCategory category = inspect(nested, depth + 1);
                if (category != OTHER) return category;
            }
            return SMTP_REJECTION;
        }
        if (failure instanceof jakarta.mail.MessagingException mail)
            return inspect(mail.getNextException(), depth + 1);
        return OTHER;
    }
    boolean providerOutage() { return this == CONNECTION || this == NETWORK_TIMEOUT || this == TLS || this == AUTHENTICATION; }
}
