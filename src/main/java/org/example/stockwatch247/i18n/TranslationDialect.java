package org.example.stockwatch247.i18n;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Adds {@code #tr} to templates for text that services build in English (pattern names, interval and
 * lifecycle labels, saved explanations): {@code ${#tr.text(event.patternLabel())}} shows it in the
 * page's language through {@link ServerMessages}. Interface text written in templates uses
 * {@code #{key}} instead.
 */
@Component
public class TranslationDialect extends AbstractDialect implements IExpressionObjectDialect {
    static final String NAME = "tr";

    public TranslationDialect() {
        super("StockWatch translations");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(NAME);
            }

            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                return NAME.equals(expressionObjectName) ? new Translator(context.getLocale()) : null;
            }

            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }

    /** The {@code #tr} object: translations for one template run, in that run's locale. */
    public record Translator(Locale locale) {
        public String text(Object value) {
            return value == null ? null : ServerMessages.translate(value.toString(), locale);
        }

        /**
         * An insider's officer title or a member's chamber: {@code ${#tr.role(signal.actorRole())}}. In Polish a title
         * is translated when every part of it is a known role and is otherwise shown as filed.
         */
        public String role(Object value) {
            if (value == null) return null;
            if (AppLanguage.of(locale) == AppLanguage.PL) {
                String polish = RoleTitles.polish(value.toString());
                if (polish != null) return polish;
            }
            return text(value);
        }

        /** The plural form's key for a count: {@code #messages.msg(#tr.pluralKey('rules', n), n)}. */
        public String pluralKey(String baseKey, Number count) {
            return Plurals.key(locale, baseKey, count);
        }

        /** A number with thousands grouped the page language's way: {@code ${#tr.number(value, 2)}}. */
        public String number(Number value, int digits) {
            if (value == null) return null;
            NumberFormat format = NumberFormat.getNumberInstance(locale);
            format.setMinimumFractionDigits(digits);
            format.setMaximumFractionDigits(digits);
            format.setRoundingMode(RoundingMode.HALF_UP);
            return format.format(rounded(value, digits));
        }

        /** An amount and its currency, joined by a no-break space so the currency never wraps onto a line of its own. */
        public String money(Number value, int digits, String currency) {
            return value == null ? null : number(value, digits) + NO_BREAK_SPACE + currency;
        }

        /** A change with its sign, "+1.20" or "-1.20". Zero has no sign: it is neither a gain nor a loss. */
        public String signed(Number value, int digits) {
            if (value == null) return null;
            BigDecimal rounded = rounded(value, digits);
            return (rounded.signum() > 0 ? "+" : rounded.signum() < 0 ? "-" : "") + number(rounded.abs(), digits);
        }

        public String signedPercent(Number value) {
            return value == null ? null : signed(value, 2) + "%";
        }

        public String signedMoney(Number value, String currency) {
            return value == null ? null : signed(value, 2) + NO_BREAK_SPACE + currency;
        }

        /** The colour class of a change as shown with two decimals: "positive", "negative", or "neutral" for zero. */
        public String tone(Number value) {
            if (value == null) return "neutral";
            int sign = rounded(value, 2).signum();
            return sign > 0 ? "positive" : sign < 0 ? "negative" : "neutral";
        }

        private static BigDecimal rounded(Number value, int digits) {
            return new BigDecimal(value.toString()).setScale(digits, RoundingMode.HALF_UP);
        }
    }

    private static final char NO_BREAK_SPACE = ' ';
}
