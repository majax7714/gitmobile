package com.ajax.relay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Value("${auth.bearer-token}")
    private String bearerToken;

    @Value("${cors.allowed-origins:capacitor://localhost,ionic://localhost,http://localhost,https://localhost}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerTokenFilter(bearerToken),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS for the Capacitor mobile client. The WebView issues requests from
     * {@code capacitor://localhost} / {@code ionic://localhost} (iOS) and
     * {@code http://localhost} (Android); the origins are overridable via the
     * {@code CORS_ALLOWED_ORIGINS} env var (comma-separated).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    static class BearerTokenFilter extends OncePerRequestFilter {
        private final String expected;

        BearerTokenFilter(String expected) {
            this.expected = expected;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            // CORS preflights carry no Authorization header and must not be rejected.
            // Skip auth for OPTIONS so the CORS layer can answer the preflight with 200.
            if (HttpMethod.OPTIONS.matches(req.getMethod())) {
                chain.doFilter(req, res);
                return;
            }

            String header = req.getHeader("Authorization");
            boolean headerOk = header != null && header.startsWith("Bearer ")
                    && header.substring(7).equals(expected);

            // Webview WebSocket APIs cannot set custom headers, so /api/shell also
            // accepts the token via ?token= query param. Limited to that path so REST
            // endpoints keep header-only auth.
            boolean queryOk = "/api/shell".equals(req.getRequestURI())
                    && expected.equals(req.getParameter("token"));

            if (headerOk || queryOk) {
                var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "relay-client", null, java.util.List.of());
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                chain.doFilter(req, res);
                return;
            }
            if (req.getRequestURI().equals("/actuator/health")) {
                chain.doFilter(req, res);
                return;
            }
            res.setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }
}
