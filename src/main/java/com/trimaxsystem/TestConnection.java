package com.trimaxsystem;

import java.sql.Connection;
import java.sql.SQLException;

public class TestConnection {
    public static void main(String[] args) {
        try {
            Connection conn = DatabaseConnection.getConnection();
            if (conn != null) {
                System.out.println("Connection test PASSED!");
                conn.close();
            } else {
                System.out.println("Connection test FAILED: Connection was null.");
            }
        } catch (SQLException e) {
            System.out.println("Connection test FAILED with exception:");
            e.printStackTrace();
        }
    }
}