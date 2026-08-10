package com.leantpm.security.session;

import com.leantpm.security.CurrentUser;
import com.leantpm.security.IssuedTokenPair;
import io.jsonwebtoken.Claims;

import java.util.List;

public interface AuthSessionService {
    void register(CurrentUser user, IssuedTokenPair issued, String loginIp, String userAgent);

    void registerLogin(CurrentUser user, IssuedTokenPair issued, String loginIp, String userAgent);

    void validateAccess(Claims claims);

    void rotate(Claims previousClaims, IssuedTokenPair issued);

    void revoke(String sessionId);

    void revokeAllUserSessions(long tenantId, long userId);

    List<OnlineSession> list(long tenantId, String currentSessionId);

    void kickout(long tenantId, String currentSessionId, String targetSessionId);

}
