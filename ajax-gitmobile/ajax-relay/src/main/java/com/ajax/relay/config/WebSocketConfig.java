package com.ajax.relay.config;

import com.ajax.relay.controller.ShellWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ShellWebSocketHandler shellHandler;

    @Value("${auth.bearer-token}")
    private String bearerToken;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(shellHandler, "/api/shell")
                .addInterceptors(new BearerTokenHandshakeInterceptor(bearerToken))
                .setAllowedOriginPatterns("*");
    }

    static class BearerTokenHandshakeInterceptor implements HandshakeInterceptor {
        private final String expected;

        BearerTokenHandshakeInterceptor(String expected) {
            this.expected = expected;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            List<String> headers = request.getHeaders().get("Authorization");
            if (headers != null) {
                for (String header : headers) {
                    if (header.startsWith("Bearer ") && header.substring(7).equals(expected)) {
                        return true;
                    }
                }
            }
            // Webview WebSocket APIs cannot set custom headers, so also accept the
            // token via ?token= query param.
            String query = request.getURI().getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    if (!"token".equals(key)) {
                        continue;
                    }
                    String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    if (expected.equals(value)) {
                        return true;
                    }
                }
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
