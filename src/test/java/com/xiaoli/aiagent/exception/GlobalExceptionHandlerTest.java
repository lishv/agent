package com.xiaoli.aiagent.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    MockMvc mvc;
    @RestController
    static class Endpoints {
        @GetMapping("/failure/{code}")
        public String fail(@PathVariable int code) {
            if (code == -1) throw new IllegalStateException("internal-db-password-and-url");
            if (code == -2) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "internal-path");
            if (code == -3) throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "internal-format");
            throw new BusinessException(ErrorCode.fromCode(code));
        }
        @PostMapping("/validate")
        public String validate(@Valid @RequestBody Input input) { return "ok"; }
    }
    record Input(@NotBlank String message) { }
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void businessErrorsHaveNumericCodesAndPreserveHttpStatus() throws Exception {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.PARAMS_ERROR, ErrorCode.NOT_LOGIN_ERROR, ErrorCode.FORBIDDEN_ERROR,
                ErrorCode.ACCOUNT_CHANGED, ErrorCode.TASK_ENDED, ErrorCode.TOO_MANY_REQUESTS, ErrorCode.SERVICE_UNAVAILABLE}) {
            mvc.perform(get("/failure/" + code.getCode())).andExpect(status().is(code.getHttpStatus().value()))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value(code.getCode())).andExpect(jsonPath("$.message").value(code.getMessage()));
        }
    }
    @Test void unexpectedFailuresDoNotExposeInternalDetailsEvenWhenSseIsRequested() throws Exception {
        var result = mvc.perform(get("/failure/-1").accept(MediaType.TEXT_EVENT_STREAM)).andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(50000)).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("internal-db-password"));
    }
    @Test void frameworkAndBodyValidationErrorsUseTheSameErrorShape() throws Exception {
        mvc.perform(get("/failure/-2")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40400));
        mvc.perform(get("/failure/-3")).andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/failure/not-a-number")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("invalid-json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40000));
    }
    @Test void guardsThrowOnlyWhenConditionIsTrueAndPreserveTheCause() {
        assertDoesNotThrow(() -> ThrowUtils.throwIf(false, ErrorCode.PARAMS_ERROR));
        assertEquals(40000, assertThrows(BusinessException.class, () -> ThrowUtils.throwIf(true, ErrorCode.PARAMS_ERROR)).getCode());
        var cause = new IllegalStateException("root cause");
        assertSame(cause, new BusinessException(ErrorCode.OPERATION_ERROR, "操作失败", cause).getCause());
    }
}
