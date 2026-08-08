package com.leantpm.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "LEANTPM_TEST_DB_URL", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=${LEANTPM_TEST_DB_URL}",
                "spring.datasource.username=${LEANTPM_TEST_DB_USERNAME:root}",
                "spring.datasource.password=${LEANTPM_TEST_DB_PASSWORD:}",
                "spring.data.redis.repositories.enabled=false",
                "management.health.redis.enabled=false",
                "leantpm.security.jwt-secret=integration-test-secret-at-least-32-characters",
                "leantpm.bootstrap.admin-password="
        }
)
@AutoConfigureMockMvc
@DirtiesContext
class SecurityHeadersMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectsUnauthenticatedApiWithReleaseHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/mobile/bootstrap").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        containsString("frame-ancestors 'none'")
                ))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                        "Permissions-Policy",
                        "camera=(), microphone=(), geolocation=()"
                ))
                .andExpect(header().string(
                        "Strict-Transport-Security",
                        containsString("max-age=31536000")
                ))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void rejectsUnlistedCorsOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/mobile/bootstrap")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsConfiguredCorsOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/mobile/bootstrap")
                        .header("Origin", "http://localhost:15173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Access-Control-Allow-Origin",
                        "http://localhost:15173"
                ));
    }

    @Test
    void exposesHealthyReleaseProbe() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesPublicCustomerBrandingWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/public/branding").secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.systemName").value("大宝山矿业设备管理系统"))
                .andExpect(jsonPath("$.data.shortName").value("大宝山矿业"))
                .andExpect(jsonPath("$.data.logoUrl").value("/branding/baoshan-mining-logo.png"))
                .andExpect(jsonPath("$.data.primaryColor").value("#1c7d50"))
                .andExpect(jsonPath("$.data.secondaryColor").value("#3e3a39"))
                .andExpect(jsonPath("$.data.neutralColor").value("#c4000a"));
    }
}
