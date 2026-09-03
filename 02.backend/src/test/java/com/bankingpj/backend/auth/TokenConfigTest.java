package com.bankingpj.backend.auth;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.config.TokenConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenConfigTest {

    private final TokenConfig config = new TokenConfig();

    // 누락·형식 오류·길이 부족인 서명 키 설정을 원문 노출 없이 거부하는지 검증한다.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid-base64!", "YWJj"})
    void rejectsMissingMalformedAndShortSecrets(String secret) {
        assertThatThrownBy(() -> config.jwtAccessKey(new AuthProperties(secret, 900, 1209600, true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("JWT_ACCESS_SECRET");
    }

    // 외부에서 주입한 256비트 Base64 서명 키를 사용할 수 있는지 검증한다.
    @Test
    void acceptsRandom256BitSecret() {
        SecretKey key = randomKey();
        assertThat(key.getEncoded().length).isEqualTo(32);
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
    }

    // Access·Refresh Token 수명이 양수가 아니면 설정 생성을 거부하는지 검증한다.
    @Test
    void rejectsNonPositiveTokenLifetimes() {
        assertThatThrownBy(() -> new AuthProperties("unused", 0, 1, true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties("unused", 1, -1, true)).isInstanceOf(IllegalArgumentException.class);
    }

    // 다른 키로 서명한 토큰을 Decoder가 거부하는지 검증한다.
    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JwtEncoder encoder = config.jwtEncoder(randomKey());
        JwtDecoder decoder = config.jwtDecoder(randomKey());
        String token = signedToken(encoder, "bankingpj", Instant.now().plusSeconds(900));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    // 서명이 맞아도 issuer가 다르거나 만료된 토큰은 거부하는지 검증한다.
    @Test
    void rejectsWrongIssuerAndExpiredTokens() {
        SecretKey key = randomKey();
        JwtEncoder encoder = config.jwtEncoder(key);
        JwtDecoder decoder = config.jwtDecoder(key);
        String wrongIssuer = signedToken(encoder, "another-issuer", Instant.now().plusSeconds(900));
        String expired = signedToken(encoder, "bankingpj", Instant.now().minusSeconds(120));

        assertThatThrownBy(() -> decoder.decode(wrongIssuer)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    // 테스트 메모리에서만 사용할 임시 서명 키를 생성한다.
    private SecretKey randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return config.jwtAccessKey(new AuthProperties(Base64.getEncoder().encodeToString(bytes), 900, 1209600, true));
    }

    // JWT 원문을 출력하지 않고 지정한 issuer와 만료 시각으로 테스트 토큰을 서명한다.
    private String signedToken(JwtEncoder encoder, String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("role", "USER").issuer(issuer)
                .issuedAt(expiresAt.minusSeconds(900)).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }
}
