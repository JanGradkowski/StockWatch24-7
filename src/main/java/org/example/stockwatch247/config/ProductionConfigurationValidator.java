package org.example.stockwatch247.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Fails startup when security-sensitive production settings are missing or unsafe. */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {
    private final boolean requireHttps;
    private final String publicBaseUrl;
    private final String forwardHeadersStrategy;
    private final String trustedProxyPattern;
    private final boolean verificationRequired;
    private final boolean emailEnabled;
    private final String mailHost;
    private final String mailUsername;
    private final String mailPassword;
    private final String fromAddress;
    private final boolean smtpAuth;
    private final boolean startTlsEnabled;
    private final boolean startTlsRequired;
    private final String migrationUsername;
    private final String migrationPassword;
    private final String mfaEncryptionKey;

    @Autowired
    public ProductionConfigurationValidator(
            @Value("${security.require-https:false}") boolean requireHttps,
            @Value("${app.public-base-url:}") String publicBaseUrl,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy,
            @Value("${server.tomcat.remoteip.internal-proxies:}") String trustedProxyPattern,
            @Value("${security.email-verification.required:true}") boolean verificationRequired,
            @Value("${alerts.email.enabled:false}") boolean emailEnabled,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${spring.mail.username:}") String mailUsername,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${alerts.email.from:}") String fromAddress,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean smtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:false}") boolean startTlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:false}") boolean startTlsRequired,
            @Value("${DB_MIGRATION_USERNAME:}") String migrationUsername,
            @Value("${DB_MIGRATION_PASSWORD:}") String migrationPassword,
            @Value("${security.mfa.encryption-key:}") String mfaEncryptionKey) {
        this.requireHttps = requireHttps;
        this.publicBaseUrl = publicBaseUrl;
        this.forwardHeadersStrategy = forwardHeadersStrategy;
        this.trustedProxyPattern = trustedProxyPattern;
        this.verificationRequired = verificationRequired;
        this.emailEnabled = emailEnabled;
        this.mailHost = mailHost;
        this.mailUsername = mailUsername;
        this.mailPassword = mailPassword;
        this.fromAddress = fromAddress;
        this.smtpAuth = smtpAuth;
        this.startTlsEnabled = startTlsEnabled;
        this.startTlsRequired = startTlsRequired;
        this.migrationUsername = migrationUsername;
        this.migrationPassword = migrationPassword;
        this.mfaEncryptionKey = mfaEncryptionKey;
    }

    /** Retained for focused tests and non-Spring construction. */
    public ProductionConfigurationValidator(boolean requireHttps, String publicBaseUrl,
                                            String forwardHeadersStrategy, String trustedProxyPattern,
                                            boolean verificationRequired, boolean emailEnabled,
                                            String mailHost, String mailUsername, String mailPassword,
                                            String fromAddress, boolean smtpAuth, boolean startTlsEnabled,
                                            boolean startTlsRequired, String migrationUsername,
                                            String migrationPassword) {
        this(requireHttps, publicBaseUrl, forwardHeadersStrategy, trustedProxyPattern,
                verificationRequired, emailEnabled, mailHost, mailUsername, mailPassword,
                fromAddress, smtpAuth, startTlsEnabled, startTlsRequired, migrationUsername,
                migrationPassword, "test-only-mfa-encryption-key-with-32-characters");
    }

    @Override
    public void afterPropertiesSet() {
        require(requireHttps, "Production requires HTTPS enforcement.");
        validatePublicBaseUrl();
        require("native".equalsIgnoreCase(forwardHeadersStrategy),
                "Production must use the container's trusted-proxy forwarding strategy.");
        validateTrustedProxyPattern();
        require(isBlank(migrationUsername) && isBlank(migrationPassword),
                "The production web process must not receive database migration credentials.");
        require(!isBlank(mfaEncryptionKey)
                        && !"local-development-only-change-me".equals(mfaEncryptionKey)
                        && !"replace-with-a-long-random-secret".equals(mfaEncryptionKey)
                        && mfaEncryptionKey.length() >= 32,
                "MFA_ENCRYPTION_KEY must be a unique production secret of at least 32 characters.");

        require(!verificationRequired || emailEnabled,
                "Email delivery must be enabled when production email verification is required.");
        if (emailEnabled) {
            require(!isBlank(mailHost), "SMTP_HOST is required when email delivery is enabled.");
            require(!isBlank(fromAddress) && fromAddress.contains("@"),
                    "ALERTS_EMAIL_FROM must be a valid configured address.");
            if (smtpAuth) {
                require(!isBlank(mailUsername), "SMTP_USERNAME is required when SMTP authentication is enabled.");
                require(!isBlank(mailPassword), "SMTP_PASSWORD is required when SMTP authentication is enabled.");
            }
            require(startTlsEnabled && startTlsRequired,
                    "Production SMTP must enable and require STARTTLS.");
        }
    }

    private void validatePublicBaseUrl() {
        require(!isBlank(publicBaseUrl), "PUBLIC_BASE_URL must be an absolute HTTPS URL.");
        try {
            URI uri = URI.create(publicBaseUrl);
            require("https".equalsIgnoreCase(uri.getScheme()) && !isBlank(uri.getHost()),
                    "PUBLIC_BASE_URL must be an absolute HTTPS URL.");
            require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                    "PUBLIC_BASE_URL must not contain credentials, a query, or a fragment.");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PUBLIC_BASE_URL must be an absolute HTTPS URL.", exception);
        }
    }

    private void validateTrustedProxyPattern() {
        require(!isBlank(trustedProxyPattern), "TRUSTED_PROXY_IP_REGEX is required.");
        String compact = trustedProxyPattern.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        require(!compact.equals(".*") && !compact.equals("^.*$") && !compact.equals(".+") && !compact.equals("^.+$"),
                "TRUSTED_PROXY_IP_REGEX must not trust every address.");
        try {
            Pattern pattern = Pattern.compile(trustedProxyPattern);
            for (String unrelatedAddress : new String[]{"198.51.100.42", "203.0.113.9", "2001:db8::42"}) {
                require(!pattern.matcher(unrelatedAddress).matches(),
                        "TRUSTED_PROXY_IP_REGEX must not trust arbitrary public addresses.");
            }
        } catch (PatternSyntaxException exception) {
            throw new IllegalStateException("TRUSTED_PROXY_IP_REGEX is not a valid regular expression.", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
