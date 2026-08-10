package com.leantpm.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Verifies the release contract using the runtime service identity. */
@Component("releaseContract")
public final class ReleaseReadinessHealthIndicator implements HealthIndicator {

    private static final String SCHEMA_QUERY = """
            SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
            FROM flyway_schema_history
            WHERE success = 1
            """;

    private final DataSource dataSource;
    private final int expectedSchemaVersion;
    private final Path uploadRoot;

    public ReleaseReadinessHealthIndicator(
            DataSource dataSource,
            @Value("${info.app.database-schema-version}") int expectedSchemaVersion,
            @Value("${leantpm.storage.upload-dir}") String uploadDirectory) {
        this.dataSource = dataSource;
        this.expectedSchemaVersion = expectedSchemaVersion;
        this.uploadRoot = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @Override
    public Health health() {
        Path probe = null;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SCHEMA_QUERY);
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != expectedSchemaVersion) {
                return Health.down().withDetail("reason", "schema-mismatch").build();
            }
            if (!Files.isDirectory(uploadRoot) || !Files.isWritable(uploadRoot)) {
                return Health.down().withDetail("reason", "upload-root-not-writable").build();
            }
            probe = Files.createTempFile(uploadRoot, ".leantpm-readiness-", ".tmp");
            Files.write(probe, new byte[] {0x4c, 0x54});
            return Health.up()
                    .withDetail("databaseSchemaVersion", expectedSchemaVersion)
                    .withDetail("uploadRootWritable", true)
                    .build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (Exception ignored) {
                    // A failed cleanup makes the next probe or host alert visible; no secret is logged.
                }
            }
        }
    }
}
