package com.rummy.gameservice.config;

import com.rummy.gameservice.handler.GameWebSocketHandler;
import com.rummy.gameservice.security.JwtHandshakeInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Objects;

/**
 * Registers WebSocket endpoints and CORS policies for real-time gameplay.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final String allowedOrigins;

    public WebSocketConfig(GameWebSocketHandler gameWebSocketHandler,
                           JwtHandshakeInterceptor jwtHandshakeInterceptor,
                           @Value("${rummy.security.allowed-origins:*}") String allowedOrigins) {
        this.gameWebSocketHandler = Objects.requireNonNull(gameWebSocketHandler);
        this.jwtHandshakeInterceptor = Objects.requireNonNull(jwtHandshakeInterceptor);
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOriginPatterns(WebCorsConfig.parseOrigins(allowedOrigins).toArray(String[]::new));
    }
}
