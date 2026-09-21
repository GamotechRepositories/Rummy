package com.rummy.gameservice.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;
import java.time.Duration;

/**
 * Optional Redis for production multi-node coordination (matchmaking, routing, presence).
 * Enable with {@code rummy.redis.enabled=true} and {@code REDIS_URL} / host+port.
 */
@Configuration
@ConditionalOnProperty(name = "rummy.redis.enabled", havingValue = "true")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.url:}") String redisUrl,
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {

        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
        if (redisUrl != null && !redisUrl.isBlank()) {
            URI uri = URI.create(redisUrl);
            standalone.setHostName(uri.getHost() != null ? uri.getHost() : "localhost");
            standalone.setPort(uri.getPort() > 0 ? uri.getPort() : 6379);
            String userInfo = uri.getUserInfo();
            if (userInfo != null && userInfo.contains(":")) {
                standalone.setPassword(userInfo.substring(userInfo.indexOf(':') + 1));
            } else if (userInfo != null && !userInfo.isBlank()) {
                standalone.setPassword(userInfo);
            }
        } else {
            standalone.setHostName(host);
            standalone.setPort(port);
            if (password != null && !password.isBlank()) {
                standalone.setPassword(password);
            }
        }

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(800))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(800)))
                .autoReconnect(true)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(800))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfig);
        factory.setValidateConnection(false);
        log.info("[Redis] Coordination enabled → {}:{}", standalone.getHostName(), standalone.getPort());
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
