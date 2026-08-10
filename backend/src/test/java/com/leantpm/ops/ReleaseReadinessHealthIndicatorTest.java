package com.leantpm.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

class ReleaseReadinessHealthIndicatorTest {

    @TempDir
    Path uploadRoot;

    @Test
    void reportsUpOnlyWhenTheActualSchemaAndRuntimeUploadIdentityAreReady() throws Exception {
        DataSource dataSource = dataSourceReturning(48);
        ReleaseReadinessHealthIndicator indicator =
                new ReleaseReadinessHealthIndicator(dataSource, 48, uploadRoot.toString());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(uploadRoot.toFile().list()).isEmpty();
    }

    @Test
    void reportsDownWhenTheDatabaseSchemaDoesNotMatchTheReleaseContract() throws Exception {
        ReleaseReadinessHealthIndicator indicator =
                new ReleaseReadinessHealthIndicator(dataSourceReturning(47), 48, uploadRoot.toString());

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private static DataSource dataSourceReturning(int schemaVersion) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(schemaVersion);
        return dataSource;
    }
}
