package com.bankingpj.backend.common.exception;

import com.bankingpj.backend.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerMvcTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerMvcTest.TestController.class})
class GlobalExceptionHandlerMvcTest {

    private static final String BASE_PATH = "/test/common-exceptions";
    private static final String INTERNAL_MESSAGE = "Sensitive internal database connection details";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void validRequestReturnsSuccessfulResponse() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"tester@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "success": true,
                          "data": {"email": "tester@example.com"},
                          "error": null
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(result -> assertThat(result.getResolvedException()).isNull());
    }

    @Test
    void missingRequiredValueReturnsCommon001() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "success": false,
                          "data": null,
                          "error": {
                            "code": "COMMON_001",
                            "message": "잘못된 입력값 (email: 이메일은 필수입니다)"
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));
    }

    @Test
    void invalidEmailFormatReturnsCommon001() throws Exception {
        // Valid JSON with an invalid field value exercises Bean Validation, not JSON parsing.
        mockMvc.perform(post(BASE_PATH + "/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "success": false,
                          "data": null,
                          "error": {
                            "code": "COMMON_001",
                            "message": "잘못된 입력값 (email: 이메일 형식이 올바르지 않습니다)"
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(MethodArgumentNotValidException.class));
    }

    @Test
    void businessExceptionReturnsStatusAndErrorFromEachErrorCode() throws Exception {
        // Include both 400 and 500 codes so a hard-coded response status cannot pass.
        for (ErrorCode errorCode : ErrorCode.values()) {
            mockMvc.perform(get(BASE_PATH + "/business/{errorCode}", errorCode.name()))
                    .andExpect(status().is(errorCode.getHttpStatus().value()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("""
                            {
                              "success": false,
                              "data": null,
                              "error": {"code": "%s", "message": "%s"}
                            }
                            """.formatted(errorCode.getCode(), errorCode.getMessage()), JsonCompareMode.STRICT))
                    .andExpect(result -> assertThat(result.getResolvedException())
                            .isInstanceOfSatisfying(BusinessException.class,
                                    exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode)));
        }
    }

    @Test
    void unexpectedExceptionReturnsCommon999WithoutInternalDetails() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "success": false,
                          "data": null,
                          "error": {"code": "COMMON_999", "message": "서버 내부 오류"}
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(INTERNAL_MESSAGE))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(INTERNAL_MESSAGE, "IllegalStateException", "stackTrace", "trace",
                                TestController.class.getName()));
    }

    // These fixtures live only in src/test/java and are explicitly imported into this slice.
    @RestController
    @RequestMapping(BASE_PATH)
    public static class TestController {

        @PostMapping("/validation")
        public ApiResponse<ValidationRequest> validate(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.success(request);
        }

        @GetMapping("/business/{errorCode}")
        public ApiResponse<Void> business(@PathVariable("errorCode") ErrorCode errorCode) {
            throw new BusinessException(errorCode);
        }

        @GetMapping("/unexpected")
        public ApiResponse<Void> unexpected() {
            throw new IllegalStateException(INTERNAL_MESSAGE);
        }
    }

    public record ValidationRequest(
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "이메일 형식이 올바르지 않습니다")
            String email
    ) {
    }
}
