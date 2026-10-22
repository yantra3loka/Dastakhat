package com.dastakhat.config;

import com.dastakhat.security.DatabaseUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Session based authentication for senders plus a public, token based surface for signers.
 *
 * <ul>
 *   <li>The session cookie is HttpOnly, SameSite=Lax and {@code Secure} in production.</li>
 *   <li>Every state changing request needs the CSRF token in the {@code X-CSRF-TOKEN} header.</li>
 *   <li>Signing links are bearer secrets: they are hashed before storage and single use.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    public static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/health",
            "/api/v1/ready",
            "/api/v1/auth/login",
            "/api/v1/auth/csrf",
            "/api/v1/sign/**",
            "/api/v1/esign/callback",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health/**",
            "/actuator/info"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt with a work factor of 12 - deliberately slow enough to make offline cracking expensive.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(DatabaseUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * Accepts the CSRF token either in the {@code X-CSRF-TOKEN} header or in the
     * {@code X-XSRF-TOKEN} header that matches the {@code XSRF-TOKEN} cookie.
     */
    @Bean
    public CsrfTokenRequestHandler csrfTokenRequestHandler() {
        return new CsrfTokenRequestHandler() {
            private final CsrfTokenRequestAttributeHandler delegate = new CsrfTokenRequestAttributeHandler();

            @Override
            public void handle(HttpServletRequest request, HttpServletResponse response,
                               java.util.function.Supplier<CsrfToken> csrfToken) {
                this.delegate.handle(request, response, csrfToken);
            }

            @Override
            public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
                String header = request.getHeader("X-CSRF-TOKEN");
                if (header == null || header.isBlank()) {
                    header = request.getHeader("X-XSRF-TOKEN");
                }
                if (header != null && !header.isBlank()) {
                    return header;
                }
                return CsrfTokenRequestHandler.super.resolveCsrfTokenValue(request, csrfToken);
            }
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           AppProperties properties,
                                           SecurityContextRepository securityContextRepository,
                                           CsrfTokenRequestHandler csrfTokenRequestHandler) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").path("/"));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        // The login does not act on an existing session, it creates one. Every
                        // other state changing request of the sender API needs the token.
                        .ignoringRequestMatchers("/api/v1/auth/login")
                        // The signer API is authorised by the signing token itself, which is a
                        // secret that never leaves the recipient. A CSRF token would be of no
                        // use there because a signer has no session.
                        .ignoringRequestMatchers("/api/v1/sign/**", "/api/v1/esign/callback")
                        .csrfTokenRequestHandler(csrfTokenRequestHandler))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository)
                        .requireExplicitSave(false))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(sf -> sf.migrateSession())
                        .maximumSessions(5))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .invalidateHttpSession(true)
                        .deleteCookies("DASTAKHAT_SESSION", "JSESSIONID")
                        .permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, ex2) -> {
                            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\","
                                    + "\"message\":\"Authentication required.\",\"code\":\"unauthorized\"}");
                        })
                        .accessDeniedHandler((req, res, ex2) -> {
                            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                                    + "\"message\":\"Access denied.\",\"code\":\"forbidden\"}");
                        }))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .cacheControl(Customizer.withDefaults())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "img-src 'self' data: blob:; "
                                        + "object-src 'none'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'; "
                                        + "connect-src 'self' https: http://127.0.0.1:51515 http://localhost:9100; "
                                        + "script-src 'self'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "worker-src 'self' blob:")))
                ;

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCorsOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-XSRF-TOKEN", "Accept",
                HttpHeaders.AUTHORIZATION));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
