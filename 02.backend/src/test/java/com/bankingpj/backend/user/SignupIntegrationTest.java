package com.bankingpj.backend.user;

import com.bankingpj.backend.support.MySqlTestContainerConfiguration;
import com.bankingpj.backend.user.domain.User;
import com.bankingpj.backend.user.domain.UserRole;
import com.bankingpj.backend.user.domain.UserStatus;
import com.bankingpj.backend.user.dto.SignupRequest;
import com.bankingpj.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(MySqlTestContainerConfiguration.class)
class SignupIntegrationTest {

    private static final String SIGNUP_PATH = "/api/auth/signup";
    private static final String RAW_PASSWORD = "signup-test-password";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 회원가입의 DB 커밋·기본 상태·역할과 안전한 성공 응답을 검증한다.
    @Test
    void signupCreatesActiveUserWithUserRoleAndSafeResponse() throws Exception {
        String email = uniqueEmail();

        MvcResult result = mockMvc.perform(post(SIGNUP_PATH)
                        .header("X-Request-ID", "signup-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, RAW_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-ID", "signup-request"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        // No test transaction: this lookup observes the service's committed database state.
        User saved = userRepository.findByEmail(email).orElseThrow();
        assertThat(saved.getUserId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getName()).isEqualTo("Test User");
        content().json("""
                {"success":true,"data":{"userId":%d},"error":null}
                """.formatted(saved.getUserId()), JsonCompareMode.STRICT).match(result);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("password", "passwordHash", RAW_PASSWORD, saved.getPasswordHash());
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // 원문 대신 BCrypt 해시를 저장하고 비밀번호 검증이 가능한지 확인한다.
    @Test
    void storesOnlyBcryptHashThatMatchesOriginalPassword() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, RAW_PASSWORD)))
                .andExpect(status().isCreated());

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(storedHash).isNotEqualTo(RAW_PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(RAW_PASSWORD, storedHash)).isTrue();
        assertThat(passwordEncoder.matches("different-password", storedHash)).isFalse();
    }

    // 중복 가입이 USER_001로 실패하고 기존 회원은 유지되는지 검증한다.
    @Test
    void secondSignupWithSameEmailReturnsUser001WithoutChangingUser() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, RAW_PASSWORD)))
                .andExpect(status().isCreated());
        User original = userRepository.findByEmail(email).orElseThrow();

        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, "another-password")))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"success":false,"data":null,
                         "error":{"code":"USER_001","message":"이미 사용 중인 이메일"}}
                        """, JsonCompareMode.STRICT));

        User unchanged = userRepository.findByEmail(email).orElseThrow();
        assertThat(unchanged.getUserId()).isEqualTo(original.getUserId());
        assertThat(unchanged.getPasswordHash()).isEqualTo(original.getPasswordHash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1L);
    }

    // 잘못된 이메일이 저장 없이 COMMON_001로 거부되는지 검증한다.
    @Test
    void invalidEmailReturnsCommon001WithoutSavingUser() throws Exception {
        long before = userRepository.count();
        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("invalid-email", RAW_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(userRepository.count()).isEqualTo(before);
    }

    // 비밀번호 누락 요청이 저장 없이 검증 오류로 거부되는지 검증한다.
    @Test
    void missingPasswordReturnsCommon001WithoutSavingUser() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","name":"Test User"}
                                """.formatted(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    // 다중 바이트 비밀번호의 BCrypt 제한 초과를 검증 단계에서 거부하는지 확인한다.
    @Test
    void rejectsMultibytePasswordOverBcryptLimitBeforeEncoding() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post(SIGNUP_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson(email, "가".repeat(25))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    // health 조회가 인증과 세션 생성 없이 가능한지 검증한다.
    @Test
    void healthIsPublicWithoutCreatingSession() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    // 공개하지 않은 GET 경로가 로그인 리다이렉트 없이 인증을 요구하는지 검증한다.
    @ParameterizedTest
    @ValueSource(strings = {"/api/private", "/api/auth/signup", "/actuator/info", "/login", "/logout"})
    void otherGetEndpointsRequireAuthenticationWithoutLoginRedirect(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("AUTH_003"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    // 역할 마이그레이션 적용과 VARCHAR·NOT NULL DB 정의를 검증한다.
    @Test
    void roleMigrationIsAppliedAsNonNullVarchar() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class))
                .contains("1", "2", "3", "4");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'role'
                """, String.class)).isEqualTo("varchar");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT IS_NULLABLE FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name = 'role'
                """, String.class)).isEqualTo("NO");
    }

    // 테스트용 회원가입 요청을 JSON으로 변환한다.
    private String signupJson(String email, String password) {
        return objectMapper.writeValueAsString(new SignupRequest(email, password, "Test User"));
    }

    // 테스트 간 중복되지 않는 회원 이메일을 생성한다.
    private String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
