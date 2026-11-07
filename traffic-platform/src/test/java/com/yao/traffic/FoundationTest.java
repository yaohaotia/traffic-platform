package com.yao.traffic;

import com.yao.traffic.shared.web.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FoundationTest.ProbeConfiguration.class)
class FoundationTest {
    @Autowired MockMvc mvc;
    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startsWebApplicationAndReturnsBuildInformation() throws Exception {
        mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("traffic-platform"))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void databaseIsAvailableAndHealthDetailsAreNotExposed() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void migratesAnIsolatedDatabaseAndRepeatedMigrationDoesNotDuplicateData() {
        assertThat(jdbc.queryForObject("SELECT metadata_value FROM schema_metadata WHERE metadata_key = 'application'",
                String.class)).isEqualTo("traffic-platform");
        assertThat(flyway.info().current().getVersion().getVersion()).isNotBlank();
        assertThat(flyway.info().applied()).extracting(info -> info.getVersion().getVersion()).contains("1");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_metadata", Integer.class)).isEqualTo(1);
    }

    @Test
    void returnsValidationErrorsWithRequestIdAndDoesNotEchoRejectedValues() throws Exception {
        mvc.perform(post("/test-only/validation").header("X-Request-Id", "validation-1")
                        .contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("validation-1"))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[0].rejectedValue").doesNotExist());
    }

    @Test
    void malformedJsonUsesTheSharedErrorEnvelope() throws Exception {
        mvc.perform(post("/test-only/validation").contentType("application/json").content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingResourceReturns404InsteadOfBusinessSuccess() throws Exception {
        mvc.perform(get("/api/v1/missing").with(user("foundation-probe")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/missing"));
    }

    @Test
    void businessRejectionPreservesItsHttpStatusAndCode() throws Exception {
        mvc.perform(get("/test-only/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void unexpectedErrorDoesNotLeakImplementationDetails() throws Exception {
        String body = mvc.perform(get("/test-only/failure").header("X-Request-Id", "failure-1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("failure-1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("sensitive database detail", "IllegalStateException", "stackTrace");
    }

    @Test
    void requestIdIsPropagatedAndThreadContextIsCleared() throws Exception {
        mvc.perform(get("/api/v1/system/info").header("X-Request-Id", "client-request-123"))
                .andExpect(header().string("X-Request-Id", "client-request-123"));
        assertThat(MDC.get("requestId")).isNull();
        String generated = mvc.perform(get("/api/v1/system/info").header("X-Request-Id", "invalid request id"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(MDC.get("requestId")).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ProbeController probeController() { return new ProbeController(); }

        @Bean
        @Order(-100)
        SecurityFilterChain testProbeSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test-only/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll()).build();
        }
    }

    /** Failure and validation probes are test-only and never enter the deliverable jar. */
    @RestController
    static class ProbeController {
        @PostMapping("/test-only/validation")
        Map<String, String> validation(@Valid @RequestBody ProbeInput input) {
            return Map.of("name", input.name());
        }

        @GetMapping("/test-only/conflict")
        void conflict() { throw new BusinessException(HttpStatus.CONFLICT, "STALE_VERSION", "状态已发生变化"); }

        @GetMapping("/test-only/failure")
        void failure() { throw new IllegalStateException("sensitive database detail"); }
    }

    record ProbeInput(@NotBlank(message = "名称不能为空") String name) { }
}
