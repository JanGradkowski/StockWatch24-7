package org.example.stockwatch247.config;

import org.example.stockwatch247.security.RateLimitFilter;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.security.RequestBodySizeLimitFilter;
import org.example.stockwatch247.security.CspNonceFilter;
import org.example.stockwatch247.security.AbsoluteSessionTimeoutFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import java.time.Duration;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final boolean requireHttps;
    private final int serverPort;
    private final int httpsRedirectPort;
    private final long absoluteSessionTimeoutSeconds;
    private final long maximumJsonBodyBytes;
    private final RequestRateLimiter requestRateLimiter;

    public SecurityConfig(@Value("${security.require-https:false}") boolean requireHttps,
                           @Value("${server.port:8080}") int serverPort,
                           @Value("${security.https-redirect-port:443}") int httpsRedirectPort,
                           @Value("${security.session.absolute-timeout-seconds:43200}") long absoluteSessionTimeoutSeconds,
                           @Value("${security.request.max-json-body-bytes:65536}") long maximumJsonBodyBytes,
                           RequestRateLimiter requestRateLimiter) {
        this.requireHttps = requireHttps;
        this.serverPort = serverPort;
        this.httpsRedirectPort = httpsRedirectPort;
        this.absoluteSessionTimeoutSeconds = absoluteSessionTimeoutSeconds;
        this.maximumJsonBodyBytes = maximumJsonBodyBytes;
        this.requestRateLimiter = requestRateLimiter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 1. Public Routes (Added /login here explicitly)
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**",
                                "/signup", "/login", "/verify-email", "/resend-verification", "/about").permitAll()

                        // 2. Protected Routes (Added /stock/** here for the new stock page)
                        .requestMatchers("/home", "/stock/**", "/api/**").authenticated()

                        // 3. Catch-all safety net
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .usernameParameter("email")
                        .defaultSuccessUrl("/home", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.migrateSession())
                )
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                )
                .addFilterAfter(new AbsoluteSessionTimeoutFilter(
                        Duration.ofSeconds(Math.max(300L, absoluteSessionTimeoutSeconds))), SecurityContextHolderFilter.class)
                .addFilterAfter(new RateLimitFilter(requestRateLimiter), AbsoluteSessionTimeoutFilter.class)
                .addFilterAfter(new RequestBodySizeLimitFilter(maximumJsonBodyBytes), RateLimitFilter.class)
                .addFilterAfter(new CspNonceFilter(), RequestBodySizeLimitFilter.class);

        if (requireHttps) {
            http.portMapper(portMapper -> {
                portMapper.http(80).mapsTo(httpsRedirectPort);
                if (serverPort != 80) {
                    portMapper.http(serverPort).mapsTo(httpsRedirectPort);
                }
            });
            http.redirectToHttps(withDefaults());
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
