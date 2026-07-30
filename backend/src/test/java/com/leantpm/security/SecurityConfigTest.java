package com.leantpm.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void usesOnlyExplicitCorsOriginsAndMethods() {
        SecurityConfig security = new SecurityConfig();
        var source = (UrlBasedCorsConfigurationSource) security.corsConfigurationSource(
                List.of(" https://tpm.example.com ", "https://mobile.example.com")
        );

        CorsConfiguration configuration =
                source.getCorsConfigurations().get("/**");
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(
                "https://tpm.example.com",
                "https://mobile.example.com"
        );
        assertThat(configuration.getAllowedMethods()).containsExactly(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        );
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
