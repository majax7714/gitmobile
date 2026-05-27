package com.ajax.relay.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
public class SecurityConfig {

    @Value("${auth.bearer-token}")
    private String bearerToken;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new BearerTokenFilter(bearerToken),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static class BearerTokenFilter extends OncePerRequestFilter {
        private final String expected;

        BearerTokenFilter(String expected) {
            this.expected = expected;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
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
