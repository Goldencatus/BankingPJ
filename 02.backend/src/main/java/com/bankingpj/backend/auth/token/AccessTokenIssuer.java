package com.bankingpj.backend.auth.token;

import com.bankingpj.backend.auth.config.AuthProperties;
import com.bankingpj.backend.auth.config.TokenConfig;
import com.bankingpj.backend.user.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final AuthProperties properties;

    // 토큰 서명기와 Access Token 만료 설정을 주입받는다.
    public AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    // 사용자 ID, 역할 및 토큰 메타데이터만 포함한 토큰을 발급한다
    public String issue(User user, Instant issuedAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getUserId().toString())
                .claim("role", user.getRole().name())
                .issuer(TokenConfig.ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(properties.accessTtlSeconds()))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
