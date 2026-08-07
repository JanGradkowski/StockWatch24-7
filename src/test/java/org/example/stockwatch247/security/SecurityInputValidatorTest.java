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
        assertEquals("#3B82F6", SecurityInputValidator.requireHexColor(" #3b82f6 "));
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
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireHexColor("red; background: url(javascript:alert(1))"));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityInputValidator.requireHexColor("#12345"));
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
