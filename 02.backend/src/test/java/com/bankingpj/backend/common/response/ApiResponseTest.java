package com.bankingpj.backend.common.response;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

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

    private void assertSameJsonFields(JsonNode expected, JsonNode actual) {
        assertEquals(3, actual.size());
        assertEquals(expected.get("success"), actual.get("success"));
        assertEquals(expected.get("data"), actual.get("data"));
        assertEquals(expected.get("error"), actual.get("error"));
    }
}
