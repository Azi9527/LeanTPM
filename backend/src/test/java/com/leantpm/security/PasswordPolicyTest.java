package com.leantpm.security;

import com.leantpm.auth.dto.ChangePasswordRequest;
import com.leantpm.system.dto.SystemDtos;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsSixDigitPasswordsForEveryPasswordFlow() {
        var createUser = new SystemDtos.CreateUserRequest(
                "operator01", "操作工01", "OP-001", "", "",
                1L, true, List.of(3L), "888888"
        );

        assertThat(validator.validate(createUser)).isEmpty();
        assertThat(validator.validate(
                new SystemDtos.ResetPasswordRequest("888888")
        )).isEmpty();
        assertThat(validator.validate(
                new ChangePasswordRequest("old-password", "888888")
        )).isEmpty();
    }

    @Test
    void rejectsPasswordsShorterThanSixCharacters() {
        assertThat(validator.validate(
                new SystemDtos.ResetPasswordRequest("88888")
        )).isNotEmpty();
        assertThat(validator.validate(
                new ChangePasswordRequest("old-password", "88888")
        )).isNotEmpty();
    }
}
