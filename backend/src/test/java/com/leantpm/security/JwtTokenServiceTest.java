package com.leantpm.security;

import com.leantpm.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    @Test
    void shouldIssueAndParseAccessToken() {
        JwtProperties properties = properties();
        JwtTokenService service = new JwtTokenService(properties);
        CurrentUser expected = new CurrentUser(
                7L,
                1L,
                "operator",
                "设备管理员",
                false,
                Set.of("PLANNER"),
                Set.of("equipment:asset:view", "equipment:asset:update"),
                null
        );

        var issued = service.issue(expected);
        var pair = issued.tokens();
        CurrentUser actual = service.toCurrentUser(service.parse(pair.accessToken(), "access"));

        assertThat(actual).isEqualTo(expected.withSessionId(issued.sessionId()));
        assertThat(service.parse(pair.accessToken(), "access").getId()).isEqualTo(issued.accessTokenId());
        assertThat(service.parse(pair.refreshToken(), "refresh").getId()).isEqualTo(issued.refreshTokenId());
        assertThat(pair.accessExpiresAt()).isBefore(pair.refreshExpiresAt());
    }

    @Test
    void shouldRejectTokenOfWrongType() {
        JwtTokenService service = new JwtTokenService(properties());
        CurrentUser user = new CurrentUser(
                1L, 1L, "admin", "管理员", true, Set.of("ADMIN"), Set.of("system:view"), null
        );
        var pair = service.issue(user).tokens();

        assertThatThrownBy(() -> service.parse(pair.refreshToken(), "access"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("令牌类型错误");
    }

    @Test
    void shouldRejectShortSecret() {
        JwtProperties properties = new JwtProperties();
        properties.setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtTokenService(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    private JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setJwtSecret("test-secret-with-more-than-thirty-two-bytes");
        properties.setAccessTokenMinutes(30);
        properties.setRefreshTokenDays(7);
        return properties;
    }
}
