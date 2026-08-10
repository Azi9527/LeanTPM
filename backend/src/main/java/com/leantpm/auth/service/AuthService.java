package com.leantpm.auth.service;

import com.leantpm.auth.domain.UserAccount;
import com.leantpm.auth.dto.ChangePasswordRequest;
import com.leantpm.auth.dto.LoginRequest;
import com.leantpm.auth.dto.LoginResponse;
import com.leantpm.auth.dto.TokenPair;
import com.leantpm.auth.dto.UserProfile;
import com.leantpm.auth.mapper.AuthMapper;
import com.leantpm.common.exception.BusinessException;
import com.leantpm.security.CurrentUser;
import com.leantpm.security.JwtTokenService;
import com.leantpm.security.SecurityUtils;
import com.leantpm.security.session.AuthSessionService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class AuthService {
    public static final long DEFAULT_TENANT_ID = 1L;

    private final AuthMapper authMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    private final AuthSessionService sessionService;
    private final DatabaseLoginAttemptService loginAttemptService;

    public AuthService(
            AuthMapper authMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService,
            AuthSessionService sessionService,
            DatabaseLoginAttemptService loginAttemptService
    ) {
        this.authMapper = authMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.loginAttemptService = loginAttemptService;
    }

    public LoginResponse login(LoginRequest request, HttpServletRequest servletRequest) {
        String username = request.username().trim();
        LoginAttemptResult attempt;
        try {
            attempt = loginAttemptService.verify(
                    DEFAULT_TENANT_ID,
                    username,
                    request.password(),
                    clientIp(servletRequest),
                    safeUserAgent(servletRequest)
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(
                    "AUTH_STATE_UNAVAILABLE",
                    "认证安全状态暂不可用，请稍后重试",
                    HttpStatus.SERVICE_UNAVAILABLE
            );
        }
        if (attempt.decision() != LoginAttemptDecision.AUTHENTICATED) {
            throw new BusinessException("LOGIN_FAILED", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
        }
        UserAccount user = attempt.user();

        CurrentUser currentUser = loadCurrentUser(user);
        var issued = tokenService.issue(currentUser);
        sessionService.registerLogin(
                currentUser,
                issued,
                clientIp(servletRequest),
                safeUserAgent(servletRequest)
        );
        return new LoginResponse(issued.tokens(), toProfile(currentUser));
    }

    public TokenPair refresh(String refreshToken) {
        Claims claims = tokenService.parse(refreshToken, "refresh");
        long tenantId = claims.get("tid", Number.class).longValue();
        long userId = claims.get("uid", Number.class).longValue();
        Number claimedEpoch = claims.get("uv", Number.class);
        UserAccount account = requireActiveUser(tenantId, userId);
        if (claimedEpoch == null || account.getAuthEpoch() == null
                || claimedEpoch.longValue() != account.getAuthEpoch()) {
            throw new BusinessException(
                    "TOKEN_SESSION_INVALID",
                    "The account security state changed after this refresh token was issued",
                    HttpStatus.UNAUTHORIZED
            );
        }
        String sessionId = claims.get("sid", String.class);
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException("INVALID_REFRESH_TOKEN", "刷新令牌缺少会话标识", HttpStatus.UNAUTHORIZED);
        }
        CurrentUser currentUser = loadCurrentUser(account).withSessionId(sessionId);
        var issued = tokenService.issue(currentUser, sessionId);
        sessionService.rotate(claims, issued);
        return issued.tokens();
    }

    @Transactional(readOnly = true)
    public UserProfile currentProfile() {
        return toProfile(SecurityUtils.currentUser());
    }

    @Transactional
    public TokenPair changePassword(ChangePasswordRequest request, HttpServletRequest servletRequest) {
        CurrentUser current = SecurityUtils.currentUser();
        UserAccount account = requireActiveUser(current.tenantId(), current.userId());
        if (!passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            throw new BusinessException("CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), account.getPasswordHash())) {
            throw new BusinessException("PASSWORD_REUSED", "新密码不能与当前密码相同");
        }
        authMapper.updatePassword(
                current.tenantId(),
                current.userId(),
                passwordEncoder.encode(request.newPassword())
        );
        UserAccount refreshed = requireActiveUser(current.tenantId(), current.userId());
        sessionService.revokeAllUserSessions(current.tenantId(), current.userId());
        CurrentUser refreshedUser = loadCurrentUser(refreshed);
        var issued = tokenService.issue(refreshedUser);
        sessionService.register(
                refreshedUser,
                issued,
                clientIp(servletRequest),
                safeUserAgent(servletRequest)
        );
        return issued.tokens();
    }

    public void logout() {
        sessionService.revoke(SecurityUtils.currentUser().sessionId());
    }

    private UserAccount requireActiveUser(long tenantId, long userId) {
        UserAccount account = authMapper.findById(tenantId, userId);
        if (account == null || account.getStatus() == null || account.getStatus() != 1) {
            throw new BusinessException("ACCOUNT_DISABLED", "账号不存在或已停用", HttpStatus.UNAUTHORIZED);
        }
        return account;
    }

    private CurrentUser loadCurrentUser(UserAccount user) {
        Set<String> roles = authMapper.findRoleCodes(user.getTenantId(), user.getId());
        Set<String> permissions = authMapper.findPermissionCodes(user.getTenantId(), user.getId());
        return new CurrentUser(
                user.getId(),
                user.getTenantId(),
                user.getUsername(),
                user.getRealName(),
                Boolean.TRUE.equals(user.getMustChangePassword()),
                roles,
                permissions,
                user.getAuthEpoch() == null ? 0L : user.getAuthEpoch(),
                null
        );
    }

    private UserProfile toProfile(CurrentUser user) {
        return new UserProfile(
                user.userId(),
                user.tenantId(),
                user.username(),
                user.realName(),
                user.mustChangePassword(),
                user.roles(),
                user.permissions(),
                authMapper.findMenus(user.tenantId(), user.userId())
        );
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String safeUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) {
            return null;
        }
        return userAgent.substring(0, Math.min(500, userAgent.length()));
    }
}
