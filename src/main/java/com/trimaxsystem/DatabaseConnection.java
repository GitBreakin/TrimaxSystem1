package com.trimaxsystem;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class DatabaseConnection {

    private static final String URL =
            "jdbc:postgresql://aws-0-ap-southeast-1.pooler.supabase.com:5432/postgres";

    private static final String USER = "postgres.dfxjblrletoeukoarxlz";

    private static final String PASSWORD = "";

    public static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASSWORD);
        props.setProperty("sslmode", "require");
        props.setProperty("connectTimeout", "10");

        return DriverManager.getConnection(URL, props);
    }
}
