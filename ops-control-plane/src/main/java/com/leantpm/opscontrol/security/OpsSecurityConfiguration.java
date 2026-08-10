package com.leantpm.opscontrol.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
public class OpsSecurityConfiguration {

    @Bean
    SecurityFilterChain opsSecurityFilterChain(
        HttpSecurity http,
        OperatorTokenAuthenticator authenticator
    ) throws Exception {
        OpsBearerTokenFilter bearer = new OpsBearerTokenFilter(authenticator);
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(
                SessionCreationPolicy.STATELESS
            ))
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable())
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/app.js",
                    "/release-tracker.js",
                    "/styles.css",
                    "/favicon.ico",
                    "/actuator/health",
                    "/actuator/health/**"
                ).permitAll()
                .anyRequest().hasRole("RELEASE_OPERATOR")
            )
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, cause) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(
                    "{\"code\":\"OPS_AUTH_REQUIRED\",\"message\":\"Independent operations authentication is required\"}"
                );
            }))
            .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
            .headers(headers -> headers
                .contentSecurityPolicy(policy -> policy.policyDirectives(
                    "default-src 'self'; "
                        + "script-src 'self'; "
                        + "style-src 'self'; "
                        + "img-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "object-src 'none'; "
                        + "base-uri 'none'; "
                        + "frame-ancestors 'none'; "
                        + "form-action 'self'"
                ))
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
            );
        return http.build();
    }
}
