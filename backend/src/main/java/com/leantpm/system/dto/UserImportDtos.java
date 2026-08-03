package com.leantpm.system.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class UserImportDtos {
    private UserImportDtos() {
    }

    public record ImportError(int rowNumber, String column, String message) {
    }

    public record ImportResult(
            String batchId,
            String status,
            String strategy,
            int totalRows,
            int validRows,
            int newUsers,
            int updatedUsers,
            int skippedUsers,
            List<ImportError> errors,
            LocalDateTime committedTime
    ) {
    }

    public record UserInput(
            int rowNumber,
            String username,
            String realName,
            String employeeNo,
            String mobile,
            String email,
            String organizationCode,
            List<String> roleCodes,
            boolean mobileEnabled,
            String initialPassword,
            String strategy
    ) {
    }

    public record ImportPayload(List<UserInput> users) {
    }

    public record ImportCounts(int newUsers, int updatedUsers, int skippedUsers) {
    }

    public record BatchRow(
            String batchId,
            String status,
            String strategy,
            String payloadJson,
            String errorsJson,
            String resultJson,
            int totalRows,
            LocalDateTime committedTime
    ) {
    }
}
