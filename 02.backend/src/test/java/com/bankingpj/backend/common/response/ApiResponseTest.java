package com.bankingpj.backend.common.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    // 성공 응답이 지정된 공통 JSON 구조로 직렬화되는지 검증한다.
    @Test
    void serializesSuccessResponse() throws Exception {
        ApiResponse<Map<String, Integer>> response = ApiResponse.success(Map.of("id", 1));

        JsonNode expected = jsonMapper.readTree("""
                {
                  "success": true,
                  "data": {"id": 1},
                  "error": null
                }
                """);

        assertSameJsonFields(expected, jsonMapper.valueToTree(response));
    }

    // 실패 응답이 데이터 없이 공통 오류 구조로 직렬화되는지 검증한다.
    @Test
    void serializesFailureResponse() throws Exception {
        ApiResponse<Object> response = ApiResponse.failure("USER_NOT_FOUND", "User not found.");

        JsonNode expected = jsonMapper.readTree("""
                {
                  "success": false,
                  "data": null,
                  "error": {
                    "code": "USER_NOT_FOUND",
                    "message": "User not found."
                  }
                }
                """);

        assertSameJsonFields(expected, jsonMapper.valueToTree(response));
    }

    // 공통 응답의 최상위 필드 수와 각 필드 값을 확인한다.
    private void assertSameJsonFields(JsonNode expected, JsonNode actual) {
        assertEquals(3, actual.size());
        assertEquals(expected.get("success"), actual.get("success"));
        assertEquals(expected.get("data"), actual.get("data"));
        assertEquals(expected.get("error"), actual.get("error"));
    }
}
