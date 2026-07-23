package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityInputValidatorTest {

    @Test
    void normalizesSafeMarketInputs() {
        assertEquals("SAP.DE", SecurityInputValidator.requireMarketSymbol(" sap.de "));
        assertEquals("^GSPC", SecurityInputValidator.requireMarketSymbol("spx"));
        assertEquals("^GSPC", SecurityInputValidator.requireMarketSymbol(" ^gspc "));
        assertEquals("^GSPC", SecurityInputValidator.requireSearchQuery(" ^GSPC "));
        assertEquals("XWAR", SecurityInputValidator.requireOptionalMicCode(" xwar "));
        assertEquals("jan@example.com", SecurityInputValidator.requireEmail(" Jan@Example.com "));
        assertEquals("1wk", SecurityInputValidator.requireInterval("1wk"));
    }

    @Test
    void rejectsScriptSqlAndUnsupportedIntervalInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireMarketSymbol("<script>alert(1)</script>"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireMarketSymbol("AAPL'; DROP TABLE users;--"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireSearchQuery("<img src=x onerror=alert(1)>"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireInterval("../../../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireOptionalMicCode("XWAR<script>"));
    }

    @Test
    void enforcesBcryptPasswordBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requirePassword("short"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requirePassword("é".repeat(37)));
        assertEquals("correct horse battery staple",
                SecurityInputValidator.requirePassword("correct horse battery staple"));
    }
}
