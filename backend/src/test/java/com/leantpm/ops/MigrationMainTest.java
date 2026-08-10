package com.leantpm.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationMainTest {

    @Test
    void acceptsOnlyAnExplicitBoundedMySqlMigrationContract() {
        Map<String, String> environment = new HashMap<>();
        environment.put("LEANTPM_MIGRATOR_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/leantpm_restore_001"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC"
                        + "&sslMode=VERIFY_IDENTITY");
        environment.put("LEANTPM_MIGRATOR_DB_USERNAME", "leantpm_migrator");
        environment.put("LEANTPM_MIGRATOR_DB_PASSWORD", "synthetic-test-value");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_FROM", "47");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_TO", "48");
        environment.put("LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        MigrationMain.MigrationSettings settings =
                MigrationMain.MigrationSettings.from(environment);

        assertEquals(47, settings.schemaFrom());
        assertEquals(48, settings.schemaTo());
        assertEquals("leantpm_migrator", settings.username());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", settings.expectedServerUuid());
    }

    @Test
    void acceptsZeroOnlyAsAnExplicitFirstInstallSchemaBoundary() {
        Map<String, String> environment = new HashMap<>();
        environment.put("LEANTPM_MIGRATOR_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/leantpm_first_install_001"
                        + "?sslMode=VERIFY_IDENTITY");
        environment.put("LEANTPM_MIGRATOR_DB_USERNAME", "leantpm_migrator");
        environment.put("LEANTPM_MIGRATOR_DB_PASSWORD", "synthetic-test-value");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_FROM", "0");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_TO", "48");
        environment.put("LEANTPM_MIGRATOR_EXPECTED_SERVER_UUID",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        MigrationMain.MigrationSettings settings =
                MigrationMain.MigrationSettings.from(environment);

        assertEquals(0, settings.schemaFrom());
        assertEquals(48, settings.schemaTo());
    }

    @Test
    void rejectsMissingSecretsMultiQueriesAndAnInvalidRange() {
        Map<String, String> environment = new HashMap<>();
        environment.put("LEANTPM_MIGRATOR_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/leantpm?allowMultiQueries=true");
        environment.put("LEANTPM_MIGRATOR_DB_USERNAME", "leantpm_migrator");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_FROM", "48");
        environment.put("LEANTPM_MIGRATOR_SCHEMA_TO", "47");

        assertThrows(IllegalArgumentException.class,
                () -> MigrationMain.MigrationSettings.from(environment));
    }
}
