/*
 * Copyright 2004-2008 H2 Group. Licensed under the H2 License, Version 1.0
 * (license2)
 * Initial Developer: H2 Group
 */
package org.h2.test.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.test.TestBase;

/**
 * Test using an encrypted database.
 */
public class TestEncryptedDb extends TestBase {

    public void test() throws Exception {
        if (config.memory || config.cipher != null) {
            return;
        }
        deleteDb("exclusive");
        Connection conn = getConnection("exclusive;CIPHER=AES", "sa", "123 123");
        Statement stat = conn.createStatement();
        stat.execute("CREATE TABLE TEST(ID INT)");
        stat.execute("CHECKPOINT");
        stat.execute("SET WRITE_DELAY 0");
        stat.execute("INSERT INTO TEST VALUES(1)");
        stat.execute("SHUTDOWN IMMEDIATELY");
        try {
            conn.close();
        } catch (SQLException e) {
            checkNotGeneralException(e);
        }

        try {
            conn = getConnection("exclusive;CIPHER=AES", "sa", "1234 1234");
            error();
        } catch (SQLException e) {
            checkNotGeneralException(e);
        }

        conn = getConnection("exclusive;CIPHER=AES", "sa", "123 123");
        stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM TEST");
        check(rs.next());
        check(1, rs.getInt(1));
        checkFalse(rs.next());

        conn.close();
    }

}