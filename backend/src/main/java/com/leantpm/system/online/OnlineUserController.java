package com.leantpm.system.online;

import com.leantpm.common.api.ApiResponse;
import com.leantpm.security.session.OnlineSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system/online-users")
public class OnlineUserController {
    private final OnlineUserService service;

    public OnlineUserController(OnlineUserService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:online-user:view')")
    public ApiResponse<List<OnlineSession>> list() {
        return ApiResponse.success(service.list());
    }

    @DeleteMapping("/{sessionId}")
    @PreAuthorize("hasAuthority('system:online-user:kickout')")
    public ApiResponse<Void> kickout(@PathVariable String sessionId) {
        service.kickout(sessionId);
        return ApiResponse.success();
    }
}
