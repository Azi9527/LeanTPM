package com.leantpm.auth.controller;

import com.leantpm.auth.dto.ChangePasswordRequest;
import com.leantpm.auth.dto.CaptchaChallenge;
import com.leantpm.auth.dto.LoginRequest;
import com.leantpm.auth.dto.LoginResponse;
import com.leantpm.auth.dto.RefreshTokenRequest;
import com.leantpm.auth.dto.TokenPair;
import com.leantpm.auth.dto.UserProfile;
import com.leantpm.auth.service.AuthService;
import com.leantpm.auth.service.CaptchaService;
import com.leantpm.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final CaptchaService captchaService;

    public AuthController(
            AuthService authService,
            CaptchaService captchaService
    ) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public ApiResponse<CaptchaChallenge> captcha() {
        return ApiResponse.success(captchaService.create());
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authService.login(request, servletRequest));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request.refreshToken()));
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> currentUser() {
        return ApiResponse.success(authService.currentProfile());
    }

    @PutMapping("/password")
    public ApiResponse<TokenPair> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        return ApiResponse.success(authService.changePassword(request, servletRequest));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success();
    }
}
