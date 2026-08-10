package com.leantpm.equipment;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EquipmentCodeValidationTest {
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsChineseEquipmentCodeAndSafeSeparators() {
        assertThat(validator.validate(request("铜精选#1"))).isEmpty();
        assertThat(validator.validate(request("浮选机_A-01.2"))).isEmpty();
    }

    @Test
    void rejectsPathSeparatorsAndWhitespace() {
        assertThat(validator.validate(request("铜精选/1"))).isNotEmpty();
        assertThat(validator.validate(request("铜 精选1"))).isNotEmpty();
    }

    private EquipmentDtos.SaveEquipmentRequest request(String equipmentCode) {
        return new EquipmentDtos.SaveEquipmentRequest(
                equipmentCode, "浮选机", 1L, null, null, null, null, null,
                null, null, 1L, null, null, null, "IN_SERVICE",
                false, false, true, true, null, List.of(), List.of(), null
        );
    }
}
