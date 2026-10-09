package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountEmailLanguageTest {

    @Test
    void securityCodesAndNoticesAreWrittenInTheRecipientsLanguage() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AlertNotificationService service = service(sender);
        User polish = user("pl", null);

        service.sendPasswordSecurityCode(polish, "12345678", true);
        service.sendSecurityNotice(polish, "Your StockWatch password was changed",
                "Your password was changed and other sessions were signed out.");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).getSubject()).isEqualTo("Zresetuj hasło do StockWatch");
        assertThat(sent.getAllValues().get(0).getText()).contains("Twój jednorazowy kod zabezpieczający:", "12345678", "5 minutach");
        assertThat(sent.getAllValues().get(1).getSubject()).isEqualTo("Hasło do StockWatch zostało zmienione");
        assertThat(sent.getAllValues().get(1).getText())
                .isEqualTo("Hasło zostało zmienione, a pozostałe sesje zostały wylogowane.\n\nJeśli to nie Ty, natychmiast zresetuj hasło.");
    }

    @Test
    void anAutomaticAccountUsesTheLastBrowserLanguageAndOtherwiseEnglish() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AlertNotificationService service = service(sender);

        service.sendVerificationEmail(user(null, "pl"), "https://stockwatch.test/verify?token=abc");
        service.sendVerificationEmail(user(null, null), "https://stockwatch.test/verify?token=def");

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(0).getSubject()).isEqualTo("Potwierdź konto w StockWatch 24/7");
        assertThat(sent.getAllValues().get(0).getText()).contains("https://stockwatch.test/verify?token=abc");
        assertThat(sent.getAllValues().get(1).getSubject()).isEqualTo("Verify your StockWatch 24/7 account");
    }

    private static AlertNotificationService service(JavaMailSender sender) {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new AlertNotificationService(provider, true, "alerts@stockwatch.test", "Europe/Brussels");
    }

    private static User user(String preferred, String detected) {
        User user = new User();
        user.setEmail("language-email@example.com");
        user.setFirstName("Jan");
        user.setPreferredLanguage(preferred);
        user.setDetectedLanguage(detected);
        return user;
    }
}
