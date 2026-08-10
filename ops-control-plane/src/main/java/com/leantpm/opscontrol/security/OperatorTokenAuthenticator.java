package com.leantpm.opscontrol.security;

import java.util.Optional;

public interface OperatorTokenAuthenticator {
    Optional<String> authenticate(String bearerToken);
}
