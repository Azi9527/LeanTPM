package com.leantpm.system.online;

import com.leantpm.security.SecurityUtils;
import com.leantpm.security.session.AuthSessionService;
import com.leantpm.security.session.OnlineSession;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OnlineUserService {
    private final AuthSessionService sessionService;

    public OnlineUserService(AuthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public List<OnlineSession> list() {
        var current = SecurityUtils.currentUser();
        return sessionService.list(current.tenantId(), current.sessionId());
    }

    public void kickout(String sessionId) {
        var current = SecurityUtils.currentUser();
        sessionService.kickout(current.tenantId(), current.sessionId(), sessionId);
    }
}
