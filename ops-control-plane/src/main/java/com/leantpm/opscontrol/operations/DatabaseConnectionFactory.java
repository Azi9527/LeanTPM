package com.leantpm.opscontrol.operations;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface DatabaseConnectionFactory {
    Connection open() throws SQLException;
}
