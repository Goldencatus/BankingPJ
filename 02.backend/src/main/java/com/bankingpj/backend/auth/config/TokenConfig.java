package com.bankingpj.backend.auth.config;

import com.bankingpj.backend.auth.token.AccessTokenClaimsValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class TokenConfig {

    public static final String ISSUER = "bankingpj";

    // 외부 서명 키의 형식과 길이를 확인하며 오류에 원문 키를 포함하지 않는다.
    @Bean
    public SecretKey jwtAccessKey(AuthProperties properties) {
        String secret = properties.accessSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT_ACCESS_SECRET is required");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("JWT_ACCESS_SECRET must be valid Base64");
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_ACCESS_SECRET must contain at least 32 decoded bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    // HS256 Access Token을 발급할 Nimbus 인코더를 제공한다.
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtAccessKey) {
        return NimbusJwtEncoder.withSecretKey(jwtAccessKey).build();
    }

    // Resource Server에서 사용할 서명·발급자·시간·필수 claim 검증기를 제공한다.
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtAccessKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtAccessKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new AccessTokenClaimsValidator()));
        return decoder;
    }

    // 토큰 발급과 영속화된 만료 타임스탬프를 위해 공유된 UTC 시간 소스를 사용한다.
    @Bean
    public Clock tokenClock() {
        return Clock.systemUTC();
    }
}
