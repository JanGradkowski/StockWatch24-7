package org.example.stockwatch247.i18n;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.service.UserInputException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class LanguagePreferenceService {
    /** Settings value meaning "follow the browser". */
    public static final String AUTOMATIC = "auto";

    private final JdbcTemplate jdbc;

    public LanguagePreferenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Saves "auto", "en" or "pl" for the account. */
    public void save(Long userId, String choice) {
        String stored;
        if (choice == null || AUTOMATIC.equalsIgnoreCase(choice.trim())) {
            stored = null;
        } else {
            AppLanguage language = AppLanguage.fromCode(choice);
            if (language == null) throw new UserInputException("Unsupported language.");
            stored = language.code();
        }
        jdbc.update("update users set preferred_language = ? where id = ?", stored, userId);
    }

    void rememberDetected(Long userId, AppLanguage language) {
        jdbc.update("update users set detected_language = ? where id = ? and detected_language is distinct from ?",
                language.code(), userId, language.code());
    }

    /** The language for messages sent without a browser request: the saved choice, else the last browser seen. */
    public static Locale localeFor(User user) {
        if (user == null) return AppLanguage.DEFAULT.locale();
        return locale(user.getPreferredLanguage(), user.getDetectedLanguage());
    }

    /** The language for messages to this address (an alert recipient), chosen as in {@link #localeFor(User)}. */
    public Locale localeForEmail(String email) {
        if (email == null || email.isBlank()) return AppLanguage.DEFAULT.locale();
        java.util.List<Locale> found = jdbc.query(
                "select preferred_language, detected_language from users where lower(email) = lower(?)",
                (row, index) -> locale(row.getString(1), row.getString(2)), email.trim());
        return found.isEmpty() ? AppLanguage.DEFAULT.locale() : found.getFirst();
    }

    private static Locale locale(String preferred, String detected) {
        AppLanguage saved = AppLanguage.fromCode(preferred);
        if (saved != null) return saved.locale();
        AppLanguage seen = AppLanguage.fromCode(detected);
        return (seen == null ? AppLanguage.DEFAULT : seen).locale();
    }

    /** The value the settings form shows as selected. */
    public static String settingsChoice(User user) {
        AppLanguage saved = user == null ? null : AppLanguage.fromCode(user.getPreferredLanguage());
        return saved == null ? AUTOMATIC : saved.code();
    }
}
