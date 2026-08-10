package com.leantpm.auth.service;

import com.leantpm.auth.domain.UserAccount;

public record LoginAttemptResult(LoginAttemptDecision decision, UserAccount user) {
    public static LoginAttemptResult authenticated(UserAccount user) {
        return new LoginAttemptResult(LoginAttemptDecision.AUTHENTICATED, user);
    }

    public static LoginAttemptResult failed() {
        return new LoginAttemptResult(LoginAttemptDecision.FAILED, null);
    }

    public static LoginAttemptResult locked() {
        return new LoginAttemptResult(LoginAttemptDecision.LOCKED, null);
    }
}
