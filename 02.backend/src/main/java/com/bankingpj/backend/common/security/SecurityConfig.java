package com.bankingpj.backend.common.security;

import com.bankingpj.backend.user.domain.UserRole;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;
import java.util.Collection;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@Import({SecurityErrorResponseWriter.class, RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
public class SecurityConfig {

    // 비밀번호 저장과 검증에 사용할 BCrypt 인코더를 Bean으로 제공한다.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 공개 인증 API와 health 조회를 허용하고 나머지 요청은 인증을 요구한다.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                                  JwtAuthenticationConverter converter,
                                                  RestAuthenticationEntryPoint entryPoint,
                                                  RestAccessDeniedHandler deniedHandler) throws Exception {
        return http
                // 세션은 사용하지 않으며 Refresh 쿠키 API의 최종 CSRF 보강은 보안 점검 단계에서 다룬다.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login",
                                "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .build();
    }

    // JWT 역할을 Spring Security 인증 객체의 권한으로 변환하도록 구성한다.
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::grantedAuthorities);
        return converter;
    }

    // UserRole에 선언된 역할만 ROLE_ 접두사의 권한으로 변환한다.
    private Collection<GrantedAuthority> grantedAuthorities(Jwt jwt) {
        Object claim = jwt.getClaims().get("role");
        return Arrays.stream(UserRole.values())
                .filter(role -> role.name().equals(claim))
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }
}
