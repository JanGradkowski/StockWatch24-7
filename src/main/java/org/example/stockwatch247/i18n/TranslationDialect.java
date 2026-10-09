package org.example.stockwatch247.i18n;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

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

        /** The plural form's key for a count: {@code #messages.msg(#tr.pluralKey('rules', n), n)}. */
        public String pluralKey(String baseKey, Number count) {
            return Plurals.key(locale, baseKey, count);
        }
    }
}
