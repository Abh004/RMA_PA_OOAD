package src.service;

import java.sql.*;

public class DBConnectionPool {

    private static final String URL =
        "jdbc:mysql://localhost:3306/OOAD" +
        "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    // Load credentials from environment variables so plaintext
    // passwords never live in source code.
    private static final String USER =
        System.getenv("DB_USER") != null ? System.getenv("DB_USER") : "root";
    private static final String PASS =
        System.getenv("DB_PASSWORD") != null
            ? System.getenv("DB_PASSWORD")
            : "";

    private static volatile DBConnectionPool instance;

    private DBConnectionPool() {}

    public static DBConnectionPool getInstance() {
        if (instance == null) {
            synchronized (DBConnectionPool.class) {
                if (instance == null) instance = new DBConnectionPool();
            }
        }
        return instance;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }
}
